-- One row per market: what it asked, and how it ended.
with created as (
    select
        coalesce(payload->>'id', event_key)  as market_id,
        payload->>'question'                 as question,
        payload->>'kind'                     as kind,
        try_cast(payload->>'closeAt' as timestamptz) as close_at,
        occurred_at                          as created_at
    from {{ dedup('market.created') }}
    qualify row_number() over (partition by coalesce(payload->>'id', event_key) order by occurred_at) = 1
),
closed as (
    select coalesce(payload->>'marketId', event_key) as market_id, min(occurred_at) as closed_at
    from {{ dedup('market.closed') }} group by 1
),
resolved as (
    select coalesce(payload->>'marketId', event_key) as market_id,
           any_value(payload->>'outcome') as outcome,
           min(coalesce(try_cast(payload->>'resolvedAt' as timestamptz), occurred_at)) as resolved_at
    from {{ dedup('market.resolved') }} group by 1
),
ids as (
    select market_id from created union select market_id from closed union select market_id from resolved
)
select
    ids.market_id,
    c.question,
    c.kind,
    case when r.market_id is not null then 'RESOLVED'
         when cl.market_id is not null then 'CLOSED'
         else 'OPEN' end as status,
    c.created_at,
    c.close_at,
    cl.closed_at,
    r.outcome,
    r.resolved_at
from ids
left join created c using (market_id)
left join closed cl using (market_id)
left join resolved r using (market_id)
