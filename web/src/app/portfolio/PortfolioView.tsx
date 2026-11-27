"use client";
import Link from "next/link";
import { useMemo } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { cents, countdown, money } from "@/lib/format";
import { useNow, usePollInterval, useTopics } from "@/lib/hooks";
import { PageHeader, Shell } from "@/components/Nav";
import { LineChart } from "@/components/LineChart";
import { ErrorNote, Stat } from "@/components/ui";

export function PortfolioView() {
  const qc = useQueryClient();
  const poll = usePollInterval();
  const now = useNow();
  const portfolio = useQuery({ queryKey: ["portfolio"], queryFn: api.portfolio, refetchInterval: poll || 30000 });
  const orders = useQuery({ queryKey: ["orders", "all-open"], queryFn: () => api.orders("OPEN"), refetchInterval: poll || 30000 });
  const equity = useQuery({ queryKey: ["equity"], queryFn: api.equity, refetchInterval: 30000 });
  const markets = useQuery({ queryKey: ["markets"], queryFn: () => api.markets("ALL") });

  useTopics(["user"], () => {
    qc.invalidateQueries({ queryKey: ["portfolio"] });
    qc.invalidateQueries({ queryKey: ["orders", "all-open"] });
    qc.invalidateQueries({ queryKey: ["equity"] });
  });

  const cancel = useMutation({
    mutationFn: (id: string) => api.cancelOrder(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["orders", "all-open"] });
      qc.invalidateQueries({ queryKey: ["portfolio"] });
    },
  });

  const questions = useMemo(() => new Map((markets.data ?? []).map((m) => [m.id, m.question])), [markets.data]);
  const w = portfolio.data?.wallet;
  const inPositions = (portfolio.data?.positions ?? []).reduce((s, p) => s + p.valueCents, 0);
  const values = (equity.data ?? []).map((e) => e.equityCents);

  return (
    <Shell>
      <PageHeader title="Portfolio" sub="What you hold, what is waiting to fill, and how past markets paid out." />
      <ErrorNote error={portfolio.error} />

      <div className="flex flex-wrap gap-5">
        <dl className="grid min-w-0 flex-[1_1_420px] grid-cols-2 gap-5">
          <Stat label="Total value" value={money(w?.equityCents)} testid="wallet-equity" />
          <Stat label="Profit since joining" value={money(w?.profitCents, { sign: true })} tone={(w?.profitCents ?? 0) < 0 ? "no" : "yes"} testid="wallet-profit" />
          <Stat label="Available cash" value={money(w?.availableCents)} testid="wallet-available" />
          <Stat label="In open positions" value={money(inPositions)} testid="wallet-positions" />
        </dl>
        <section aria-label="Account value over time" className="card flex min-w-0 flex-[2_1_480px] flex-col gap-3">
          <h2 className="text-sm font-semibold">Account value, last hour</h2>
          <LineChart values={values} baseline={w?.startingCents ?? 100000} label="Account value over the last hour" />
          <div className="text-xs text-mute">
            <span className="mr-1.5 inline-block w-3.5 border-t-2 border-dashed border-edge align-middle" />
            Starting bankroll, {money(w?.startingCents ?? 100000)}
          </div>
        </section>
      </div>

      <section aria-label="Open positions" className="card">
        <h2 className="mb-3 text-sm font-semibold">Open positions</h2>
        <div className="relative overflow-x-auto">
          <table className="w-full min-w-[760px] border-collapse text-sm">
            <thead>
              <tr className="text-left text-xs text-mute">
                <th scope="col" className="py-2 pr-3 font-medium">Market</th>
                <th scope="col" className="p-2 font-medium">Holding</th>
                <th scope="col" className="p-2 text-right font-medium">Paid</th>
                <th scope="col" className="p-2 text-right font-medium">Now (YES)</th>
                <th scope="col" className="p-2 text-right font-medium">Value</th>
                <th scope="col" className="p-2 text-right font-medium">Profit so far</th>
                <th scope="col" className="p-2 font-medium">Closes in</th>
                <th scope="col" className="py-2 pl-3 font-medium"><span className="sr-only">Action</span></th>
              </tr>
            </thead>
            <tbody>
              {(portfolio.data?.positions ?? []).length === 0 && (
                <tr><td colSpan={8} className="border-t border-line py-3 text-[13px] text-mute">No open positions. Pick a market and place an order.</td></tr>
              )}
              {(portfolio.data?.positions ?? []).map((p) => {
                const left = new Date(p.closeAt).getTime() - now;
                return (
                  <tr key={p.marketId} data-testid="position-row" className="border-t border-line">
                    <td className="py-3 pr-3">{p.question}</td>
                    <td className="p-3 font-mono">
                      {p.yesShares > 0 && <span className="text-yes">{p.yesShares} Yes</span>}
                      {p.yesShares > 0 && p.noShares > 0 && " · "}
                      {p.noShares > 0 && <span className="text-no">{p.noShares} No</span>}
                    </td>
                    <td className="p-3 text-right font-mono">{money(p.yesCostCents + p.noCostCents)}</td>
                    <td className="p-3 text-right font-mono">{cents(p.lastPrice)}</td>
                    <td className="p-3 text-right font-mono">{money(p.valueCents)}</td>
                    <td className={`p-3 text-right font-mono ${p.profitCents < 0 ? "text-no" : "text-yes"}`}>{money(p.profitCents, { sign: true })}</td>
                    <td className="p-3 font-mono text-amber">{p.status === "OPEN" && left > 0 ? countdown(left) : "Closed"}</td>
                    <td className="py-1.5 pl-3 text-right">
                      <Link href={`/markets/${p.marketId}`} data-testid="position-trade" className="btn btn-ghost">Trade</Link>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>

      <div className="flex flex-wrap items-start gap-5">
        <section aria-label="Orders waiting to fill" className="card min-w-0 flex-[1_1_480px]">
          <h2 className="mb-1 text-sm font-semibold">Orders waiting to fill</h2>
          <p className="mb-2 text-[13px] text-mute">Cash for these is set aside until they fill or you cancel.</p>
          {(orders.data ?? []).length === 0 && <p className="border-t border-line py-3 text-[13px] text-mute">None.</p>}
          {(orders.data ?? []).map((o) => (
            <div key={o.id} data-testid="order-row" className="flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-line py-3">
              <div className="min-w-0 flex-[1_1_240px]">
                <Link href={`/markets/${o.marketId}`} className="hover:underline">{questions.get(o.marketId) ?? "Market"}</Link>
                <div className="mt-1 font-mono text-xs text-soft">
                  {o.action === "BUY" ? "Buy" : "Sell"} {o.shares} {o.side === "YES" ? "Yes" : "No"} at {cents(o.price)} · {o.filledShares} of {o.shares} filled
                  {o.action === "BUY" ? ` · ${money(o.reservedCents)} set aside` : ""}
                </div>
              </div>
              <button type="button" data-testid="order-cancel" disabled={cancel.isPending} onClick={() => cancel.mutate(o.id)} className="btn btn-ghost">
                Cancel
              </button>
            </div>
          ))}
          <ErrorNote error={cancel.error} />
        </section>

        <section aria-label="Resolved markets" className="card min-w-0 flex-[1_1_480px]">
          <h2 className="mb-1 text-sm font-semibold">Resolved markets</h2>
          <p className="mb-2 text-[13px] text-mute">Winning shares paid $1.00 each; losing shares paid nothing.</p>
          {(portfolio.data?.resolved ?? []).length === 0 && <p className="border-t border-line py-3 text-[13px] text-mute">None yet.</p>}
          {(portfolio.data?.resolved ?? []).map((h) => (
            <div key={h.marketId} data-testid="resolved-holding" className="flex flex-wrap items-baseline gap-x-4 gap-y-1.5 border-t border-line py-3">
              <span className="min-w-0 flex-[1_1_240px]">{h.question}</span>
              <span className="text-xs text-mute">
                held {[h.yesShares ? `${h.yesShares} Yes` : "", h.noShares ? `${h.noShares} No` : ""].filter(Boolean).join(" + ") || "nothing"}, resolved {h.outcome === "YES" ? "Yes" : "No"}
              </span>
              <span className={`w-20 flex-none text-right font-mono ${h.profitCents < 0 ? "text-no" : "text-yes"}`}>{money(h.profitCents, { sign: true })}</span>
            </div>
          ))}
        </section>
      </div>
    </Shell>
  );
}
