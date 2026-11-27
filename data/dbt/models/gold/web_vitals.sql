-- 75th percentile of each web vital per page (web_vital events carry props.metric and props.value).
select
    upper(coalesce(props->>'metric', props->>'name'))      as metric,
    regexp_replace(coalesce(path, '/'), '/[0-9a-f-]{36}', '/:id') as page,
    round(quantile_cont(try_cast(props->>'value' as double), 0.75), 3) as p75,
    count(*)::bigint                                        as samples
from {{ ref('client_events') }}
where event_name = 'web_vital' and try_cast(props->>'value' as double) is not null
  and coalesce(props->>'metric', props->>'name') is not null
group by 1, 2
