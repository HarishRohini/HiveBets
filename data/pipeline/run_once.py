"""Run the whole pipeline once without Dagster: (sync bronze) -> dbt build -> publish to Postgres.

    python -m pipeline.run_once            # reads bronze from S3 (dbt target `lake`, needs httpfs)
    DBT_TARGET=local BRONZE_URI=/tmp/hivebets-bronze python -m pipeline.run_once
"""
import os
import sys
from pathlib import Path

import subprocess

from . import config, publish


def dbt_build() -> None:
    # A separate process, so its DuckDB connection is closed before publish opens the file.
    args = ["dbt", "build", "--project-dir", str(config.DBT_DIR), "--profiles-dir", str(config.DBT_DIR)]
    if subprocess.run(args).returncode != 0:
        raise SystemExit("dbt build failed")


def main() -> None:
    if os.environ.get("DBT_TARGET") == "local":
        from . import sync_bronze
        dest = Path(os.environ.setdefault("BRONZE_URI", "/tmp/hivebets-bronze"))
        print(f"synced {sync_bronze.sync(dest)} new bronze files to {dest}")
    dbt_build()
    print(publish.publish())


if __name__ == "__main__":
    sys.exit(main())
