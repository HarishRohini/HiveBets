"use client";
import Link from "next/link";
import { useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { cents, countdown, moneyShort, number } from "@/lib/format";
import { useNow, usePollInterval, useTopics } from "@/lib/hooks";
import type { Market } from "@/lib/types";
import { PageHeader, Shell } from "@/components/Nav";
import { ErrorNote, KindPill, Sparkline } from "@/components/ui";

type Filter = "ALL" | "SIMULATED" | "REAL" | "SOON";
const FILTERS: { id: Filter; label: string }[] = [
  { id: "ALL", label: "All" },
  { id: "SIMULATED", label: "Simulated" },
  { id: "REAL", label: "Real world" },
  { id: "SOON", label: "Closing soon" },
];

export function MarketsList() {
  const [filter, setFilter] = useState<Filter>("ALL");
  const params = useSearchParams();
  const denied = params.get("denied");
  const poll = usePollInterval();
  const qc = useQueryClient();
  const markets = useQuery({ queryKey: ["markets"], queryFn: () => api.markets("ALL"), refetchInterval: poll || 30000 });
  const live = useQuery({ queryKey: ["live-markets"], queryFn: api.liveMarkets, refetchInterval: poll || 15000 });
  const now = useNow();

  useTopics(["markets"], (m) => {
    if (m.type.startsWith("market.")) qc.invalidateQueries({ queryKey: ["markets"] });
    qc.invalidateQueries({ queryKey: ["live-markets"] });
  });

  const { open, resolved } = useMemo(() => {
    const all = markets.data ?? [];
    let open = all.filter((m) => m.status !== "RESOLVED");
    if (filter === "SIMULATED") open = open.filter((m) => m.kind === "SIMULATED");
    if (filter === "REAL") open = open.filter((m) => m.kind === "REAL");
    if (filter === "SOON") open = open.filter((m) => m.status === "OPEN" && new Date(m.closeAt).getTime() - now < 5 * 60_000);
    open = [...open].sort((a, b) => new Date(a.closeAt).getTime() - new Date(b.closeAt).getTime());
    const resolved = all
      .filter((m) => m.status === "RESOLVED")
      .sort((a, b) => new Date(b.resolvedAt ?? b.closeAt).getTime() - new Date(a.resolvedAt ?? a.closeAt).getTime())
      .slice(0, 5);
    return { open, resolved };
    // `now` changes every second; only "Closing soon" depends on it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [markets.data, filter, filter === "SOON" ? now : 0]);

  return (
    <Shell>
      {denied && (
        <p role="alert" data-testid="denied-banner" className="rounded-lg border border-no px-4 py-3 text-soft">
          {denied === "analytics" ? "Analytics" : "That page"} is for admins only.
        </p>
      )}
      <PageHeader title="Open markets" sub="Price is the crowd's estimate of the chance the answer is yes.">
        {FILTERS.map((f) => (
          <button
            key={f.id}
            type="button"
            data-testid={`filter-${f.id.toLowerCase()}`}
            aria-pressed={filter === f.id}
            onClick={() => setFilter(f.id)}
            className={`min-h-11 rounded-full border px-4 ${filter === f.id ? "border-yes bg-yes-deep font-medium text-white" : "border-edge text-soft hover:text-white"}`}
          >
            {f.label}
          </button>
        ))}
      </PageHeader>

      <ErrorNote error={markets.error} />

      <section aria-label="Open markets" className="grid gap-5 [grid-template-columns:repeat(auto-fill,minmax(300px,1fr))]">
        {markets.isLoading && <p className="text-mute">Loading markets…</p>}
        {!markets.isLoading && open.length === 0 && (
          <p className="text-mute" data-testid="no-markets">
            No markets here yet. An admin can open one from the Admin page.
          </p>
        )}
        {open.map((m) => (
          <MarketCard key={m.id} m={m} now={now} spark={live.data?.[m.id]?.spark ?? []} livePrice={live.data?.[m.id]?.lastPrice} liveVol={live.data?.[m.id]?.volumeCents} liveTraders={live.data?.[m.id]?.traderCount} />
        ))}
      </section>

      <section aria-label="Just resolved" className="card">
        <h2 className="mb-2 text-sm font-semibold">Just resolved</h2>
        {resolved.length === 0 && <p className="border-t border-line py-3 text-[13px] text-mute">Nothing has resolved yet.</p>}
        {resolved.map((r) => (
          <Link
            key={r.id}
            href={`/markets/${r.id}`}
            data-testid="resolved-row"
            className="flex flex-wrap items-baseline gap-x-5 gap-y-1.5 border-t border-line py-3 hover:bg-raised/40"
          >
            <span className="min-w-0 flex-[1_1_320px] text-[15px]">{r.question}</span>
            <span className="text-xs text-mute">
              last price <span className="font-mono text-soft">{cents(r.lastPrice)}</span>
            </span>
            <span className={`w-14 flex-none rounded-md py-0.5 text-center text-xs font-semibold text-bg ${r.outcome === "YES" ? "bg-yes" : "bg-no"}`}>
              {r.outcome === "YES" ? "Yes" : "No"}
            </span>
          </Link>
        ))}
      </section>
    </Shell>
  );
}

function MarketCard({ m, now, spark, livePrice, liveVol, liveTraders }: { m: Market; now: number; spark: number[]; livePrice?: number | null; liveVol?: number; liveTraders?: number }) {
  const price = livePrice ?? m.lastPrice;
  const left = new Date(m.closeAt).getTime() - now;
  const closesLabel = m.status === "CLOSED" || left <= 0 ? "Closed" : countdown(left);
  return (
    <article data-testid="market-card" data-market-id={m.id} className="flex flex-col gap-4 rounded-xl border border-line bg-card p-5">
      <div className="flex justify-between gap-2 text-xs font-medium">
        <KindPill kind={m.kind} />
        <span className="px-2.5 py-1 font-mono text-amber" data-testid="time-left">
          {closesLabel}
        </span>
      </div>
      <h2 className="min-h-[66px] font-display text-lg font-medium leading-tight" data-testid="market-question">
        {m.question}
      </h2>
      <div className="flex items-end justify-between gap-4">
        <div>
          <div className="font-mono text-[32px] leading-none text-yes" data-testid="market-price">
            {cents(price)}
          </div>
          <div className="mt-1.5 text-xs text-mute">chance of yes</div>
        </div>
        <Sparkline points={spark.length ? spark : price != null ? [price, price] : []} />
      </div>
      <div className="flex items-center justify-between gap-3 border-t border-line pt-3 text-xs text-mute">
        <span>
          {moneyShort(liveVol ?? m.volumeCents)} traded · {number(liveTraders ?? m.traderCount)} traders
        </span>
        <Link href={`/markets/${m.id}`} data-testid="market-open" className="btn btn-primary text-sm">
          <svg aria-hidden="true" viewBox="0 0 16 16" className="h-3.5 w-3.5 fill-current">
            <path d="M4 2.5 L13 8 L4 13.5 Z" />
          </svg>
          Trade
        </Link>
      </div>
    </article>
  );
}
