#!/usr/bin/env bash
# Run the lake pipeline once (dbt build + publish to Postgres), without Dagster.
set -euo pipefail
cd "$(dirname "$0")"
exec python -m pipeline.run_once "$@"
