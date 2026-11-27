-- Invariants for the trading ledger; every row of the result should say ok = true.
select 'ledger balances' as check, sum(amount_cents) = 0 as ok from trading.ledger_entries
union all
select 'available matches ledger', bool_and(w.available_cents = coalesce(l.s, 0))
  from trading.wallets w left join (select user_id, sum(amount_cents) s from trading.ledger_entries
                                     where account = 'user:available' group by user_id) l using (user_id)
union all
select 'reserved matches ledger', bool_and(w.reserved_cents = coalesce(l.s, 0))
  from trading.wallets w left join (select user_id, sum(amount_cents) s from trading.ledger_entries
                                     where account = 'user:reserved' group by user_id) l using (user_id)
union all
select 'reserved matches open buy orders', bool_and(w.reserved_cents = coalesce(o.s, 0))
  from trading.wallets w left join (select user_id, sum(reserved_cents) s from trading.orders
                                     where status = 'OPEN' and action = 'BUY' group by user_id) o using (user_id)
union all
select 'escrow covers shares', bool_and(m.escrow_cents = 100 * coalesce(p.yes, 0) and coalesce(p.yes, 0) = coalesce(p.no, 0))
  from trading.markets m left join (select market_id, sum(yes_shares) yes, sum(no_shares) no from trading.positions
                                     group by market_id) p on p.market_id = m.id
 where m.status <> 'RESOLVED'
union all
select 'escrow ledger matches markets', bool_and(m.escrow_cents = coalesce(l.s, 0))
  from trading.markets m left join (select market_id, sum(amount_cents) s from trading.ledger_entries
                                     where account = 'market:escrow' group by market_id) l on l.market_id = m.id;
