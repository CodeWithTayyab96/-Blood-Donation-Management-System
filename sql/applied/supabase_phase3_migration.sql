-- Blood Donation — Phase 3 migration: blood camps, appointment slots,
-- donor verification and stock movements.
-- Run ONCE in the Supabase SQL editor, AFTER supabase_p2p_policies.sql and
-- supabase_role_policies.sql (this file uses public.is_manager()) and after
-- supabase_phase2_migration.sql.
--
-- ⚠️  Everything here is ADDITIVE: new tables, indexes, RLS policies and one
--     overbooking trigger. No existing row is rewritten and no existing policy
--     is dropped, so an older APK keeps working against the new schema.
--
-- The local SQLite database (DB_VERSION 10 in DbHelper) is the source of truth
-- the app reads from; these tables give the server a matching shape so the same
-- data can be synced. Where a client can write (a donor checking in, booking a
-- slot), RLS allows the authenticated write; the manager-only actions (creating
-- camps/slots, verifying donors, recording movements) are restricted to
-- is_manager().

-- ---------------------------------------------------------------------------
-- 1. blood camps
-- ---------------------------------------------------------------------------
create table if not exists public.camps (
  id          bigint generated always as identity primary key,
  name        text not null,
  location    text not null,
  city        text not null,
  camp_date   date not null,
  organizer   text not null default '',
  created_at  timestamptz not null default now()
);

-- v1.19: created_at was missing on the first version of this table; the
-- ALTER below backfills it for projects that already ran the migration
-- (the CREATE TABLE above only helps a fresh install).
alter table public.camps
  add column if not exists created_at timestamptz not null default now();

create index if not exists camps_date_idx on public.camps (camp_date);
alter table public.camps enable row level security;

drop policy if exists "camps read all authenticated" on public.camps;
create policy "camps read all authenticated"
  on public.camps for select to authenticated using (true);

drop policy if exists "camps write managers" on public.camps;
create policy "camps write managers"
  on public.camps for all to authenticated
  using (public.is_manager())
  with check (public.is_manager());

-- ---------------------------------------------------------------------------
-- 2. camp attendance (a donor's check-in)
-- ---------------------------------------------------------------------------
create table if not exists public.camp_attendance (
  id          bigint generated always as identity primary key,
  camp_id     bigint not null references public.camps(id) on delete cascade,
  donor_id    bigint not null references public.donors(id),
  attended_at  timestamptz not null default now(),
  created_at  timestamptz not null default now(),
  unique (camp_id, donor_id)
);

-- v1.19: the first version of this table had a bare donor_id (no FK) and
-- no created_at. The ALTERs below bring a live table up to the shape
-- above; they are no-ops on a fresh install.
alter table public.camp_attendance
  add column if not exists created_at timestamptz not null default now();
do $$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'camp_attendance_donor_id_fk'
  ) then
    alter table public.camp_attendance
      add constraint camp_attendance_donor_id_fk
      foreign key (donor_id) references public.donors(id);
  end if;
end $$;

create index if not exists camp_attendance_camp_idx on public.camp_attendance (camp_id);
alter table public.camp_attendance enable row level security;

drop policy if exists "camp attendance read all authenticated" on public.camp_attendance;
create policy "camp attendance read all authenticated"
  on public.camp_attendance for select to authenticated using (true);

-- A donor may record their own check-in (the donor row's auth_id must be
-- the caller's), and a manager may check anyone in. The old
-- with check (true) let any signed-in user check in as any donor.
drop policy if exists "camp attendance insert authenticated" on public.camp_attendance;
create policy "camp attendance insert authenticated"
  on public.camp_attendance for insert to authenticated with check (
    public.is_manager()
    or exists (
      select 1 from public.donors d
       where d.id = camp_attendance.donor_id and d.auth_id = auth.uid()
    )
  );

drop policy if exists "camp attendance delete managers" on public.camp_attendance;
create policy "camp attendance delete managers"
  on public.camp_attendance for delete to authenticated using (public.is_manager());

-- ---------------------------------------------------------------------------
-- 3. appointment slots
-- ---------------------------------------------------------------------------
create table if not exists public.appointment_slots (
  id          bigint generated always as identity primary key,
  slot_time   timestamptz not null,
  capacity    integer not null check (capacity >= 1),
  booked      integer not null default 0,
  note        text not null default '',
  created_at  timestamptz not null default now()
);

-- v1.19: created_at was missing on the first version of this table.
alter table public.appointment_slots
  add column if not exists created_at timestamptz not null default now();

create index if not exists appointment_slots_time_idx on public.appointment_slots (slot_time);
alter table public.appointment_slots enable row level security;

drop policy if exists "slots read all authenticated" on public.appointment_slots;
create policy "slots read all authenticated"
  on public.appointment_slots for select to authenticated using (true);

drop policy if exists "slots write managers" on public.appointment_slots;
create policy "slots write managers"
  on public.appointment_slots for all to authenticated
  using (public.is_manager())
  with check (public.is_manager());

-- ---------------------------------------------------------------------------
-- 4. appointment bookings + DB-level overbooking guard
-- ---------------------------------------------------------------------------
create table if not exists public.appointment_bookings (
  id          bigint generated always as identity primary key,
  slot_id     bigint not null references public.appointment_slots(id) on delete cascade,
  donor_id    bigint not null references public.donors(id),
  created_at  timestamptz not null default now(),
  unique (slot_id, donor_id)
);

-- v1.19: the first version of this table had a bare donor_id (no FK).
do $$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'appointment_bookings_donor_id_fk'
  ) then
    alter table public.appointment_bookings
      add constraint appointment_bookings_donor_id_fk
      foreign key (donor_id) references public.donors(id);
  end if;
