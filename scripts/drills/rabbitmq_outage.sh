#!/usr/bin/env bash
# Drill: RabbitMQ goes away for OUTAGE seconds in the middle of a load test.
# Expected: orders keep being accepted (the outbox holds the events), the relay backs off,
# and once the broker is back the outbox drains, consumers catch up and the consistency
# check passes with nothing lost or counted twice. Watch it on the Grafana dashboard
# ("Outbox pending", "Queue depth", "Order rate").
set -uo pipefail
cd "$(dirname "$0")/../.."
OUTAGE=${OUTAGE:-60}
RATE=${RATE:-30}
psql() { docker compose exec -T postgres psql -U "${POSTGRES_USER:-hivebets}" -d hivebets -At "$@" < /dev/null; }

echo "load: ${RATE} orders/s for $((OUTAGE + 90)) s; RabbitMQ down for ${OUTAGE} s after 30 s"
docker run --rm --network host -v "$PWD/load:/load" grafana/k6:1.4.2 run --quiet --no-thresholds \
  -e RATE="$RATE" -e DURATION="$((OUTAGE + 60))s" -e TRADERS=30 /load/trading.js > /tmp/hivebets-drill-k6.log 2>&1 &
k6=$!
sleep 30
echo "$(date +%T) stopping rabbitmq"; docker compose stop rabbitmq > /dev/null
peak=0
for _ in $(seq 1 "$((OUTAGE / 5))"); do
  sleep 5
  n=$(psql -c "select count(*) from trading.outbox where published_at is null")
  [ "$n" -gt "$peak" ] && peak=$n
  echo "$(date +%T) broker down, outbox pending: $n"
done
echo "$(date +%T) starting rabbitmq"; docker compose start rabbitmq > /dev/null
for _ in $(seq 1 60); do
  n=$(psql -c "select count(*) from trading.outbox where published_at is null")
  echo "$(date +%T) outbox pending: $n"
  [ "$n" = "0" ] && break
  sleep 5
done
wait $k6
echo "== load test during the drill"
grep -E "http_req_failed|order_latency|checks_succeeded|order accepted" /tmp/hivebets-drill-k6.log | sed 's/^/  /'
echo "  outbox peak while the broker was down: $peak rows"
echo "== waiting for lake-sink's flush, then checking consistency"
sleep 35
scripts/drills/check_consistency.sh
