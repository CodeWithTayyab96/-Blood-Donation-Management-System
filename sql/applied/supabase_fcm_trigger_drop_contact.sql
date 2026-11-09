-- Remove the requester's phone number from the urgent-request trigger payload.
--
-- The 'contact' field traversed pg_net to the Edge Function, leaking PII
-- through the notification pipeline. The push-notify function does not need
-- the phone number to build or route the FCM message.

create or replace function public.on_urgent_request()
returns trigger language plpgsql as $$
begin
  perform public.push_send(jsonb_build_object(
    'type', 'urgent_request',
    'request_id', NEW.id,
    'blood_group', NEW.blood_group,
    'urgency', NEW.urgency,
    'units_needed', NEW.units_needed,
    'hospital', NEW.hospital,
    'city', NEW.city
  ));
  return null;
end;
$$;
