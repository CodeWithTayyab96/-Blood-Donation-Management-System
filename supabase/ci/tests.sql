-- ============================================================================
-- TEST-ONLY — supabase/ci/tests.sql
-- NEVER run this on the live Supabase project (it would fail loudly there —
-- it impersonates users by writing request.jwt.claims, which needs superuser,
-- and its carbon-copy UUIDs are meaningless on real data).
--
-- Runs against the stubs in supabase/ci/stubs.sql after every repository SQL
-- file has been applied in the documented order. Every check lives inside
-- BEGIN ... ROLLBACK, so this file is a pure read/probe pass: it changes no
-- data, is safe to re-run, and is run twice by CI (after the forward pass and
-- again after rollback + re-apply).
--
-- Any failure raises a SQL error whose message starts with 'CI-TEST FAIL',
-- and the job runs psql with ON_ERROR_STOP, so a failure fails the job —
-- including a PASS that never prints (the marker select at the bottom).
--
-- Identity map
--   R1 11111111-...  requester A      D1 33333333-...  donor A
--   R2 22222222-...  requester B      D2 44444444-...  donor B
--   M  55555555-...  the manager (raw_app_meta_data.role = MANAGER)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- T1. contact_details is hidden until an offer is ACCEPTED — the only path
--     that reveals a phone number. Covers both sides (requester reads donor
--     and donor reads requester) and the negative control (a stranger).
-- ---------------------------------------------------------------------------
begin;

insert into auth.users (id, email, raw_app_meta_data)
values
  ('11111111-1111-1111-1111-111111111111', 'ci.requester1@example.test', '{}'),
  ('22222222-2222-2222-2222-222222222222', 'ci.requester2@example.test', '{}'),
  ('33333333-3333-3333-3333-333333333333', 'ci.donor1@example.test',    '{}'),
  ('44444444-4444-4444-4444-444444444444', 'ci.donor2@example.test',    '{}')
on conflict (id) do nothing;

-- Two donor profiles, the phone rows, and one open request + offer each (both
-- still status 'Offered' / nothing accepted anywhere yet).
insert into donors (name, age, gender, blood_group, phone, city, weight, auth_id, available_to_donate)
values
  ('CI Donor One', 25, 'Male', 'B+', '0300-0000001', 'Cividale', 70,
   '33333333-3333-3333-3333-333333333333', true),
  ('CI Donor Two', 27, 'Female', 'O+', '0300-0000004', 'Cividale', 72,
   '44444444-4444-4444-4444-444444444444', true);

insert into contact_details (user_id, phone)
values
  ('11111111-1111-1111-1111-111111111111', '0300-0000002'),  -- requester A's phone
  ('22222222-2222-2222-2222-222222222222', '0300-0000003'),  -- requester B's phone
  ('33333333-3333-3333-3333-333333333333', '0300-0000001'),  -- donor A's phone
  ('44444444-4444-4444-4444-444444444444', '0300-0000004');  -- donor B's phone

insert into requests (patient_name, hospital, blood_group, units_needed, urgency, contact, status, requester_id, city)
values
  ('CI Patient A', 'CI Hospital', 'B+', 1, 'Normal', 'ci', 'Pending',
   '11111111-1111-1111-1111-111111111111', 'Cividale'),
  ('CI Patient B', 'CI Hospital', 'O+', 1, 'Normal', 'ci', 'Pending',
   '22222222-2222-2222-2222-222222222222', 'Cividale');

insert into offers (request_id, donor_id, donor_auth_id, donor_name, donor_group, donor_city, status)
values
  ((select id from requests where patient_name = 'CI Patient A'),
   (select id from donors where name = 'CI Donor One'),
   '33333333-3333-3333-3333-333333333333', 'CI Donor One', 'B+', 'Cividale', 'Offered'),
  ((select id from requests where patient_name = 'CI Patient B'),
   (select id from donors where name = 'CI Donor Two'),
   '44444444-4444-4444-4444-444444444444', 'CI Donor Two', 'O+', 'Cividale', 'Offered');

-- As requester A: own contact row visible (sanity), NOBODY else's phone yet —
-- the offer is only 'Offered'.
set local role authenticated;
set local "request.jwt.claims" = '{"sub":"11111111-1111-1111-1111-111111111111","app_metadata":{"role":"DONOR"}}';
do $runner$ begin
  if (select count(*) from contact_details where user_id = '11111111-1111-1111-1111-111111111111') <> 1 then
    raise exception 'CI-TEST FAIL: a user lost read access to their own contact details';
  end if;
  if (select count(*) from contact_details where user_id = '33333333-3333-3333-3333-333333333333') <> 0 then
    raise exception 'CI-TEST FAIL: requester saw the donor''s phone while the offer was still OFFERED (must stay hidden until Accepted)';
  end if;
end $runner$;

-- As requester B: same rule for the other pair.
set local "request.jwt.claims" = '{"sub":"22222222-2222-2222-2222-222222222222","app_metadata":{"role":"DONOR"}}';
do $runner$ begin
  if (select count(*) from contact_details where user_id = '44444444-4444-4444-4444-444444444444') <> 0 then
    raise exception 'CI-TEST FAIL: unrelated requester saw a donor''s phone before any acceptance';
  end if;
  if (select count(*) from contact_details where user_id = '22222222-2222-2222-2222-222222222222') <> 1 then
    raise exception 'CI-TEST FAIL: user cannot read their own contact details';
  end if;
end $runner$;

-- Requester A accepts the offer on their own request (this must be allowed,
-- or nothing in the app could ever progress). Back to requester A's identity
-- first — the sanity block above ended as requester B.
set local "request.jwt.claims" = '{"sub":"11111111-1111-1111-1111-111111111111","app_metadata":{"role":"DONOR"}}';
do $runner$ declare v_rows int; begin
  update offers set status = 'Accepted'
   where request_id in (select id from requests where requester_id = '11111111-1111-1111-1111-111111111111');
  get diagnostics v_rows = row_count;
  if v_rows <> 1 then
    raise exception 'CI-TEST FAIL: requester could not accept the offer on their own request (rows=%)', v_rows;
  end if;
end $runner$;

-- After an acceptance, exactly ONE new row becomes readable per pair.
do $runner$ begin
  if (select count(*) from contact_details where user_id = '33333333-3333-3333-3333-333333333333') <> 1 then
    raise exception 'CI-TEST FAIL: requester cannot read the donor''s contact details AFTER accepting the offer';
  end if;
  if (select count(*) from contact_details where user_id = '44444444-4444-4444-4444-444444444444') <> 0 then
    raise exception 'CI-TEST FAIL: acceptance leaked UNRELATED contacts';
  end if;
end $runner$;

-- Requester B accepts the offer on their request too; donor B (whose offer
-- it is) must then see requester B's phone — the DONOR side of the same
-- rule. Before that acceptance it must be hidden from them as well.
set local "request.jwt.claims" = '{"sub":"44444444-4444-4444-4444-444444444444","app_metadata":{"role":"DONOR"}}';
do $runner$ begin
  if (select count(*) from contact_details where user_id = '22222222-2222-2222-2222-222222222222') <> 0 then
    raise exception 'CI-TEST FAIL: donor saw the requester''s phone before THEIR offer was accepted';
  end if;
