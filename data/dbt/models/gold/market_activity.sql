-- Volume and liquidity per market.
with t as (
    select market_id,
           sum(volume_cents) as volume_cents,
           count(*) as trades,
           min(executed_at) as first_trade_at,
           max(executed_at) as last_trade_at
    from {{ ref('trades') }} group by 1
),
traders as (
    select market_id, count(distinct user_id) as traders from (
        select market_id, bid_user_id as user_id from {{ ref('trades') }}
        union all
        select market_id, ask_user_id from {{ ref('trades') }}
    ) where user_id is not null group by 1
),
b as (
    select market_id,
           avg(case when best_bid is not null and best_ask is not null then best_ask - best_bid end) as avg_spread_cents,
           avg(depth_shares) as avg_depth_shares
    from {{ ref('book_snapshots') }} group by 1
),
ids as (
    select market_id from {{ ref('markets') }}
    union select market_id from t
    union select market_id from b
)
select
    ids.market_id,
    m.question,
    m.kind,
    coalesce(m.status, 'OPEN')                as status,
    coalesce(t.volume_cents, 0)::bigint       as volume_cents,
    coalesce(t.trades, 0)::bigint             as trades,
    coalesce(tr.traders, 0)::bigint           as traders,
    round(b.avg_spread_cents, 2)::double      as avg_spread_cents,
    round(b.avg_depth_shares, 1)::double      as avg_depth_shares,
    t.first_trade_at,
    t.last_trade_at
from ids
left join {{ ref('markets') }} m using (market_id)
left join t using (market_id)
left join traders tr using (market_id)
left join b using (market_id)
