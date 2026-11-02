-- Blood Donation Management System — Supabase schema
-- Mirrors the app's local SQLite schema (DbHelper.kt, DB v2).
-- Safe to run on a fresh, empty project. Run once in the Supabase SQL editor.

create table if not exists donors (
  id bigint generated always as identity primary key,
  name text not null,
  age integer not null check (age >= 0),
  gender text not null,
  blood_group text not null check (blood_group in ('O-','O+','A-','A+','B-','B+','AB-','AB+')),
  phone text not null,
  city text not null,
  weight real not null check (weight > 0),
  last_donation_date date,
  created_at timestamptz not null default now()
);

create table if not exists donations (
  id bigint generated always as identity primary key,
  donor_id bigint not null references donors(id) on delete cascade,
  date date not null,
  units integer not null check (units > 0),
  created_at timestamptz not null default now()
);

create table if not exists requests (
  id bigint generated always as identity primary key,
  patient_name text not null,
  hospital text not null,
  blood_group text not null check (blood_group in ('O-','O+','A-','A+','B-','B+','AB-','AB+')),
  units_needed integer not null check (units_needed > 0),
  urgency text not null,
  contact text not null,
  status text not null default 'Pending',
  created_at timestamptz not null default now()
);

create table if not exists stock (
  blood_group text primary key check (blood_group in ('O-','O+','A-','A+','B-','B+','AB-','AB+')),
  units integer not null default 0 check (units >= 0)
);

-- NOTE: the legacy `users` table (username / password_hash / role /
-- donor_id) is deliberately NOT created here any more. It was never read
-- or written by the app from v1.4 on (Supabase Auth owns accounts), and
-- because it had no Row Level Security the anon key could read every
-- password hash through PostgREST. If you applied an older version of
-- this file, run `fix_users_table.sql` to drop it.

-- Seed the 8 blood groups in stock (same as the app does on first launch).
insert into stock (blood_group, units) values
  ('O-', 0), ('O+', 0), ('A-', 0), ('A+', 0),
  ('B-', 0), ('B+', 0), ('AB-', 0), ('AB+', 0)
on conflict (blood_group) do nothing;

-- Seed the same 5 sample donors as the app (demo data only).
insert into donors (name, age, gender, blood_group, phone, city, weight, last_donation_date) values
  ('Ali Raza', 24, 'Male', 'B+', '0300-1112233', 'Faisalabad', 68, null),
  ('Fatima Noor', 21, 'Female', 'O+', '0321-4455667', 'Shahkot', 55, null),
  ('Usman Ali', 29, 'Male', 'O-', '0333-9988776', 'Jaranwala', 74, null),
  ('Ayesha Khan', 26, 'Female', 'A+', '0345-2233445', 'Faisalabad', 60, null),
  ('Bilal Ahmed', 32, 'Male', 'AB+', '0301-6677889', 'Lahore', 80, null)
on conflict do nothing;