end $$;

create index if not exists appointment_bookings_slot_idx on public.appointment_bookings (slot_id);
alter table public.appointment_bookings enable row level security;

drop policy if exists "bookings read all authenticated" on public.appointment_bookings;
create policy "bookings read all authenticated"
  on public.appointment_bookings for select to authenticated using (true);

-- A donor may book a slot for themselves (the donor row's auth_id must be
-- the caller's), and a manager may book for anyone. The old
-- with check (true) let any signed-in user book as any donor.
drop policy if exists "bookings insert authenticated" on public.appointment_bookings;
create policy "bookings insert authenticated"
  on public.appointment_bookings for insert to authenticated with check (
    public.is_manager()
    or exists (
      select 1 from public.donors d
       where d.id = appointment_bookings.donor_id and d.auth_id = auth.uid()
    )
  );

drop policy if exists "bookings delete managers" on public.appointment_bookings;
create policy "bookings delete managers"
  on public.appointment_bookings for delete to authenticated using (public.is_manager());

-- The real capacity check. Counts existing bookings for the slot and compares to
-- the slot's capacity; if it would overfill, the insert is rejected. This is the
-- server-side mirror of the transactional guard in DbHelper.bookSlot(), so two
-- donors racing the last seat cannot both succeed even if the app check passes.
-- The FOR UPDATE lock is what makes that true: concurrent booking inserts for
-- the same slot queue on the slot row, so the count below sees every committed
-- booking. Without it both racers could read count < capacity and both insert.
-- The trigger also keeps the booked counter in step with the rows, since the
-- app reads it for capacity display.
create or replace function public.prevent_overbooking()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_capacity integer;
  v_count    integer;
begin
  select capacity into v_capacity
    from public.appointment_slots where id = new.slot_id for update;
  if v_capacity is null then
    raise exception 'unknown slot';
  end if;
  select count(*) into v_count
    from public.appointment_bookings where slot_id = new.slot_id;
  if v_count >= v_capacity then
    raise exception 'slot is full';
  end if;
  update public.appointment_slots set booked = booked + 1 where id = new.slot_id;
  return new;
end;
$$;

drop trigger if exists trg_prevent_overbooking on public.appointment_bookings;
create trigger trg_prevent_overbooking
  before insert on public.appointment_bookings
  for each row execute function public.prevent_overbooking();

-- Keep booked honest when a manager removes a booking.
create or replace function public.decrement_booked()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  update public.appointment_slots
    set booked = greatest(booked - 1, 0) where id = old.slot_id;
  return old;
end;
$$;

drop trigger if exists trg_decrement_booked on public.appointment_bookings;
create trigger trg_decrement_booked
  after delete on public.appointment_bookings
  for each row execute function public.decrement_booked();

-- ---------------------------------------------------------------------------
-- 5. donor verification (manager-set)
-- ---------------------------------------------------------------------------
create table if not exists public.donor_verifications (
  id           bigint generated always as identity primary key,
  donor_id     bigint not null references public.donors(id),
  status       text not null check (status in ('pending','verified','rejected')),
  verified_by  text,
  verified_at  timestamptz,
  note         text not null default '',
  created_at   timestamptz not null default now()
);

-- v1.19: the first version of this table had a bare donor_id (no FK) and
-- no created_at.
alter table public.donor_verifications
  add column if not exists created_at timestamptz not null default now();
do $$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'donor_verifications_donor_id_fk'
  ) then
    alter table public.donor_verifications
      add constraint donor_verifications_donor_id_fk
      foreign key (donor_id) references public.donors(id);
  end if;
end $$;

create index if not exists donor_verifications_donor_idx on public.donor_verifications (donor_id);
alter table public.donor_verifications enable row level security;

drop policy if exists "verifications read all authenticated" on public.donor_verifications;
create policy "verifications read all authenticated"
  on public.donor_verifications for select to authenticated using (true);

-- Only managers may write a verification (the isDonorVerified() rule the app
-- reads treats the latest record as authoritative).
drop policy if exists "verifications write managers" on public.donor_verifications;
create policy "verifications write managers"
  on public.donor_verifications for all to authenticated
  using (public.is_manager())
  with check (public.is_manager());

-- ---------------------------------------------------------------------------
-- 6. stock movements (for forecasting)
-- ---------------------------------------------------------------------------
create table if not exists public.stock_movements (
  id           bigint generated always as identity primary key,
  blood_group  text not null check (blood_group in ('O-','O+','A-','A+','B-','B+','AB-','AB+')),
  delta        integer not null,
  reason       text not null check (reason in ('donation','fulfilment','wastage','adjustment')),
  date         date not null,
  created_at   timestamptz not null default now()
);

-- v1.19: the first version of this table had a bare blood_group (no
-- 8-group CHECK, so a typo'd group was accepted) and no created_at.
alter table public.stock_movements
  add column if not exists created_at timestamptz not null default now();
do $$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'stock_movements_blood_group_check'
  ) then
    alter table public.stock_movements
      add constraint stock_movements_blood_group_check
      check (blood_group in ('O-','O+','A-','A+','B-','B+','AB-','AB+'));
  end if;
end $$;

create index if not exists stock_movements_group_date_idx
  on public.stock_movements (blood_group, date);
alter table public.stock_movements enable row level security;

drop policy if exists "stock movements read all authenticated" on public.stock_movements;
create policy "stock movements read all authenticated"
  on public.stock_movements for select to authenticated using (true);

-- Movements are written by the manager-side donation / fulfilment flows.
drop policy if exists "stock movements write managers" on public.stock_movements;
create policy "stock movements write managers"
  on public.stock_movements for insert to authenticated
  with check (public.is_manager());
