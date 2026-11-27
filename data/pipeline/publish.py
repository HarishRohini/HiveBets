"""Copy the gold marts from DuckDB into the Postgres `analytics` schema.

All tables and the `meta.as_of` stamp are replaced in one Postgres transaction, so readers
see either the previous run or this one, never a mix.
"""
from datetime import datetime, timezone

import duckdb
import psycopg

from . import config

PG_TYPES = {
    "VARCHAR": "text", "BIGINT": "bigint", "INTEGER": "bigint", "HUGEINT": "numeric", "SMALLINT": "bigint",
    "DOUBLE": "double precision", "FLOAT": "double precision", "BOOLEAN": "boolean", "DATE": "date",
    "TIMESTAMP WITH TIME ZONE": "timestamptz", "TIMESTAMP": "timestamp",
}


def pg_type(duck_type: str) -> str:
    if duck_type.startswith("DECIMAL"):
        return "numeric"
    return PG_TYPES.get(duck_type, "text")


def publish(duckdb_path: str = config.DUCKDB_PATH, dsn: str = config.PG_DSN) -> dict:
    counts = {}
    as_of = datetime.now(timezone.utc)
    with duckdb.connect(duckdb_path, read_only=True) as duck, psycopg.connect(dsn) as pg:
        with pg.transaction(), pg.cursor() as cur:
            cur.execute("create schema if not exists analytics")
            for table in config.GOLD_TABLES:
                cols = duck.execute(
                    "select column_name, data_type from information_schema.columns "
                    "where table_schema = 'gold' and table_name = ? order by ordinal_position", [table]).fetchall()
                if not cols:
                    raise RuntimeError(f"gold.{table} is missing; did dbt build run?")
                rows = duck.execute(f'select * from gold."{table}"').fetchall()
                ddl = ", ".join(f'"{c}" {pg_type(t)}' for c, t in cols)
                cur.execute(f'drop table if exists analytics."{table}__new"')
                cur.execute(f'create table analytics."{table}__new" ({ddl})')
                names = ", ".join(f'"{c}"' for c, _ in cols)
                with cur.copy(f'copy analytics."{table}__new" ({names}) from stdin') as copy:
                    for r in rows:
                        copy.write_row(r)
                cur.execute(f'drop table if exists analytics."{table}"')
                cur.execute(f'alter table analytics."{table}__new" rename to "{table}"')
                counts[table] = len(rows)
            cur.execute("create table if not exists analytics.meta (as_of timestamptz not null)")
            cur.execute("delete from analytics.meta")
            cur.execute("insert into analytics.meta (as_of) values (%s)", [as_of])
    return {"as_of": as_of.isoformat(), "rows": counts}


if __name__ == "__main__":
    print(publish())