end $runner$;
set local "request.jwt.claims" = '{"sub":"22222222-2222-2222-2222-222222222222","app_metadata":{"role":"DONOR"}}';
do $runner$ begin
  update offers set status = 'Accepted'
   where request_id in (select id from requests where requester_id = '22222222-2222-2222-2222-222222222222');
end $runner$;
set local "request.jwt.claims" = '{"sub":"44444444-4444-4444-4444-444444444444","app_metadata":{"role":"DONOR"}}';
do $runner$ begin
  if (select count(*) from contact_details where user_id = '22222222-2222-2222-2222-222222222222') <> 1 then
    raise exception 'CI-TEST FAIL: donor cannot read the requester''s contact details AFTER their offer was accepted';
  end if;
  if (select count(*) from contact_details) <> 2 then
    raise exception 'CI-TEST FAIL: donor B''s visible contacts exceeded their own pair (saw % rows)', (select count(*) from contact_details);
  end if;
end $runner$;

-- Donor A's view must stay their own pair only: own phone + requester A.
set local "request.jwt.claims" = '{"sub":"33333333-3333-3333-3333-333333333333","app_metadata":{"role":"DONOR"}}';
do $runner$ begin
  if (select count(*) from contact_details) <> 2 then
    raise exception 'CI-TEST FAIL: donor A''s visible contacts exceeded their own pair (saw % rows)', (select count(*) from contact_details);
  end if;
  if (select count(*) from contact_details where user_id = '44444444-4444-4444-4444-444444444444') <> 0 then
    raise exception 'CI-TEST FAIL: donor A saw donor B''s phone';
  end if;
  if (select count(*) from contact_details where user_id = '22222222-2222-2222-2222-222222222222') <> 0 then
    raise exception 'CI-TEST FAIL: donor A saw someone else''s requester phone';
  end if;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T2. The 3-open-requests cap: the 4th insert must be refused with the
--     cap message; exactly three may exist first.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values ('11111111-1111-1111-1111-111111111111', 'ci.requester1@example.test', '{}')
on conflict (id) do nothing;
set local role authenticated;
set local "request.jwt.claims" = '{"sub":"11111111-1111-1111-1111-111111111111","app_metadata":{"role":"DONOR"}}';

do $runner$ begin
  insert into requests (patient_name, hospital, blood_group, units_needed, urgency, contact, requester_id, city)
  values
    ('CI Cap 1', 'H', 'B+', 1, 'Normal', 'ci', '11111111-1111-1111-1111-111111111111', 'X'),
    ('CI Cap 2', 'H', 'B+', 1, 'Normal', 'ci', '11111111-1111-1111-1111-111111111111', 'X'),
    ('CI Cap 3', 'H', 'B+', 1, 'Normal', 'ci', '11111111-1111-1111-1111-111111111111', 'X');
  if (select count(*) from requests where requester_id = '11111111-1111-1111-1111-111111111111') <> 3 then
    raise exception 'CI-TEST FAIL: a user could not insert three open requests';
  end if;

  begin
    insert into requests (patient_name, hospital, blood_group, units_needed, urgency, contact, requester_id, city)
    values ('CI Cap 4', 'H', 'B+', 1, 'Normal', 'ci', '11111111-1111-1111-1111-111111111111', 'X');
    raise exception 'CI-TEST FAIL: a 4th open request was accepted (limit is 3)';
  exception
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      if sqlerrm not like '%Open request limit reached%' then
        raise exception 'CI-TEST FAIL: 4th request failed with the wrong error: %', sqlerrm;
      end if;
  end;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T3. Another user's request is untouchable. Cancelling is an UPDATE; hard
--     DELETE is manager-only — even the OWNER cannot erase their own.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values
  ('11111111-1111-1111-1111-111111111111', 'ci.requester1@example.test', '{}'),
  ('22222222-2222-2222-2222-222222222222', 'ci.requester2@example.test', '{}'),
  ('33333333-3333-3333-3333-333333333333', 'ci.donor1@example.test',    '{}')
on conflict (id) do nothing;
insert into requests (patient_name, hospital, blood_group, units_needed, urgency, contact, status, requester_id, city)
values
  ('CI Patient A', 'H', 'B+', 1, 'Normal', 'ci', 'Pending',
   '11111111-1111-1111-1111-111111111111', 'X'),
  ('CI Patient B', 'H', 'O+', 1, 'Normal', 'ci', 'Pending',
   '22222222-2222-2222-2222-222222222222', 'X');

-- Donor A attacks requester A's request.
set local role authenticated;
set local "request.jwt.claims" = '{"sub":"33333333-3333-3333-3333-333333333333","app_metadata":{"role":"DONOR"}}';
do $runner$ declare v_rows int; begin
  update requests set status = 'Cancelled' where patient_name = 'CI Patient A';
  get diagnostics v_rows = row_count;
  if v_rows <> 0 then
    raise exception 'CI-TEST FAIL: a user changed someone else''s request (% row(s))', v_rows;
  end if;
  delete from requests where patient_name = 'CI Patient A';
  get diagnostics v_rows = row_count;
  if v_rows <> 0 then
    raise exception 'CI-TEST FAIL: a user deleted someone else''s request (% row(s))', v_rows;
  end if;
end $runner$;

-- Requester A cannot hard-delete their OWN request either (matches the
-- policies: cancel by update, no personal erasure).
set local "request.jwt.claims" = '{"sub":"11111111-1111-1111-1111-111111111111","app_metadata":{"role":"DONOR"}}';
do $runner$ declare v_rows int; begin
  delete from requests where patient_name = 'CI Patient A';
  get diagnostics v_rows = row_count;
  if v_rows <> 0 then
    raise exception 'CI-TEST FAIL: request hard-delete is not manager-only (% row(s))', v_rows;
  end if;
  -- They CAN update their own (the cancel path).
  update requests set status = 'Cancelled' where patient_name = 'CI Patient A';
  get diagnostics v_rows = row_count;
  if v_rows <> 1 then
    raise exception 'CI-TEST FAIL: a user cannot cancel their OWN request (% rows)', v_rows;
  end if;
  -- But not someone else's.
  update requests set status = 'Cancelled' where patient_name = 'CI Patient B';
  get diagnostics v_rows = row_count;
  if v_rows <> 0 then
    raise exception 'CI-TEST FAIL: a user changed someone else''s request (% row(s))', v_rows;
  end if;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T4. Another user's offer is untouchable; the offer table has NO delete
--     policy at all, so even the donor who wrote it cannot erase it.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values
  ('11111111-1111-1111-1111-111111111111', 'ci.requester1@example.test', '{}'),
  ('22222222-2222-2222-2222-222222222222', 'ci.requester2@example.test', '{}'),
  ('33333333-3333-3333-3333-333333333333', 'ci.donor1@example.test',    '{}'),
  ('44444444-4444-4444-4444-444444444444', 'ci.donor2@example.test',    '{}')
on conflict (id) do nothing;
insert into donors (name, age, gender, blood_group, phone, city, weight, auth_id, available_to_donate)
values
  ('CI Donor One', 25, 'Male', 'B+', '1', 'X', 70, '33333333-3333-3333-3333-333333333333', true),
  ('CI Donor Two', 27, 'Male', 'O+', '2', 'X', 72, '44444444-4444-4444-4444-444444444444', true);
