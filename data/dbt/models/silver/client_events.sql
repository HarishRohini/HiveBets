-- Clickstream and web-vitals events sent by the browser through the gateway.
select
    replace(event_type, 'client.', '')              as event_name,
    payload->>'sessionId'                           as session_id,
    nullif(payload->>'userId', '')                  as user_id,
    payload->>'path'                                as path,
    coalesce(try_cast(payload->>'ts' as timestamptz),
             make_timestamptz(try_cast(payload->>'ts' as bigint) * 1000),
             occurred_at)                           as event_at,
    payload->'props'                                as props,
    payload->>'userAgent'                           as user_agent,
    event_id
from {{ dedup('client.*') }}
