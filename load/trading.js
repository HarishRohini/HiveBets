// k6 load test for the trading API, through the gateway.
//   make load                     (or: docker run --rm -i --network host -v $PWD/load:/load grafana/k6:1.4.2 run /load/trading.js)
// Env: API (default http://localhost:8080), ADMIN_USERNAME, ADMIN_PASSWORD, RATE (orders/s, default 40),
//      DURATION (default 2m), TRADERS (default 40).
// Traders cross orders around 50¢ in one market, while also reading the book, the live state and
// their portfolio the way the web app does. Thresholds are the SLOs in ops/grafana (see docs/RUNBOOK.md).
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const API = __ENV.API || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 40);
const DURATION = __ENV.DURATION || '2m';
const TRADERS = Number(__ENV.TRADERS || 40);

const orderLatency = new Trend('order_latency', true);
const fills = new Counter('order_fills');
const rejected = new Counter('order_rejected');

export const options = {
  setupTimeout: '120s',
  scenarios: {
    orders: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.round(RATE / 4)),
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 100,
      stages: [
        { target: RATE, duration: '20s' },
        { target: RATE, duration: DURATION },
        { target: 0, duration: '10s' },
      ],
      exec: 'trade',
    },
    readers: {
      executor: 'constant-arrival-rate',
      rate: Math.max(1, Math.round(RATE / 2)),
      timeUnit: '1s',
      duration: DURATION,
      startTime: '20s',
      preAllocatedVUs: 10,
      maxVUs: 50,
      exec: 'read',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'order_latency': ['p(95)<300', 'p(99)<800'],
    'http_req_duration{kind:read}': ['p(95)<200'],
    checks: ['rate>0.99'],
  },
};

const json = { headers: { 'Content-Type': 'application/json' } };
const auth = (token, extra = {}) => ({ headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...extra } });

export function setup() {
  const admin = http.post(`${API}/api/auth/login`, JSON.stringify({
    username: __ENV.ADMIN_USERNAME || 'admin', password: __ENV.ADMIN_PASSWORD || 'change-me-admin' }), json);
  check(admin, { 'admin signed in': (r) => r.status === 200 });
  const adminToken = admin.json('token');
  const market = http.post(`${API}/api/markets`, JSON.stringify({
    question: `Load test ${new Date().toISOString()}: will the system hold?`, durationMinutes: 30 }), auth(adminToken));
  check(market, { 'market created': (r) => r.status === 201 });
  const run = Date.now().toString(36);
  const tokens = [];
  for (let i = 0; i < TRADERS; i++) {
    const r = http.post(`${API}/api/auth/signup`, JSON.stringify({ username: `load-${run}-${i}`, password: 'load-test-password' }), json);
    if (r.status === 201) tokens.push(r.json('token'));
  }
  if (tokens.length < 2) throw new Error(`could only create ${tokens.length} traders`);
  return { marketId: market.json('id'), tokens, adminToken };
}

export function trade(data) {
  const i = exec.scenario.iterationInTest;
  const token = data.tokens[i % data.tokens.length];
  // Alternate YES and NO buyers around 50¢ so most orders cross and settle.
  const yes = i % 2 === 0;
  const yesPrice = 48 + (i % 5);
  const body = { marketId: data.marketId, side: yes ? 'YES' : 'NO', action: 'BUY', price: yes ? yesPrice : 100 - yesPrice + 1, shares: 1 + (i % 5) };
  const r = http.post(`${API}/api/orders`, JSON.stringify(body), auth(token, { 'Idempotency-Key': `${data.marketId}-${i}` }));
  orderLatency.add(r.timings.duration);
  const ok = check(r, { 'order accepted': (res) => res.status === 201 || res.status === 200 });
  if (!ok) rejected.add(1, { status: String(r.status) });
  else if (r.json('filledShares') > 0) fills.add(1);
}

export function read(data) {
  const token = data.tokens[exec.scenario.iterationInTest % data.tokens.length];
  const params = auth(token);
  params.tags = { kind: 'read' };
  const pick = exec.scenario.iterationInTest % 3;
  const path = pick === 0 ? `/api/markets/${data.marketId}/book` : pick === 1 ? `/api/live/markets/${data.marketId}` : '/api/portfolio';
  check(http.get(`${API}${path}`, params), { 'read ok': (r) => r.status === 200 });
}

export function teardown(data) {
  // Leave the market open so drills can inspect it; print its id for the drill scripts.
  console.log(`MARKET_ID=${data.marketId}`);
}
