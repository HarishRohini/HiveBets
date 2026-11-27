#!/usr/bin/env bash
# Checks that nothing was lost or double-counted between Postgres (source of truth) and the
# event consumers, once the system has settled:
#   - the ledger invariants (scripts/check_ledger.sql)
#   - the outbox is fully published, no queue has a backlog and nothing is dead-lettered
#     (lake-sink holds up to 30 s of events unacknowledged on purpose, so unacked is fine)
#   - for every open market, realtime-service's volume in Redis equals trading-service's volume
# Exit code 0 when everything matches.
set -uo pipefail
cd "$(dirname "$0")/../.."
fail=0
psql() { docker compose exec -T postgres psql -U "${POSTGRES_USER:-hivebets}" -d hivebets -At -F' ' "$@"; }

echo "== ledger"
while read -r line; do
  echo "  $line"; [[ "$line" == *" f" ]] && fail=1
done < <(psql < scripts/check_ledger.sql)

echo "== outbox"
pending=$(psql -c "select count(*) from trading.outbox where published_at is null")
echo "  unpublished rows: $pending"; [ "$pending" = "0" ] || fail=1

echo "== queues"
queues=$(curl -s -u "${RABBITMQ_USER:-hivebets}:${RABBITMQ_PASSWORD:-hivebets}" http://localhost:15672/api/queues)
echo "$queues" | python3 -c "
import json,sys
bad=0
for q in json.load(sys.stdin):
    ready, unacked = q.get('messages_ready',0), q.get('messages_unacknowledged',0)
    dead = q['name'].endswith('.dlq') and q.get('messages',0) > 0
    print(f\"  {q['name']}: ready={ready} unacked={unacked}\" + ('  <- dead letters' if dead else ''))
    bad |= ready > 0 or dead
sys.exit(bad)" || fail=1

echo "== live volume (Postgres vs Redis)"
mismatches=0; checked=0
while read -r id vol; do
  [ -z "$id" ] && continue
  rv=$(docker compose exec -T redis redis-cli GET "mkt:$id:vol" < /dev/null | tr -d '\r')
  rv=${rv:-0}; checked=$((checked+1))
  if [ "$rv" != "$vol" ]; then echo "  MISMATCH $id postgres=$vol redis=$rv"; mismatches=$((mismatches+1)); fi
done < <(psql -c "select id, volume_cents from trading.markets where status <> 'RESOLVED' and trade_count > 0")
echo "  markets checked: $checked, mismatches: $mismatches"; [ $mismatches -eq 0 ] || fail=1

[ $fail -eq 0 ] && echo "CONSISTENT" || echo "INCONSISTENT"
exit $fail
