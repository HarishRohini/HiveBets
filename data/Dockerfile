# Lake pipeline: dbt (DuckDB) models + publish to Postgres, scheduled by Dagster.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    DAGSTER_HOME=/opt/dagster/home \
    DUCKDB_PATH=/opt/dagster/lake.duckdb \
    DBT_TARGET=lake

RUN useradd --create-home --uid 10001 app \
 && mkdir -p /opt/dagster/home /app && chown -R app /opt/dagster /app
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

USER app
# Download DuckDB's httpfs extension now, so reading s3:// needs no network at run time.
RUN python -c "import duckdb; c = duckdb.connect(); c.sql('INSTALL httpfs'); c.sql('LOAD httpfs')"

COPY --chown=app dagster.yaml /opt/dagster/home/dagster.yaml
COPY --chown=app dbt ./dbt
COPY --chown=app pipeline ./pipeline
COPY --chown=app run_once.sh ./
# Build the dbt manifest Dagster reads (parsing needs no lake access).
RUN cd dbt && dbt parse --profiles-dir . --quiet

EXPOSE 3000
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:3000/server_info', timeout=4)"
# Webserver and daemon (the daemon runs the 5-minute schedule).
CMD ["dagster", "dev", "-h", "0.0.0.0", "-p", "3000", "-m", "pipeline.definitions"]
