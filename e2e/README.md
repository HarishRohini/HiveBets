# End-to-end tests

Playwright tests that drive the web app like a person (and like the AI swarm will), using only `data-testid` selectors (see `web/TESTIDS.md`). They need the whole stack running.

```bash
make up                       # from the repo root
cd e2e && pnpm install
pnpm exec playwright install chromium   # once
pnpm test                     # = playwright test --trace off
```

Settings (environment variables): `E2E_BASE_URL` (default `http://localhost:3000`), `E2E_API_URL` (default `http://localhost:8080`), `ADMIN_USERNAME` / `ADMIN_PASSWORD` (default `admin` / `change-me-admin`). Each test signs up its own traders and opens its own market, so tests are independent and can be re-run against the same stack. To debug a failure, run it again with `--trace on` and open the report with `pnpm report`.