insert into requests (patient_name, hospital, blood_group, units_needed, urgency, contact, status, requester_id, city)
values
  ('CI Patient A', 'H', 'B+', 1, 'Normal', 'ci', 'Pending', '11111111-1111-1111-1111-111111111111', 'X'),
  ('CI Patient B', 'H', 'O+', 1, 'Normal', 'ci', 'Pending', '22222222-2222-2222-2222-222222222222', 'X');
insert into offers (request_id, donor_id, donor_auth_id, donor_name, status)
values
  ((select id from requests where patient_name = 'CI Patient A'), (select id from donors where name = 'CI Donor One'), '33333333-3333-3333-3333-333333333333', 'CI Donor One', 'Offered'),
  ((select id from requests where patient_name = 'CI Patient B'), (select id from donors where name = 'CI Donor Two'), '44444444-4444-4444-4444-444444444444', 'CI Donor Two', 'Offered');

-- Donor A attacks donor B's offer (on requester B's request).
set local role authenticated;
set local "request.jwt.claims" = '{"sub":"33333333-3333-3333-3333-333333333333","app_metadata":{"role":"DONOR"}}';
do $runner$ declare v_rows int; begin
  update offers set status = 'Accepted' where donor_name = 'CI Donor Two' or request_id in (select id from requests where requester_id = '22222222-2222-2222-2222-222222222222');
  get diagnostics v_rows = row_count;
  if v_rows <> 0 then
    raise exception 'CI-TEST FAIL: a donor changed someone else''s offer (% row(s))', v_rows;
  end if;
  delete from offers where donor_name = 'CI Donor Two';
  get diagnostics v_rows = row_count;
  if v_rows <> 0 then
    raise exception 'CI-TEST FAIL: offer rows are deletable by users (% row(s)) — there must be NO delete policy on offers', v_rows;
  end if;
end $runner$;

-- Requester A attacks donor B's offer (an offer on somebody else's request).
set local "request.jwt.claims" = '{"sub":"11111111-1111-1111-1111-111111111111","app_metadata":{"role":"DONOR"}}';
do $runner$ declare v_rows int; begin
  update offers set status = 'Accepted' where request_id in (select id from requests where requester_id = '22222222-2222-2222-2222-222222222222');
  get diagnostics v_rows = row_count;
  if v_rows <> 0 then
    raise exception 'CI-TEST FAIL: a requester changed an offer on someone else''s request (% row(s))', v_rows;
  end if;
end $runner$;

