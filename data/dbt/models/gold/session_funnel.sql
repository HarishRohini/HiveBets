-- How far each browser session got. A step counts a session once, whatever it did before.
--   1 Visited           any clickstream event
--   2 Viewed a market   a page_view on /markets/<id>
--   3 Opened ticket     a click whose props.testid starts with 'ticket-' (side, shares, price or submit)
--   4 Placed order      an order_submitted event
--   5 Order filled      the session's user took part in a trade between the session's first event
--                       and 5 minutes after its last one
with s as (
    select session_id, any_value(user_id) filter (where user_id is not null) as user_id,
           min(event_at) as started_at, max(event_at) as ended_at,
           bool_or(event_name = 'page_view' and regexp_matches(path, '^/markets/[^/]+')) as viewed_market,
           bool_or(event_name = 'click' and starts_with(coalesce(props->>'testid', ''), 'ticket-')) as opened_ticket,
           bool_or(event_name = 'order_submitted') as placed_order
    from {{ ref('client_events') }}
    where session_id is not null
    group by 1
),
filled as (
    select distinct s.session_id
    from s
    join {{ ref('trades') }} t
      on (t.bid_user_id = s.user_id or t.ask_user_id = s.user_id)
     and t.executed_at between s.started_at and s.ended_at + interval 5 minute
    where s.placed_order
),
steps as (
    select 1 as step_order, 'Visited' as step, count(*) as sessions from s
    union all select 2, 'Viewed a market', count(*) filter (where viewed_market) from s
    union all select 3, 'Opened ticket', count(*) filter (where opened_ticket) from s
    union all select 4, 'Placed order', count(*) filter (where placed_order) from s
    union all select 5, 'Order filled', count(*) from filled
)
select step_order, step, sessions::bigint as sessions from steps
