"""Settings for the lake pipeline, all from the environment (see docker-compose.yml)."""
import os
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent.parent
DBT_DIR = DATA_DIR / "dbt"

S3_ENDPOINT = os.environ.get("S3_ENDPOINT", "localhost:9000")  # host:port, no scheme
S3_ACCESS_KEY = os.environ.get("S3_ACCESS_KEY", "hivebets")
S3_SECRET_KEY = os.environ.get("S3_SECRET_KEY", "hivebets-secret")
LAKE_BUCKET = os.environ.get("LAKE_BUCKET", "hivebets-lake")

PG_DSN = "host={} port={} dbname={} user={} password={}".format(
    os.environ.get("PG_HOST", "localhost"),
    os.environ.get("PG_PORT", "5432"),
    os.environ.get("PG_DB", "hivebets"),
    os.environ.get("PG_USER", "hivebets"),
    os.environ.get("PG_PASSWORD", "hivebets"),
)

DUCKDB_PATH = os.environ.get("DUCKDB_PATH", "/tmp/hivebets-lake.duckdb")
os.environ.setdefault("DUCKDB_PATH", DUCKDB_PATH)

# The gold tables copied into Postgres for analytics-service and Grafana.
GOLD_TABLES = ["market_activity", "session_funnel", "web_vitals", "trader_profit", "daily_summary"]
