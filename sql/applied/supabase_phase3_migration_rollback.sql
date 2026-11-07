-- Rollback for supabase_phase3_migration.sql. Run ONLY if you need to undo
-- Phase 3. Drops the trigger, function, policies and the six new tables.
-- Existing tables (donors, donations, requests, stock, offers, …) are untouched.

drop trigger if exists trg_prevent_overbooking on public.appointment_bookings;
drop trigger if exists trg_decrement_booked on public.appointment_bookings;
drop function if exists public.prevent_overbooking();
drop function if exists public.decrement_booked();

drop policy if exists "stock movements write managers" on public.stock_movements;
drop policy if exists "stock movements read all authenticated" on public.stock_movements;
drop table if exists public.stock_movements;

drop policy if exists "verifications write managers" on public.donor_verifications;
drop policy if exists "verifications read all authenticated" on public.donor_verifications;
drop table if exists public.donor_verifications;

drop policy if exists "bookings delete managers" on public.appointment_bookings;
drop policy if exists "bookings insert authenticated" on public.appointment_bookings;
drop policy if exists "bookings read all authenticated" on public.appointment_bookings;
drop table if exists public.appointment_bookings;

drop policy if exists "slots write managers" on public.appointment_slots;
drop policy if exists "slots read all authenticated" on public.appointment_slots;
drop table if exists public.appointment_slots;

drop policy if exists "camp attendance delete managers" on public.camp_attendance;
drop policy if exists "camp attendance insert authenticated" on public.camp_attendance;
drop policy if exists "camp attendance read all authenticated" on public.camp_attendance;
drop table if exists public.camp_attendance;

drop policy if exists "camps write managers" on public.camps;
drop policy if exists "camps read all authenticated" on public.camps;
drop table if exists public.camps;