-- Sanity: requester A CAN still drive their own pair (donor A's offer).
do $runner$ declare v_rows int; begin
  update offers set status = 'Accepted' where donor_name = 'CI Donor One';
  get diagnostics v_rows = row_count;
  if v_rows <> 1 then
    raise exception 'CI-TEST FAIL: the legitimate requester cannot accept the offer on their own request (% rows)', v_rows;
  end if;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T5. Availability is personal: a user can flip ONLY their own switch, and
--     cannot hijack another profile or its auth_id.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values
  ('33333333-3333-3333-3333-333333333333', 'ci.donor1@example.test', '{}'),
  ('44444444-4444-4444-4444-444444444444', 'ci.donor2@example.test', '{}')
on conflict (id) do nothing;
insert into donors (name, age, gender, blood_group, phone, city, weight, auth_id, available_to_donate)
values
  ('CI Donor One', 25, 'Male', 'B+', '1', 'X', 70, '33333333-3333-3333-3333-333333333333', false),
  ('CI Donor Two', 27, 'Male', 'O+', '2', 'X', 72, '44444444-4444-4444-4444-444444444444', false);

set local role authenticated;
set local "request.jwt.claims" = '{"sub":"44444444-4444-4444-4444-444444444444","app_metadata":{"role":"DONOR"}}';
do $runner$ declare v_rows int; begin
  update donors set available_to_donate = true where auth_id = '33333333-3333-3333-3333-333333333333';
  get diagnostics v_rows = row_count;
  if v_rows <> 0 then
    raise exception 'CI-TEST FAIL: a user set ANOTHER donor''s availability (% row(s))', v_rows;
  end if;
  update donors set available_to_donate = true where auth_id = '44444444-4444-4444-4444-444444444444';
  get diagnostics v_rows = row_count;
  if v_rows <> 1 then
    raise exception 'CI-TEST FAIL: a donor cannot flip their OWN availability switch (% rows)', v_rows;
  end if;
end $runner$;

-- Stealing the row (moving auth_id to another account) must be refused too.
-- The refusal must be an RLS denial (42501), not some unrelated error.
do $runner$ begin
  begin
    update donors set auth_id = '33333333-3333-3333-3333-333333333333'
     where auth_id = '44444444-4444-4444-4444-444444444444';
    raise exception 'CI-TEST FAIL: a donor moved their profile to another auth_id';
  exception
    when insufficient_privilege then
      null;  -- expected: the WITH CHECK clause refused the new auth_id
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      raise exception 'CI-TEST FAIL: the auth_id takeover was refused with SQLSTATE % (%), not an RLS denial (42501)', sqlstate, sqlerrm;
  end;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T6. The manager sees and changes everything. Also files/reports.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values
  ('11111111-1111-1111-1111-111111111111', 'ci.requester1@example.test', '{}'),
  ('55555555-5555-5555-5555-555555555555', 'manager@blood.local', '{"role":"MANAGER"}')
on conflict (id) do nothing;
insert into donors (name, age, gender, blood_group, phone, city, weight, auth_id)
values ('CI Donor One', 25, 'Male', 'B+', '1', 'X', 70, '33333333-3333-3333-3333-333333333333');
insert into contact_details (user_id, phone)
values ('33333333-3333-3333-3333-333333333333', '0300-0000001');
insert into requests (patient_name, hospital, blood_group, units_needed, urgency, contact, status, requester_id, city)
values ('CI Patient A', 'H', 'B+', 1, 'Normal', 'ci', 'Pending',
        '11111111-1111-1111-1111-111111111111', 'X');
insert into offers (request_id, donor_id, donor_auth_id, status)
values ((select id from requests where patient_name = 'CI Patient A'), (select id from donors where name = 'CI Donor One'), '33333333-3333-3333-3333-333333333333', 'Accepted');

-- Requester A files a report.
set local role authenticated;
set local "request.jwt.claims" = '{"sub":"11111111-1111-1111-1111-111111111111","app_metadata":{"role":"DONOR"}}';
do $runner$ begin
  insert into reports (request_id, reporter_id, reason)
  values ((select id from requests where patient_name = 'CI Patient A'),
          '11111111-1111-1111-1111-111111111111', 'CI spam test');
end $runner$;

-- The manager: can read everything they just hid from users...
set local "request.jwt.claims" = '{"sub":"55555555-5555-5555-5555-555555555555","app_metadata":{"role":"MANAGER"}}';
do $runner$ begin
  if (select count(*) from contact_details) < 1 then
    raise exception 'CI-TEST FAIL: manager cannot read contact_details';
  end if;
  if (select count(*) from offers) < 1 then
    raise exception 'CI-TEST FAIL: manager cannot read offers';
  end if;
  if (select count(*) from reports where reason = 'CI spam test') <> 1 then
    raise exception 'CI-TEST FAIL: manager cannot read user reports';
  end if;
end $runner$;

-- ...and can change everything: strangers' requests, donor rows, hard delete.
do $runner$ declare v_rows int; begin
  update requests set status = 'Cancelled' where patient_name = 'CI Patient A';
  get diagnostics v_rows = row_count;
  if v_rows <> 1 then raise exception 'CI-TEST FAIL: manager cannot moderate a request (% rows)', v_rows; end if;

  delete from requests where patient_name = 'CI Patient A';
  get diagnostics v_rows = row_count;
  if v_rows <> 1 then raise exception 'CI-TEST FAIL: manager cannot hard-delete a request (% rows)', v_rows; end if;

  update donors set weight = 71 where name = 'CI Donor One';
  get diagnostics v_rows = row_count;
  if v_rows <> 1 then raise exception 'CI-TEST FAIL: manager cannot edit a donor row (% rows)', v_rows; end if;

  insert into donors (name, age, gender, blood_group, phone, city, weight)
  values ('CI Manager Seed', 30, 'Male', 'O-', '3', 'X', 75);
  delete from donors where name = 'CI Manager Seed';

  update stock set units = 3 where blood_group = 'O-';
  get diagnostics v_rows = row_count;
  if v_rows <> 1 then raise exception 'CI-TEST FAIL: manager cannot adjust stock (% rows)', v_rows; end if;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T7. Anon (the key inside every APK): nothing, anywhere, by any means.
-- ---------------------------------------------------------------------------
begin;
insert into donors (name, age, gender, blood_group, phone, city, weight, last_donation_date)
values ('CI Anon Probe', 25, 'Male', 'B+', '1', 'X', 70, null);
insert into contact_details (user_id, phone)
values ('33333333-3333-3333-3333-333333333333', '0300-0000001');

set local role anon;
set local "request.jwt.claims" = '';
do $runner$ begin
  if (select count(*) from donors) <> 0
    or (select count(*) from requests) <> 0
    or (select count(*) from offers) <> 0
    or (select count(*) from contact_details) <> 0
    or (select count(*) from device_tokens) <> 0
    or (select count(*) from reports) <> 0
    or (select count(*) from ai_coach_usage) <> 0 then
    raise exception 'CI-TEST FAIL: the anon role read rows from at least one table';
  end if;
end $runner$;

-- And even a direct write must be refused (RLS ON, no anon policies).
do $runner$ begin
  begin
    insert into donors (name, age, gender, blood_group, phone, city, weight)
    values ('CI Anon Write', 30, 'Male', 'O-', '9', 'X', 70);
    raise exception 'CI-TEST FAIL: the anon role was able to INSERT into donors';
  exception
    when insufficient_privilege then
      null;  -- expected: RLS denied the write
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      raise exception 'CI-TEST FAIL: the anon insert was refused with SQLSTATE % (%), not an RLS denial (42501)', sqlstate, sqlerrm;
  end;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T8. device_tokens: a user owns exactly their own tokens.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values
  ('33333333-3333-3333-3333-333333333333', 'ci.donor1@example.test', '{}'),
  ('44444444-4444-4444-4444-444444444444', 'ci.donor2@example.test', '{}')
on conflict (id) do nothing;

set local role authenticated;
set local "request.jwt.claims" = '{"sub":"33333333-3333-3333-3333-333333333333","app_metadata":{"role":"DONOR"}}';
do $runner$ begin
  insert into device_tokens (user_id, token)
  values ('33333333-3333-3333-3333-333333333333', 'ci-token-a');
end $runner$;

set local "request.jwt.claims" = '{"sub":"44444444-4444-4444-4444-444444444444","app_metadata":{"role":"DONOR"}}';
do $runner$ declare v_rows int; begin
  insert into device_tokens (user_id, token)
  values ('44444444-4444-4444-4444-444444444444', 'ci-token-b');

  if (select count(*) from device_tokens where user_id = '33333333-3333-3333-3333-333333333333') <> 0 then
    raise exception 'CI-TEST FAIL: a user read someone else''s FCM tokens (push impersonation possible)';
  end if;

  insert into device_tokens (user_id, token)
  values ('33333333-3333-3333-3333-333333333333', 'ci-token-c');
  raise exception 'CI-TEST FAIL: a user registered a token FOR someone else';

exception
  when insufficient_privilege then
    null;  -- expected: the insert for another user_id was refused by RLS
  when others then
    if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
    raise exception 'CI-TEST FAIL: the foreign-token insert was refused with SQLSTATE % (%), not an RLS denial (42501)', sqlstate, sqlerrm;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T9. ai_coach_usage: RLS ON with NO policies — a user cannot read or
--     inflate their own quota; only the Edge Function (bypassrls) can.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values ('11111111-1111-1111-1111-111111111111', 'ci.requester1@example.test', '{}')
on conflict (id) do nothing;

set local role authenticated;
set local "request.jwt.claims" = '{"sub":"11111111-1111-1111-1111-111111111111","app_metadata":{"role":"DONOR"}}';
do $runner$ begin
  if (select count(*) from ai_coach_usage) <> 0 then
    raise exception 'CI-TEST FAIL: a user can read the ai_coach_usage rate-limit table';
  end if;
  begin
    insert into ai_coach_usage (user_id, calls) values ('11111111-1111-1111-1111-111111111111', 999999);
    raise exception 'CI-TEST FAIL: a user can write their own ai_coach_usage quota';
  exception
    when insufficient_privilege then
      null;  -- expected: RLS is ON with no policies
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      raise exception 'CI-TEST FAIL: the quota insert was refused with SQLSTATE % (%), not an RLS denial (42501)', sqlstate, sqlerrm;
  end;
end $runner$;

-- Service role = what the Edge Function runs with: full control.
set local role service_role;
set local "request.jwt.claims" = '{"role":"service_role"}';
do $runner$ begin
  insert into ai_coach_usage (user_id, calls) values ('11111111-1111-1111-1111-111111111111', 1);
  if (select calls from ai_coach_usage where user_id = '11111111-1111-1111-1111-111111111111') <> 1 then
    raise exception 'CI-TEST FAIL: service_role cannot manage the ai_coach_usage table';
  end if;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T10. complete_peer_donation: one call records ONE donation, completes the
--      offer, fulfils the request, and sets the donor's last donation date —
--      in a single transaction, from the DONOR's identity.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values
  ('11111111-1111-1111-1111-111111111111', 'ci.requester1@example.test', '{}'),
  ('33333333-3333-3333-3333-333333333333', 'ci.donor1@example.test',    '{}')
on conflict (id) do nothing;
insert into donors (name, age, gender, blood_group, phone, city, weight, auth_id, available_to_donate)
values ('CI Donor One', 25, 'Male', 'B+', '0300-0000001', 'X', 70, '33333333-3333-3333-3333-333333333333', true);
insert into requests (patient_name, hospital, blood_group, units_needed, urgency, contact, status, requester_id, city)
values ('CI Patient A', 'H', 'B+', 1, 'Normal', 'ci', 'Pending', '11111111-1111-1111-1111-111111111111', 'X');
insert into offers (request_id, donor_id, donor_auth_id, donor_name, status)
values ((select id from requests where patient_name = 'CI Patient A'),
        (select id from donors where name = 'CI Donor One'), '33333333-3333-3333-3333-333333333333',
        'CI Donor One', 'Completed');  -- Completed without both confirmations must be refused
insert into stock (blood_group, units) values ('B+', 0) on conflict (blood_group) do nothing;

set local role authenticated;
set local "request.jwt.claims" = '{"sub":"33333333-3333-3333-3333-333333333333","app_metadata":{"role":"DONOR"}}';
do $runner$ declare v_done boolean; begin
  -- Not accepted, no confirmations: refused, records nothing.
  v_done := public.complete_peer_donation((select id from offers where donor_name = 'CI Donor One'));
  if v_done then
    raise exception 'CI-TEST FAIL: complete_peer_donation ran without an Accepted offer and both confirmations';
  end if;
  if (select count(*) from donations) <> 0 then
    raise exception 'CI-TEST FAIL: complete_peer_donation recorded a donation despite refusing';
  end if;
end $runner$;

-- Both sides confirm (timestamps), and the requester accepts.
do $runner$ begin
  update offers set status = 'Accepted' where donor_name = 'CI Donor One';
  update offers set donor_confirmed_at = now() where donor_name = 'CI Donor One';
end $runner$;
set local "request.jwt.claims" = '{"sub":"11111111-1111-1111-1111-111111111111","app_metadata":{"role":"DONOR"}}';
do $runner$ begin
  update offers set requester_confirmed_at = now() where donor_name = 'CI Donor One';
end $runner$;
set local "request.jwt.claims" = '{"sub":"33333333-3333-3333-3333-333333333333","app_metadata":{"role":"DONOR"}}';
do $runner$ declare v_done boolean; begin
  v_done := public.complete_peer_donation((select id from offers where donor_name = 'CI Donor One'));
  if not v_done then
    raise exception 'CI-TEST FAIL: complete_peer_donation refused a fully-confirmed accepted offer';
  end if;
  if (select count(*) from donations where donor_id = (select id from donors where name = 'CI Donor One')) <> 1 then
    raise exception 'CI-TEST FAIL: peer donation was not recorded exactly once';
  end if;
  if (select status from offers where donor_name = 'CI Donor One') <> 'Completed' then
    raise exception 'CI-TEST FAIL: the offer was not completed by the RPC';
  end if;
  if (select status from requests where patient_name = 'CI Patient A') <> 'Fulfilled' then
    raise exception 'CI-TEST FAIL: the request was not fulfilled by the peer RPC';
  end if;
  if (select last_donation_date from donors where name = 'CI Donor One') is null then
    raise exception 'CI-TEST FAIL: peer donation did not set the donor''s last donation date';
  end if;
  if (select units from stock where blood_group = 'B+') <> 0 then
    raise exception 'CI-TEST FAIL: peer donation must NOT change blood-bank stock';
  end if;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T11. Stock is the blood bank's inventory: a signed-in donor cannot write it,
--      and cannot call adjust_stock() either — the guard is inside the
--      function, because managers are the `authenticated` role too. The
--      manager can do both.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values
  ('33333333-3333-3333-3333-333333333333', 'ci.donor1@example.test', '{}'),
  ('55555555-5555-5555-5555-555555555555', 'manager@blood.local',    '{"role":"MANAGER"}')
on conflict (id) do nothing;
insert into donors (name, age, gender, blood_group, phone, city, weight, auth_id, available_to_donate)
values ('CI Donor One', 25, 'Male', 'B+', '1', 'X', 70, '33333333-3333-3333-3333-333333333333', true);
update stock set units = 5 where blood_group = 'B+';

set local role authenticated;
set local "request.jwt.claims" = '{"sub":"33333333-3333-3333-3333-333333333333","app_metadata":{"role":"DONOR"}}';

do $runner$ begin
  begin
    perform public.adjust_stock('B+', 1);
    raise exception 'CI-TEST FAIL: a donor moved blood-bank stock through adjust_stock()';
  exception
    when insufficient_privilege then null;   -- expected: manager-only
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      raise exception 'CI-TEST FAIL: adjust_stock refused a donor with SQLSTATE % (%), not 42501', sqlstate, sqlerrm;
  end;
  if (select units from stock where blood_group = 'B+') <> 5 then
    raise exception 'CI-TEST FAIL: a refused adjust_stock still changed the stock';
  end if;
end $runner$;

do $runner$ declare v_rows int; begin
  -- A denied UPDATE is SILENT (0 rows, no error), so assert the row count
  -- instead of waiting for an exception.
  update stock set units = 1000 where blood_group = 'B+';
  get diagnostics v_rows = row_count;
  if v_rows <> 0 then
    raise exception 'CI-TEST FAIL: a donor wrote the stock table directly (% row(s))', v_rows;
  end if;
end $runner$;

-- The manager may do both.
set local "request.jwt.claims" = '{"sub":"55555555-5555-5555-5555-555555555555","app_metadata":{"role":"MANAGER"}}';
do $runner$ begin
  perform public.adjust_stock('B+', 1);
  if (select units from stock where blood_group = 'B+') <> 6 then
    raise exception 'CI-TEST FAIL: the manager could not move stock through adjust_stock()';
  end if;
  -- Unknown groups are still rejected with the RPC's own 22023.
  begin
    perform public.adjust_stock('ZZ', 1);
    raise exception 'CI-TEST FAIL: adjust_stock accepted an unknown blood group';
  exception
    when invalid_parameter_value then null;   -- expected
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      raise exception 'CI-TEST FAIL: adjust_stock rejected an unknown group with SQLSTATE % (%), not 22023', sqlstate, sqlerrm;
  end;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T12. A user whose role claim exists ONLY in user_metadata is a normal
--      user. GoTrue copies auth.users.raw_user_meta_data into the JWT's
--      `user_metadata` field; is_manager() must read ONLY
--      auth.jwt() -> 'app_metadata' ->> 'role', so such a JWT must give
--      no manager power anywhere.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_user_meta_data, raw_app_meta_data)
values
  -- the fake manager: MANAGER in user_metadata, NOTHING in app_metadata
  ('66666666-6666-6666-6666-666666666666', 'ci.fakemanager@example.test',
   '{"role":"MANAGER"}', '{}'),
  -- an ordinary requester whose request they must not touch
  ('11111111-1111-1111-1111-111111111111', 'ci.requester1@example.test', '{}', '{}')
