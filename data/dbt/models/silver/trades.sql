-- trade.executed: one row per fill. Prices are YES cents; volume is price x shares.
select
    payload->>'id'                                   as trade_id,
    coalesce(payload->>'marketId', event_key)        as market_id,
    cast(payload->>'price' as integer)               as price,
    cast(payload->>'shares' as bigint)               as shares,
    cast(payload->>'price' as bigint) * cast(payload->>'shares' as bigint) as volume_cents,
    payload->>'kind'                                 as kind,
    payload->>'takerUserId'                          as taker_user_id,
    payload->>'takerUsername'                        as taker_username,
    payload->>'takerSide'                            as taker_side,
    payload->>'takerAction'                          as taker_action,
    payload->>'makerUsername'                        as maker_username,
    payload->>'bidUserId'                            as bid_user_id,
    payload->>'askUserId'                            as ask_user_id,
    coalesce(try_cast(payload->>'executedAt' as timestamptz), occurred_at) as executed_at,
    event_id
from {{ dedup('trade.executed') }}
