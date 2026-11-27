-- trade.executed: one row per fill. Prices are YES cents. volume_cents is the cash that changed
-- hands, the same rule trading-service and realtime-service use: a mint or burn moves 100 cents a
-- share, a YES transfer the price, a NO transfer 100 minus the price.
select
    payload->>'id'                                   as trade_id,
    coalesce(payload->>'marketId', event_key)        as market_id,
    cast(payload->>'price' as integer)               as price,
    cast(payload->>'shares' as bigint)               as shares,
    cast(payload->>'shares' as bigint) * case payload->>'kind'
        when 'MINT' then 100
        when 'BURN' then 100
        when 'TRANSFER_NO' then 100 - cast(payload->>'price' as bigint)
        else cast(payload->>'price' as bigint)
    end                                              as volume_cents,
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