on conflict (id) do nothing;
insert into donors (name, age, gender, blood_group, phone, city, weight, auth_id)
values ('CI Donor One', 25, 'Male', 'B+', '0300-0000001', 'X', 70,
        '33333333-3333-3333-3333-333333333333');
insert into contact_details (user_id, phone)
values ('33333333-3333-3333-3333-333333333333', '0300-0000001');
insert into requests (patient_name, hospital, blood_group, units_needed, urgency, contact, status, requester_id, city)
values ('CI Patient A', 'H', 'B+', 1, 'Normal', 'ci', 'Pending',
        '11111111-1111-1111-1111-111111111111', 'X');

set local role authenticated;
set local "request.jwt.claims" =
  '{"sub":"66666666-6666-6666-6666-666666666666","app_metadata":{},"user_metadata":{"role":"MANAGER"}}';
do $runner$ begin
  -- the helper itself must say no, so no policy can ever disagree
  if public.is_manager() then
    raise exception 'CI-TEST FAIL: is_manager() treated a user_metadata-only role as MANAGER';
  end if;
end $runner$;
do $runner$ begin
  -- manager-only INSERT on donors. The refusal must be an RLS denial
  -- (SQLSTATE 42501) — any other error (e.g. a constraint violation) would
  -- make this test pass for the wrong reason.
  insert into donors (name, age, gender, blood_group, phone, city, weight)
  values ('CI Fake Manager Seed', 30, 'Male', 'O-', '2', 'X', 75);
  raise exception 'CI-TEST FAIL: a user_metadata-only manager inserted into donors';
