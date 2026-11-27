# Stable `data-testid` selectors

The AI swarm (Phase 4) drives the UI only through these. Keep them when changing markup; add new ones here.

| Area | Test ids |
|---|---|
| Sign in | `tab-sign-in`, `tab-sign-up`, `login-username`, `login-password`, `signup-invite`, `login-submit`, `login-error` |
| Nav | `nav-home`, `nav-markets`, `nav-portfolio`, `nav-leaderboard`, `nav-analytics`, `nav-admin`, `nav-cash`, `nav-username`, `nav-sign-out`, `traders-online` |
| Markets list | `filter-all`, `filter-simulated`, `filter-real`, `filter-soon`, `market-card` (has `data-market-id`), `market-kind`, `market-question`, `market-price`, `time-left`, `market-open`, `resolved-row`, `no-markets`, `denied-banner` |
| Market page | `back-to-markets`, `market-question`, `yes-price`, `market-volume`, `market-traders`, `time-left`, `resolved-banner`, `price-chart`, `headline` (has `data-source`), `headline-text`, `headline-link`, `best-bid`, `best-ask`, `book-bid`, `book-ask`, `trade-row`, `order-row`, `order-cancel` |
| Order ticket | `ticket-action-buy`, `ticket-action-sell`, `ticket-side-yes`, `ticket-side-no`, `ticket-shares`, `ticket-shares-inc`, `ticket-shares-dec`, `ticket-price`, `ticket-price-reset`, `ticket-cost`, `ticket-payout`, `ticket-profit`, `ticket-available`, `ticket-submit`, `ticket-toast`, `position` |
| Portfolio | `wallet-equity`, `wallet-profit`, `wallet-available`, `wallet-positions`, `position-row`, `position-trade`, `order-row`, `order-cancel`, `resolved-holding` |
| Leaderboard | `leader-row`, `account-type`, `my-rank` |
| Analytics | `data-as-of`, `kpi-resolved`, `kpi-trades`, `kpi-volume`, `kpi-orders`, `kpi-sessions`, `analytics-denied` |
| Admin | `create-question`, `create-yes`, `create-no`, `create-kind-simulated`, `create-kind-real`, `create-mode-duration`, `create-minutes`, `create-mode-date`, `create-close-at`, `create-submit`, `create-done`, `headline-market`, `headline-input`, `headline-submit`, `headline-done`, `admin-market-row` (has `data-market-id`), `resolve-yes`, `resolve-no`, `resolve-confirm`, `resolve-cancel`, `ingested-row` |
