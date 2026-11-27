-- order.accepted plus whether the order was later cancelled.
with accepted as (
    select
        payload->>'orderId'                 as order_id,
        coalesce(payload->>'marketId', event_key) as market_id,
        payload->>'userId'                  as user_id,
        payload->>'side'                    as side,
        payload->>'action'                  as action,
        cast(payload->>'price' as integer)  as price,
        cast(payload->>'shares' as bigint)  as shares,
        cast(payload->>'yesPrice' as integer) as yes_price,
        payload->>'bookSide'                as book_side,
        occurred_at                         as accepted_at
    from {{ dedup('order.accepted') }}
),
cancelled as (
    select payload->>'orderId' as order_id,
           min(occurred_at) as cancelled_at,
           any_value(cast(payload->>'remainingShares' as bigint)) as remaining_shares,
           any_value(payload->>'reason') as cancel_reason
    from {{ dedup('order.cancelled') }}
    group by 1
)
select a.*, c.cancelled_at, c.remaining_shares, c.cancel_reason
from accepted a
left join cancelled c using (order_id)
