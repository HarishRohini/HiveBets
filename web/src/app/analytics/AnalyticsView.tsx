"use client";
import { useQuery } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { clockTime, money, moneyShort, number } from "@/lib/format";
import { PageHeader, Shell } from "@/components/Nav";
import { Bars } from "@/components/LineChart";
import { ErrorNote, Stat } from "@/components/ui";

const VITAL_UNITS: Record<string, string> = { LCP: "ms", INP: "ms", TTFB: "ms", FCP: "ms", CLS: "" };

export function AnalyticsView() {
  const summary = useQuery({ queryKey: ["an", "summary"], queryFn: api.analyticsSummary, refetchInterval: 60000 });
  const markets = useQuery({ queryKey: ["an", "markets"], queryFn: api.analyticsMarkets, refetchInterval: 60000 });
  const funnel = useQuery({ queryKey: ["an", "funnel"], queryFn: api.analyticsFunnel, refetchInterval: 60000 });
  const vitals = useQuery({ queryKey: ["an", "vitals"], queryFn: api.analyticsVitals, refetchInterval: 60000 });
  const traders = useQuery({ queryKey: ["an", "traders"], queryFn: api.analyticsTraders, refetchInterval: 60000 });

  if (summary.error instanceof ApiError && summary.error.status === 403) {
    return (
      <Shell>
        <PageHeader title="Analytics" />
        <p role="alert" data-testid="analytics-denied" className="rounded-lg border border-no px-4 py-3 text-soft">Not allowed: analytics is for admins only.</p>
      </Shell>
    );
  }

  const s = summary.data;
  const top = funnel.data?.[0]?.sessions || 0;
  const topMarkets = [...(markets.data ?? [])].sort((a, b) => b.volumeCents - a.volumeCents).slice(0, 8);
  const topTraders = [...(traders.data ?? [])].sort((a, b) => b.profitCents - a.profitCents);
  const traderRows = [...topTraders.slice(0, 5), ...topTraders.slice(-3).filter((t) => !topTraders.slice(0, 5).includes(t))];

  return (
    <Shell>
      <PageHeader title="Analytics" sub="Built from the data lake, refreshed by the pipeline every 5 minutes.">
        <span className="rounded-full bg-no-deep px-3 py-1.5 text-xs font-medium text-amber">Admin only</span>
        <span className="pill font-mono" data-testid="data-as-of">
          {s?.asOf ? `Data as of ${clockTime(s.asOf)}` : "No pipeline run yet"}
        </span>
      </PageHeader>
      <ErrorNote error={summary.error} />

      <dl className="grid gap-5 [grid-template-columns:repeat(auto-fit,minmax(200px,1fr))]">
        <Stat label="Markets resolved" value={number(s?.marketsResolved)} testid="kpi-resolved" />
        <Stat label="Trades" value={number(s?.trades)} testid="kpi-trades" />
        <Stat label="Volume traded" value={moneyShort(s?.volumeCents)} testid="kpi-volume" />
        <Stat label="Orders placed" value={number(s?.orders)} testid="kpi-orders" />
        <Stat label="Sessions" value={number(s?.sessions)} testid="kpi-sessions" />
      </dl>

      <div className="grid gap-5 [grid-template-columns:repeat(auto-fit,minmax(min(440px,100%),1fr))]">
        <section aria-label="Session funnel" className="card flex flex-col gap-3.5">
          <div>
            <h2 className="text-[15px] font-semibold">Where do sessions drop off?</h2>
            <p className="mt-1 text-[13px] text-mute">From clickstream events sent by the web app</p>
          </div>
          {(funnel.data ?? []).length === 0 ? (
            <p className="text-[13px] text-mute">No sessions recorded yet.</p>
          ) : (
            <Bars rows={(funnel.data ?? []).map((f) => ({ name: f.step, value: f.sessions, label: top ? `${number(f.sessions)} · ${Math.round((f.sessions / top) * 100)}%` : number(f.sessions) }))} />
          )}
        </section>

        <section aria-label="Volume by market" className="card flex flex-col gap-3.5">
          <div>
            <h2 className="text-[15px] font-semibold">Where is the money going?</h2>
            <p className="mt-1 text-[13px] text-mute">Volume traded per market, top 8</p>
          </div>
          {topMarkets.length === 0 ? (
            <p className="text-[13px] text-mute">No trades yet.</p>
          ) : (
            <Bars rows={topMarkets.map((m) => ({ name: m.question, value: m.volumeCents, label: moneyShort(m.volumeCents) }))} />
          )}
        </section>

        <section aria-label="Profit per trader" className="card flex flex-col gap-3.5">
          <div>
            <h2 className="text-[15px] font-semibold">Who is making money?</h2>
            <p className="mt-1 text-[13px] text-mute">Profit per trader, best and worst</p>
          </div>
          {traderRows.length === 0 ? (
            <p className="text-[13px] text-mute">No traders yet.</p>
          ) : (
            <Bars rows={traderRows.map((t) => ({ name: `${t.username}${t.accountType === "AGENT" ? " (AI)" : ""}`, value: t.profitCents, label: money(t.profitCents, { sign: true }), tone: t.profitCents < 0 ? "no" : "yes" }))} />
          )}
        </section>

        <section aria-label="Page speed" className="card flex flex-col gap-3.5">
          <div>
            <h2 className="text-[15px] font-semibold">How fast do pages feel?</h2>
            <p className="mt-1 text-[13px] text-mute">75th percentile web vitals per page</p>
          </div>
          <div className="relative overflow-x-auto">
            <table className="w-full min-w-[360px] border-collapse text-[13px]">
              <thead>
                <tr className="text-left text-xs text-mute">
                  <th scope="col" className="py-2 pr-3 font-medium">Page</th>
                  <th scope="col" className="p-2 font-medium">Metric</th>
                  <th scope="col" className="p-2 text-right font-medium">p75</th>
                  <th scope="col" className="py-2 pl-2 text-right font-medium">Samples</th>
                </tr>
              </thead>
              <tbody>
                {(vitals.data ?? []).length === 0 && <tr><td colSpan={4} className="border-t border-line py-3 text-mute">No samples yet.</td></tr>}
                {(vitals.data ?? []).map((v) => (
                  <tr key={v.page + v.metric} className="border-t border-line">
                    <td className="py-2 pr-3 font-mono">{v.page}</td>
                    <td className="p-2">{v.metric}</td>
                    <td className="p-2 text-right font-mono">{v.metric === "CLS" ? v.p75.toFixed(3) : `${Math.round(v.p75)} ${VITAL_UNITS[v.metric] ?? ""}`}</td>
                    <td className="py-2 pl-2 text-right font-mono">{number(v.samples)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section aria-label="Market liquidity" className="card flex flex-col gap-3.5">
          <div>
            <h2 className="text-[15px] font-semibold">How liquid are the markets?</h2>
            <p className="mt-1 text-[13px] text-mute">Average spread and depth from order book snapshots</p>
          </div>
          <div className="relative overflow-x-auto">
            <table className="w-full min-w-[420px] border-collapse text-[13px]">
              <thead>
                <tr className="text-left text-xs text-mute">
                  <th scope="col" className="py-2 pr-3 font-medium">Market</th>
                  <th scope="col" className="p-2 text-right font-medium">Trades</th>
                  <th scope="col" className="p-2 text-right font-medium">Traders</th>
                  <th scope="col" className="p-2 text-right font-medium">Avg spread</th>
                  <th scope="col" className="py-2 pl-2 text-right font-medium">Avg depth</th>
                </tr>
              </thead>
              <tbody>
                {(markets.data ?? []).length === 0 && <tr><td colSpan={5} className="border-t border-line py-3 text-mute">No markets yet.</td></tr>}
                {(markets.data ?? []).slice(0, 12).map((m) => (
                  <tr key={m.marketId} className="border-t border-line">
                    <td className="max-w-[260px] truncate py-2 pr-3" title={m.question}>{m.question}</td>
                    <td className="p-2 text-right font-mono">{number(m.trades)}</td>
                    <td className="p-2 text-right font-mono">{number(m.traders)}</td>
                    <td className="p-2 text-right font-mono">{m.avgSpreadCents == null ? "—" : `${m.avgSpreadCents.toFixed(1)}¢`}</td>
                    <td className="py-2 pl-2 text-right font-mono">{m.avgDepthShares == null ? "—" : number(Math.round(m.avgDepthShares))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section aria-label="Coming with the AI swarm" className="card flex flex-col gap-3">
          <h2 className="text-[15px] font-semibold">Coming in Phase 4</h2>
          <p className="text-[13px] text-mute">
            These need the world engine and the AI traders, which know the hidden true odds of simulated markets:
          </p>
          <ul className="flex list-disc flex-col gap-1.5 pl-5 text-[13px] text-soft">
            <li>Did the price track the true odds?</li>
            <li>Are prices honest? (calibration)</li>
            <li>Which personas make money?</li>
            <li>How fast does the price react to news?</li>
          </ul>
        </section>
      </div>
    </Shell>
  );
}
