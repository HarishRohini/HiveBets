-- wallet.changed history; gold takes the latest row per user.
select
    coalesce(payload->>'userId', event_key)          as user_id,
    payload->>'username'                             as username,
    payload->>'accountType'                          as account_type,
    cast(payload->>'availableCents' as bigint)       as available_cents,
    cast(payload->>'reservedCents' as bigint)        as reserved_cents,
    cast(payload->>'equityCents' as bigint)          as equity_cents,
    cast(payload->>'profitCents' as bigint)          as profit_cents,
    cast(payload->>'marketsTraded' as bigint)        as markets_traded,
    cast(payload->>'marketsWon' as bigint)           as markets_won,
    cast(payload->>'marketsResolved' as bigint)      as markets_resolved,
    occurred_at                                      as changed_at
from {{ dedup('wallet.changed') }}
