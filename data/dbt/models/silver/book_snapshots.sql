-- book.changed: top-of-book and visible depth after every change.
with b as (
    select
        coalesce(payload->>'marketId', event_key) as market_id,
        occurred_at as changed_at,
        from_json(coalesce(payload->'bids', '[]'), '[{"price":"INTEGER","shares":"BIGINT"}]') as bids,
        from_json(coalesce(payload->'asks', '[]'), '[{"price":"INTEGER","shares":"BIGINT"}]') as asks
    from {{ dedup('book.changed') }}
)
select
    market_id,
    changed_at,
    list_max(list_transform(bids, x -> x.price)) as best_bid,
    list_min(list_transform(asks, x -> x.price)) as best_ask,
    coalesce(list_sum(list_transform(bids, x -> x.shares)), 0)
      + coalesce(list_sum(list_transform(asks, x -> x.shares)), 0) as depth_shares
from b
