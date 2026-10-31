-- Rollback for supabase_fcm_triggers.sql. Removes the triggers and their
-- functions only. pg_net is deliberately left installed because other
-- features may use it — uncomment the last line if you want it gone too.
drop trigger if exists push_after_offer_insert on offers;
drop trigger if exists push_after_offer_accepted on offers;
drop trigger if exists push_after_urgent_request on requests;
drop function if exists public.on_offer_inserted();
drop function if exists public.on_offer_accepted();
drop function if exists public.on_urgent_request();
drop function if exists public.push_send(jsonb);
drop function if exists public.push_url();
drop function if exists public.push_secret();
-- drop extension if exists pg_net;
