-- Headline numbers per UTC day.
with days as (
    select cast(event_at as date) as day from {{ ref('client_events') }}
    union select cast(executed_at as date) from {{ ref('trades') }}
    union select cast(accepted_at as date) from {{ ref('orders') }}
    union select cast(resolved_at as date) from {{ ref('markets') }} where resolved_at is not null
),
sessions as (select cast(event_at as date) as day, count(distinct session_id) as n from {{ ref('client_events') }} group by 1),
trades as (select cast(executed_at as date) as day, count(*) as n, sum(volume_cents) as v from {{ ref('trades') }} group by 1),
orders as (select cast(accepted_at as date) as day, count(*) as n from {{ ref('orders') }} group by 1),
resolved as (select cast(resolved_at as date) as day, count(*) as n from {{ ref('markets') }} where resolved_at is not null group by 1)
select
    d.day,
    coalesce(s.n, 0)::bigint as sessions,
    coalesce(t.n, 0)::bigint as trades,
    coalesce(t.v, 0)::bigint as volume_cents,
    coalesce(r.n, 0)::bigint as markets_resolved,
    coalesce(o.n, 0)::bigint as orders
from days d
left join sessions s using (day)
left join trades t using (day)
left join orders o using (day)
left join resolved r using (day)
where d.day is not null
