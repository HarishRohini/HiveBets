{#
  bronze(pattern): every bronze Parquet row for the event types matching `pattern`
  (a folder glob such as 'trade.executed' or 'client.*'), with the hive partition columns dt and hr.

  When no file exists yet (a fresh lake, or a type nothing has produced) it returns an empty
  relation with the same columns, so every downstream model still builds.
#}
{% macro bronze(pattern) -%}
  {%- set root = env_var('BRONZE_URI', 's3://' ~ env_var('LAKE_BUCKET', 'hivebets-lake') ~ '/bronze') -%}
  {%- set path = root ~ "/" ~ pattern ~ "/*/*/*.parquet" -%}
  {%- set has_files = false -%}
  {%- if execute -%}
    {%- set res = run_query("select count(*) from glob('" ~ path ~ "')") -%}
    {%- set has_files = res.columns[0].values()[0] > 0 -%}
  {%- endif -%}
  {%- if has_files -%}
    (select event_id, event_type, version, occurred_at, source, event_key, trace_id, payload, received_at,
            cast(dt as varchar) as dt, cast(hr as varchar) as hr
     from read_parquet('{{ path }}', hive_partitioning = true, union_by_name = true))
  {%- else -%}
    (select null::varchar as event_id, null::varchar as event_type, null::integer as version,
            null::timestamptz as occurred_at, null::varchar as source, null::varchar as event_key,
            null::varchar as trace_id, null::varchar as payload, null::timestamptz as received_at,
            null::varchar as dt, null::varchar as hr
     where false)
  {%- endif -%}
{%- endmacro %}

{# Keep the first copy of each event; the bus is at-least-once. #}
{% macro dedup(pattern) -%}
  (select * from {{ bronze(pattern) }} b
   qualify row_number() over (partition by event_id order by received_at) = 1)
{%- endmacro %}
