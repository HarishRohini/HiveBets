"""Dagster: dbt models as assets, then publish the gold marts to Postgres, every 5 minutes."""
import os
from pathlib import Path

import dagster as dg
from dagster_dbt import DbtCliResource, DbtProject, dbt_assets

from . import config, publish as publish_mod, sync_bronze

dbt_project = DbtProject(project_dir=config.DBT_DIR, profiles_dir=config.DBT_DIR)
dbt_project.prepare_if_dev()


@dg.asset(group_name="bronze", description="Local copy of bronze, only used by the `local` dbt target.")
def bronze_local_copy(context: dg.AssetExecutionContext) -> None:
    if os.environ.get("DBT_TARGET") != "local":
        context.log.info("dbt reads bronze straight from S3; nothing to copy")
        return
    dest = Path(os.environ.setdefault("BRONZE_URI", "/tmp/hivebets-bronze"))
    context.log.info(f"copied {sync_bronze.sync(dest)} new files to {dest}")


@dbt_assets(manifest=dbt_project.manifest_path)
def lake_models(context: dg.AssetExecutionContext, dbt: DbtCliResource):
    yield from dbt.cli(["build"], context=context).stream()


GOLD_KEYS = [dg.AssetKey(["gold", t]) for t in config.GOLD_TABLES]


@dg.asset(
    group_name="serving",
    deps=[bronze_local_copy, *GOLD_KEYS],
    description="Gold marts copied into the Postgres `analytics` schema in one transaction.",
)
def analytics_tables(context: dg.AssetExecutionContext) -> dg.MaterializeResult:
    result = publish_mod.publish()
    context.log.info(str(result))
    return dg.MaterializeResult(metadata={"as_of": result["as_of"], **{f"rows_{k}": v for k, v in result["rows"].items()}})


lake_job = dg.define_asset_job("lake_refresh", selection=dg.AssetSelection.all())

defs = dg.Definitions(
    assets=[bronze_local_copy, lake_models, analytics_tables],
    jobs=[lake_job],
    schedules=[
        dg.ScheduleDefinition(job=lake_job, cron_schedule="*/5 * * * *", default_status=dg.DefaultScheduleStatus.RUNNING)
    ],
    resources={"dbt": DbtCliResource(project_dir=dbt_project)},
)
