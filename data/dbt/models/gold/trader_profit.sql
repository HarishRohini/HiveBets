-- Profit per trader: the latest wallet snapshot plus how much they traded.
with w as (
    select * from {{ ref('wallets') }}
    qualify row_number() over (partition by user_id order by changed_at desc) = 1
),
sides as (
    select bid_user_id as user_id, volume_cents from {{ ref('trades') }}
    union all
    select ask_user_id, volume_cents from {{ ref('trades') }}
),
t as (
    select user_id, count(*) as trades, sum(volume_cents) as volume_cents
    from sides where user_id is not null group by 1
)
select
    coalesce(w.user_id, t.user_id)            as user_id,
    w.username,
    w.account_type,
    coalesce(w.profit_cents, 0)::bigint       as profit_cents,
    w.equity_cents::bigint                    as equity_cents,
    coalesce(t.trades, 0)::bigint             as trades,
    coalesce(t.volume_cents, 0)::bigint       as volume_cents
from w full outer join t on w.user_id = t.user_id
