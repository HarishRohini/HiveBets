"""Copy bronze Parquet from the lake to a local folder (only for the `local` dbt target).

    BRONZE_URI=/tmp/bronze python -m pipeline.sync_bronze
"""
import os
from pathlib import Path

import boto3
from botocore.config import Config

from . import config


def sync(dest: Path) -> int:
    s3 = boto3.client(
        "s3",
        endpoint_url="http://" + config.S3_ENDPOINT,
        aws_access_key_id=config.S3_ACCESS_KEY,
        aws_secret_access_key=config.S3_SECRET_KEY,
        region_name="us-east-1",
        config=Config(s3={"addressing_style": "path"}, proxies={}),
    )
    copied = 0
    for page in s3.get_paginator("list_objects_v2").paginate(Bucket=config.LAKE_BUCKET, Prefix="bronze/"):
        for obj in page.get("Contents", []):
            rel = obj["Key"][len("bronze/"):]
            target = dest / rel
            if target.exists() and target.stat().st_size == obj["Size"]:
                continue  # part files are immutable
            target.parent.mkdir(parents=True, exist_ok=True)
            s3.download_file(config.LAKE_BUCKET, obj["Key"], str(target))
            copied += 1
    return copied


if __name__ == "__main__":
    dest = Path(os.environ.get("BRONZE_URI", "/tmp/hivebets-bronze"))
    print(f"copied {sync(dest)} new bronze files to {dest}")