exception
  when insufficient_privilege then
    null;  -- expected: RLS denied the insert
  when others then
    if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
    raise exception 'CI-TEST FAIL: the donors insert was refused with SQLSTATE % (%), not an RLS denial (42501)', sqlstate, sqlerrm;
end $runner$;
do $runner$ declare v_rows int; begin
  -- deny-by-silence checks: UPDATE/DELETE another user's request = 0 rows
  update requests set status = 'Cancelled' where patient_name = 'CI Patient A';
  get diagnostics v_rows = row_count;
  if v_rows <> 0 then
    raise exception 'CI-TEST FAIL: a user_metadata-only manager cancelled someone else''s request (% row(s))', v_rows;
  end if;
  delete from requests where patient_name = 'CI Patient A';
  get diagnostics v_rows = row_count;
  if v_rows <> 0 then
    raise exception 'CI-TEST FAIL: a user_metadata-only manager deleted a request (% row(s))', v_rows;
  end if;
end $runner$;

-- visibility stays personal: own row only, others' phones unreachable
do $runner$ begin
  insert into contact_details (user_id, phone)
  values ('66666666-6666-6666-6666-666666666666', '0300-0000006');
  if (select count(*) from contact_details
       where user_id <> '66666666-6666-6666-6666-666666666666') <> 0 then
    raise exception 'CI-TEST FAIL: a user_metadata-only manager read other users'' contact details';
  end if;
end $runner$;

-- device_tokens: the manager-read-all policy must not apply either
do $runner$ begin
  insert into device_tokens (user_id, token)
  values ('66666666-6666-6666-6666-666666666666', 'ci-token-fake-mgr');
  if (select count(*) from device_tokens
       where user_id <> '66666666-6666-6666-6666-666666666666') <> 0 then
    raise exception 'CI-TEST FAIL: a user_metadata-only manager read other users'' FCM tokens';
  end if;
end $runner$;

