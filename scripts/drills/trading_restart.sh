#!/usr/bin/env bash
# Drill: trading-service restarts in the middle of a load test.
# Expected: a short burst of failed requests while it is down (the gateway answers 5xx), then
# order books are rebuilt from Postgres, trading resumes, and the consistency check passes:
# no lost or duplicated trades, the ledger balances.
set -uo pipefail
cd "$(dirname "$0")/../.."
RATE=${RATE:-30}
echo "load: ${RATE} orders/s for 120 s; trading-service restarts after 30 s"
docker run --rm --network host -v "$PWD/load:/load" grafana/k6:1.4.2 run --quiet --no-thresholds \
  -e RATE="$RATE" -e DURATION=90s -e TRADERS=30 /load/trading.js > /tmp/hivebets-drill-k6.log 2>&1 &
k6=$!
sleep 30
echo "$(date +%T) restarting trading-service"; start=$(date +%s)
docker compose restart trading-service > /dev/null
until curl -sf http://localhost:8080/api/live/stats > /dev/null && \
      [ "$(docker compose ps trading-service --format '{{.Health}}')" = "healthy" ]; do sleep 1; done
echo "$(date +%T) trading-service healthy again after $(( $(date +%s) - start )) s"
wait $k6
echo "== load test during the drill"
grep -E "http_req_failed|order_latency|checks_succeeded|order accepted" /tmp/hivebets-drill-k6.log | sed 's/^/  /'
echo "== waiting for lake-sink's flush, then checking consistency"
sleep 35
scripts/drills/check_consistency.sh