-- Control: the SAME checks with the claim in the RIGHT place must show the
-- manager powers exist (so the negatives above mean policy, not schema,
-- rejection). This deliberately stays INSIDE T12's transaction: `set local
-- role authenticated` is transaction-scoped, so a genuinely fresh transaction
-- here would run as the CI superuser, bypass RLS, and make the delete below
-- succeed no matter what the policies say — the control would prove nothing.
-- A savepoint undoes the control's writes while keeping role + claim.
savepoint t12_control;
set local "request.jwt.claims" =
  '{"sub":"66666666-6666-6666-6666-666666666666","app_metadata":{"role":"MANAGER"},"user_metadata":{}}';
do $runner$ declare v_rows int; begin
  -- Guard: without this the control could pass vacuously if the role were
  -- ever reset (RLS is skipped for the table owner / superuser).
  if current_user <> 'authenticated' then
    raise exception 'CI-TEST FAIL: the control is running as "%", not "authenticated" — RLS is bypassed and the control proves nothing', current_user;
  end if;
  if not public.is_manager() then
    raise exception 'CI-TEST FAIL: is_manager() does not honour a genuine app_metadata MANAGER';
  end if;
  delete from requests where patient_name = 'CI Patient A';
  get diagnostics v_rows = row_count;
  if v_rows <> 1 then
    raise exception 'CI-TEST FAIL: a real manager lost hard-delete on requests (% rows)', v_rows;
  end if;
  if (select count(*) from contact_details
       where user_id = '33333333-3333-3333-3333-333333333333') <> 1 then
    raise exception 'CI-TEST FAIL: a real manager lost contact_details access';
  end if;
end $runner$;
rollback to savepoint t12_control;

rollback;

-- ---------------------------------------------------------------------------
-- T13. A donor may create their OWN profile (the registration path) and
--      nothing else: not a profile for another auth_id, and not one with no
--      auth_id at all. T12 covers the third case — a user whose MANAGER claim
--      is only in user_metadata — and still holds, because that insert carries
--      no auth_id.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values
  ('11111111-1111-1111-1111-111111111111', 'ci.requester1@example.test', '{}'),
  ('22222222-2222-2222-2222-222222222222', 'ci.requester2@example.test', '{}')
on conflict (id) do nothing;

set local role authenticated;
set local "request.jwt.claims" =
  '{"sub":"11111111-1111-1111-1111-111111111111","app_metadata":{"role":"DONOR"}}';

-- own auth_id: allowed — this is what RegisterActivity does
do $runner$ begin
  insert into donors (name, age, gender, blood_group, phone, city, weight, auth_id)
  values ('CI Self Registered', 30, 'Male', 'A+', '0300-0000011', 'X', 70,
          '11111111-1111-1111-1111-111111111111');
  if (select count(*) from donors where name = 'CI Self Registered') <> 1 then
    raise exception 'CI-TEST FAIL: a donor could not create their own profile';
  end if;
end $runner$;

-- somebody else's auth_id: refused with an RLS denial, not something else
do $runner$ begin
  begin
    insert into donors (name, age, gender, blood_group, phone, city, weight, auth_id)
    values ('CI Stolen Profile', 30, 'Male', 'A+', '0300-0000012', 'X', 70,
            '22222222-2222-2222-2222-222222222222');
    raise exception 'CI-TEST FAIL: a user created a donor profile for someone else';
  exception
    when insufficient_privilege then null;   -- expected
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      raise exception 'CI-TEST FAIL: foreign-auth_id insert refused with SQLSTATE % (%), not 42501', sqlstate, sqlerrm;
  end;
end $runner$;

-- no auth_id at all: refused (NULL is not "their own id")
do $runner$ begin
  begin
    insert into donors (name, age, gender, blood_group, phone, city, weight)
    values ('CI No Auth Id', 30, 'Male', 'A+', '0300-0000013', 'X', 70);
    raise exception 'CI-TEST FAIL: a user created a donor row with no auth_id';
  exception
    when insufficient_privilege then null;   -- expected
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      raise exception 'CI-TEST FAIL: null-auth_id insert refused with SQLSTATE % (%), not 42501', sqlstate, sqlerrm;
  end;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T14. Phones are not a column anyone may read. A non-manager cannot select
--      donors.phone or requests.contact at all (42501, from the column
--      privileges in supabase_role_policies.sql), and the two SECURITY DEFINER
--      helpers return NULL for other people's numbers while still answering
--      for the owner and the manager.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values
  ('11111111-1111-1111-1111-111111111111', 'ci.requester1@example.test', '{}'),
  ('22222222-2222-2222-2222-222222222222', 'ci.requester2@example.test', '{}'),
  ('33333333-3333-3333-3333-333333333333', 'ci.donor1@example.test',     '{}'),
  ('55555555-5555-5555-5555-555555555555', 'manager@blood.local',        '{"role":"MANAGER"}')
on conflict (id) do nothing;
insert into donors (name, age, gender, blood_group, phone, city, weight, auth_id)
values ('CI Phone Donor', 30, 'Male', 'A+', '0300-1111111', 'Cividale', 70,
        '33333333-3333-3333-3333-333333333333');
insert into requests (patient_name, hospital, blood_group, units_needed, urgency, contact, status, requester_id, city)
values
  ('CI Phone Req Own',   'H', 'A+', 1, 'Normal', '0300-2222222', 'Pending',
   '11111111-1111-1111-1111-111111111111', 'Cividale'),
  ('CI Phone Req Other', 'H', 'A+', 1, 'Normal', '0300-3333333', 'Pending',
   '22222222-2222-2222-2222-222222222222', 'Cividale');

set local role authenticated;
set local "request.jwt.claims" =
  '{"sub":"11111111-1111-1111-1111-111111111111","app_metadata":{"role":"DONOR"}}';

-- the columns themselves are unreadable
do $runner$ begin
  begin
    perform 1 from donors where phone is not null;
    raise exception 'CI-TEST FAIL: a non-manager read donors.phone';
  exception
    when insufficient_privilege then null;   -- expected
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      raise exception 'CI-TEST FAIL: donors.phone refused with SQLSTATE % (%), not 42501', sqlstate, sqlerrm;
  end;
  begin
    perform 1 from requests where contact is not null;
    raise exception 'CI-TEST FAIL: a non-manager read requests.contact';
  exception
    when insufficient_privilege then null;   -- expected
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      raise exception 'CI-TEST FAIL: requests.contact refused with SQLSTATE % (%), not 42501', sqlstate, sqlerrm;
  end;
  -- the directory itself must still work
  if (select count(*) from donors where city = 'Cividale') < 1 then
    raise exception 'CI-TEST FAIL: the donor directory became unreadable';
  end if;
  if (select count(*) from requests) < 2 then
    raise exception 'CI-TEST FAIL: requests became unreadable';
  end if;
end $runner$;

-- the helpers answer for yourself, and stay silent about everyone else
do $runner$ declare v text; begin
  if public.donor_phone((select id from donors where name = 'CI Phone Donor')) is not null then
    raise exception 'CI-TEST FAIL: donor_phone() revealed another donor''s number';
  end if;
  if public.request_contact((select id from requests where patient_name = 'CI Phone Req Other')) is not null then
    raise exception 'CI-TEST FAIL: request_contact() revealed someone else''s contact number';
  end if;
  v := public.request_contact((select id from requests where patient_name = 'CI Phone Req Own'));
  if v is distinct from '0300-2222222' then
    raise exception 'CI-TEST FAIL: request_contact() withheld the requester''s own contact number (%)', coalesce(v, 'NULL');
  end if;
end $runner$;

-- the manager sees both
set local "request.jwt.claims" =
  '{"sub":"55555555-5555-5555-5555-555555555555","app_metadata":{"role":"MANAGER"}}';
do $runner$ begin
  if public.donor_phone((select id from donors where name = 'CI Phone Donor')) is distinct from '0300-1111111' then
    raise exception 'CI-TEST FAIL: the manager cannot read a donor phone';
  end if;
  if public.request_contact((select id from requests where patient_name = 'CI Phone Req Other')) is distinct from '0300-3333333' then
    raise exception 'CI-TEST FAIL: the manager cannot read a request contact number';
  end if;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T15. A donation is recorded by a manager, or by complete_peer_donation().
--      The donor's own client cannot write the table: that would let anyone
--      add donations to their own history and inflate their milestones.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values
  ('33333333-3333-3333-3333-333333333333', 'ci.donor1@example.test', '{}'),
  ('55555555-5555-5555-5555-555555555555', 'manager@blood.local',    '{"role":"MANAGER"}')
on conflict (id) do nothing;
insert into donors (name, age, gender, blood_group, phone, city, weight, auth_id)
values ('CI Donor One', 25, 'Male', 'B+', '1', 'X', 70, '33333333-3333-3333-3333-333333333333');

set local role authenticated;
set local "request.jwt.claims" = '{"sub":"33333333-3333-3333-3333-333333333333","app_metadata":{"role":"DONOR"}}';
do $runner$ begin
  begin
    insert into donations (donor_id, date, units)
    values ((select id from donors where name = 'CI Donor One'), current_date, 1);
    raise exception 'CI-TEST FAIL: a donor recorded a donation for themselves';
  exception
    when insufficient_privilege then null;   -- expected: manager-only
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      raise exception 'CI-TEST FAIL: donor donation insert refused with SQLSTATE % (%), not 42501', sqlstate, sqlerrm;
  end;
  if (select count(*) from donations) <> 0 then
    raise exception 'CI-TEST FAIL: a refused donation insert still wrote a row';
  end if;
end $runner$;

set local "request.jwt.claims" = '{"sub":"55555555-5555-5555-5555-555555555555","app_metadata":{"role":"MANAGER"}}';
do $runner$ begin
  insert into donations (donor_id, date, units)
  values ((select id from donors where name = 'CI Donor One'), current_date, 1);
  if (select count(*) from donations) <> 1 then
    raise exception 'CI-TEST FAIL: the manager could not record a donation';
  end if;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T16. delete_account_data(): the personal rows go, the blood bank's record
--      stays. A donor's own donation history must survive (anonymised), and
--      one user's deletion must never touch another user's rows.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values
  ('11111111-1111-1111-1111-111111111111', 'ci.requester1@example.test', '{}'),
  ('22222222-2222-2222-2222-222222222222', 'ci.requester2@example.test', '{}'),
  ('33333333-3333-3333-3333-333333333333', 'ci.donor1@example.test',     '{}')
on conflict (id) do nothing;

insert into donors (name, age, gender, blood_group, phone, city, weight, auth_id, available_to_donate)
values
  ('CI Leaver', 30, 'Male', 'A+', '0300-1111111', 'Leaverville', 70,
   '11111111-1111-1111-1111-111111111111', true),
  ('CI Stayer', 40, 'Female', 'B+', '0300-2222222', 'Stayville', 60,
   '22222222-2222-2222-2222-222222222222', true);

insert into donations (donor_id, date, units)
values ((select id from donors where name = 'CI Leaver'), current_date - 100, 1);

insert into requests (patient_name, hospital, blood_group, units_needed, urgency, contact, status, requester_id, city)
values
  ('CI Open',      'H', 'A+', 1, 'Normal', '0300-3333333', 'Pending',
   '11111111-1111-1111-1111-111111111111', 'Leaverville'),
  ('CI Fulfilled', 'H', 'A+', 1, 'Normal', '0300-4444444', 'Fulfilled',
   '11111111-1111-1111-1111-111111111111', 'Leaverville'),
  ('CI Other',     'H', 'B+', 1, 'Normal', '0300-5555555', 'Pending',
   '22222222-2222-2222-2222-222222222222', 'Stayville');

insert into offers (request_id, donor_id, donor_auth_id, donor_name, donor_group, donor_city, status)
values ((select id from requests where patient_name = 'CI Other'),
        (select id from donors where name = 'CI Leaver'),
        '11111111-1111-1111-1111-111111111111', 'CI', 'A+', 'Leaverville', 'Offered');

insert into contact_details (user_id, phone)
values ('11111111-1111-1111-1111-111111111111', '0300-1111111'),
       ('22222222-2222-2222-2222-222222222222', '0300-2222222');

insert into device_tokens (user_id, token)
values ('11111111-1111-1111-1111-111111111111', 'ci-token-leaver'),
       ('22222222-2222-2222-2222-222222222222', 'ci-token-stayer');

insert into reports (request_id, reporter_id, reason)
values ((select id from requests where patient_name = 'CI Other'),
        '11111111-1111-1111-1111-111111111111', 'CI report by the leaver');

-- Someone else's id is refused for a signed-in user (the Edge Function passes
-- the service role, which has no auth.uid() and may delete the given id).
set local role authenticated;
set local "request.jwt.claims" = '{"sub":"11111111-1111-1111-1111-111111111111","app_metadata":{"role":"DONOR"}}';
do $runner$ begin
  begin
    perform public.delete_account_data('22222222-2222-2222-2222-222222222222');
    raise exception 'CI-TEST FAIL: a user deleted another user''s account data';
  exception
    when insufficient_privilege then null;   -- expected
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      raise exception 'CI-TEST FAIL: foreign-id delete refused with SQLSTATE % (%), not 42501', sqlstate, sqlerrm;
  end;
end $runner$;
reset role;

-- The service-role path: delete the leaver.
select public.delete_account_data('11111111-1111-1111-1111-111111111111');

do $runner$ begin
  if exists (select 1 from contact_details where user_id = '11111111-1111-1111-1111-111111111111') then
    raise exception 'CI-TEST FAIL: the leaver''s contact_details row survived';
  end if;
  if exists (select 1 from device_tokens where user_id = '11111111-1111-1111-1111-111111111111') then
    raise exception 'CI-TEST FAIL: the leaver''s device token survived';
  end if;
  if exists (select 1 from reports where reporter_id = '11111111-1111-1111-1111-111111111111') then
    raise exception 'CI-TEST FAIL: the leaver''s report survived';
  end if;
  if exists (select 1 from offers where donor_auth_id = '11111111-1111-1111-1111-111111111111') then
    raise exception 'CI-TEST FAIL: the leaver''s offer survived';
  end if;
  if exists (select 1 from requests where patient_name = 'CI Open') then
    raise exception 'CI-TEST FAIL: the leaver''s open request survived';
  end if;

  -- Kept, but detached.
  if not exists (
    select 1 from requests
     where patient_name = 'CI Fulfilled'
       and requester_id is null
       and contact = ''
  ) then
    raise exception 'CI-TEST FAIL: a fulfilled request was not kept and detached';
  end if;

  -- The donor row is anonymised, and the donation history survives it.
  if not exists (
    select 1 from donors
     where name = 'Deleted donor' and phone = '' and city = ''
       and auth_id is null and available_to_donate = false
  ) then
    raise exception 'CI-TEST FAIL: the donor row was not anonymised';
  end if;
  if (select count(*) from donations) <> 1 then
    raise exception 'CI-TEST FAIL: the donation history was deleted with the account';
  end if;

  -- Another user's data is untouched.
  if not exists (select 1 from contact_details where user_id = '22222222-2222-2222-2222-222222222222') then
    raise exception 'CI-TEST FAIL: the other user''s contact_details row was deleted';
  end if;
  if not exists (select 1 from donors where name = 'CI Stayer' and auth_id is not null) then
    raise exception 'CI-TEST FAIL: the other user''s donor row was changed';
  end if;
  if not exists (select 1 from requests where patient_name = 'CI Other' and status = 'Pending') then
    raise exception 'CI-TEST FAIL: the other user''s request was deleted';
  end if;
  if not exists (select 1 from device_tokens where user_id = '22222222-2222-2222-2222-222222222222') then
    raise exception 'CI-TEST FAIL: the other user''s device token was deleted';
  end if;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- T17. The quote bank: every signed-in user can READ it, and NOBODY through
--      the API can write it. Its text appears on every user's home screen, so
--      a writable bank is a prompt-injection surface. The global cap table is
--      untouchable by users for the same reason a rate limit always is.
-- ---------------------------------------------------------------------------
begin;
insert into auth.users (id, email, raw_app_meta_data)
values ('33333333-3333-3333-3333-333333333333', 'ci.donor1@example.test', '{}')
on conflict (id) do nothing;

insert into public.ai_quotes (for_date, quote)
values (current_date, 'Donating blood is a quiet way to help a stranger.');
insert into public.ai_provider_calls (day, calls) values (current_date, 1);

set local role authenticated;
set local "request.jwt.claims" = '{"sub":"33333333-3333-3333-3333-333333333333","app_metadata":{"role":"DONOR"}}';

do $runner$ declare v_count int; begin
  select count(*) into v_count from public.ai_quotes;
  if v_count <> 1 then
    raise exception 'CI-TEST FAIL: a signed-in user cannot read the quote bank (saw % row(s))', v_count;
  end if;
end $runner$;

do $runner$ begin
  begin
    insert into public.ai_quotes (for_date, quote) values (current_date, 'INJECTED');
    raise exception 'CI-TEST FAIL: a user wrote into the quote bank';
  exception
    when insufficient_privilege then null;   -- expected
    when others then
      if sqlerrm like 'CI-TEST FAIL:%' then raise; end if;
      raise exception 'CI-TEST FAIL: quote insert refused with SQLSTATE % (%), not 42501', sqlstate, sqlerrm;
  end;
  if exists (select 1 from public.ai_quotes where quote = 'INJECTED') then
    raise exception 'CI-TEST FAIL: an injected quote is in the bank';
  end if;
end $runner$;

do $runner$ declare v_count int; begin
  -- A rate-limit table anyone can read is a rate limit anyone can work around.
  select count(*) into v_count from public.ai_provider_calls;
  if v_count <> 0 then
    raise exception 'CI-TEST FAIL: a user can read the global provider-call counter';
  end if;
  -- An UPDATE that RLS hides is SILENT (0 rows, no error), unlike an INSERT
  -- which is refused. So assert the row count, not an exception.
  update public.ai_provider_calls set calls = 0 where day = current_date;
  get diagnostics v_count = row_count;
  if v_count <> 0 then
    raise exception 'CI-TEST FAIL: a user reset the global provider-call counter';
  end if;
  delete from public.ai_provider_calls where day = current_date;
  get diagnostics v_count = row_count;
  if v_count <> 0 then
    raise exception 'CI-TEST FAIL: a user deleted the global provider-call counter';
  end if;
end $runner$;
rollback;

-- ---------------------------------------------------------------------------
-- The job must reach THIS line. If any check failed, psql already died on the
-- raised CI-TEST FAIL error before printing this.
-- ---------------------------------------------------------------------------
select 'ALL SQL SECURITY TESTS PASSED' as ci_result;
