"use client";
import Link from "next/link";
import { useMemo } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { cents, clockTime, countdown, money, moneyShort, number, timeAgo } from "@/lib/format";
import { useMe, useNow, usePollInterval, useTopics } from "@/lib/hooks";
import type { Book, Headline, LiveMarket, Market, Trade } from "@/lib/types";
import { Shell } from "@/components/Nav";
import { addTradeToCandles, PriceChart } from "@/components/PriceChart";
import { OrderBook } from "@/components/OrderBook";
import { OrderTicket } from "@/components/OrderTicket";
import { ErrorNote, KindPill, SourceLabel } from "@/components/ui";

const EMPTY_LIVE = (id: string): LiveMarket => ({ marketId: id, lastPrice: null, book: { bids: [], asks: [] }, candles: [], trades: [], volumeCents: 0, traderCount: 0, news: [] });

export function MarketView({ id }: { id: string }) {
  const qc = useQueryClient();
  const poll = usePollInterval();
  const now = useNow();
  const me = useMe();

  const market = useQuery({ queryKey: ["market", id], queryFn: () => api.market(id), refetchInterval: poll || 30000 });
  const live = useQuery({
    queryKey: ["live", id],
    queryFn: () => api.liveMarket(id).catch(() => EMPTY_LIVE(id)),
    refetchInterval: poll,
  });
  const book = useQuery({ queryKey: ["book", id], queryFn: () => api.book(id), refetchInterval: poll });
  const news = useQuery({ queryKey: ["news", id], queryFn: () => api.news(id, 30), refetchInterval: poll || 60000 });
  const orders = useQuery({ queryKey: ["orders", id], queryFn: () => api.orders("OPEN", id), enabled: !!me.data });
  const portfolio = useQuery({ queryKey: ["portfolio"], queryFn: api.portfolio, enabled: !!me.data });

  useTopics([`market:${id}`], (m) => {
    const data = m.data as Record<string, unknown>;
    switch (m.type) {
      case "trade.executed": {
        const t = data as unknown as Trade;
        qc.setQueryData<LiveMarket>(["live", id], (old) => {
          const cur = old ?? EMPTY_LIVE(id);
          if (cur.trades.some((x) => x.id === t.id)) return cur;
          return {
            ...cur,
            lastPrice: t.price,
            trades: [t, ...cur.trades].slice(0, 50),
            candles: addTradeToCandles(cur.candles, t.price, t.shares, t.executedAt),
            volumeCents: cur.volumeCents + t.price * t.shares,
          };
        });
        qc.invalidateQueries({ queryKey: ["market", id] });
        break;
      }
      case "book.changed": {
        const b = { bids: (data.bids as Book["bids"]) ?? [], asks: (data.asks as Book["asks"]) ?? [] };
        qc.setQueryData<Book>(["book", id], b);
        qc.setQueryData<LiveMarket>(["live", id], (old) => ({ ...(old ?? EMPTY_LIVE(id)), book: b }));
        break;
      }
      case "news.published": {
        const h = data as unknown as Headline;
        qc.setQueryData<Headline[]>(["news", id], (old) => (old?.some((x) => x.id === h.id) ? old : [h, ...(old ?? [])].slice(0, 30)));
        break;
      }
      default:
        if (m.type.startsWith("market.")) {
          qc.invalidateQueries({ queryKey: ["market", id] });
          qc.invalidateQueries({ queryKey: ["portfolio"] });
          qc.invalidateQueries({ queryKey: ["orders", id] });
        }
    }
  });
  useTopics(me.data ? ["user"] : [], () => {
    qc.invalidateQueries({ queryKey: ["orders", id] });
    qc.invalidateQueries({ queryKey: ["portfolio"] });
  });

  const cancel = useMutation({
    mutationFn: (orderId: string) => api.cancelOrder(orderId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["orders", id] });
      qc.invalidateQueries({ queryKey: ["portfolio"] });
    },
  });

  const m: Market | undefined = market.data;
  const lv = live.data;
  const currentBook = book.data ?? lv?.book;
  const lastPrice = lv?.lastPrice ?? m?.lastPrice ?? null;
  const headlines = useMemo(() => {
    const merged = new Map<string, Headline>();
    for (const h of [...(news.data ?? []), ...(lv?.news ?? [])]) merged.set(h.id, h);
    return [...merged.values()].sort((a, b) => b.publishedAt.localeCompare(a.publishedAt));
  }, [news.data, lv?.news]);
  const position = portfolio.data?.positions.find((p) => p.marketId === id);
  const resolvedHolding = portfolio.data?.resolved.find((r) => r.marketId === id);

  if (market.error) {
    return (
      <Shell>
        <Link href="/markets" className="btn btn-ghost self-start">← All markets</Link>
        <ErrorNote error={market.error} />
      </Shell>
    );
  }

  const closeMs = m ? new Date(m.closeAt).getTime() - now : 0;
  const isOpen = !!m && m.status === "OPEN" && closeMs > 0;
  const closesLabel = !m ? "" : m.status === "RESOLVED" ? "Resolved" : !isOpen ? "Closed" : `Closes in ${countdown(closeMs)}`;
  const prevPrice = lv?.candles && lv.candles.length > 1 ? lv.candles[lv.candles.length - 2].c : null;
  const moveLabel = lastPrice == null || prevPrice == null ? "" : lastPrice > prevPrice ? "rising" : lastPrice < prevPrice ? "falling" : "steady";

  return (
    <Shell>
      <Link href="/markets" data-testid="back-to-markets" className="btn btn-ghost self-start">
        <svg aria-hidden="true" viewBox="0 0 16 16" className="h-4 w-4 fill-none stroke-current" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
          <path d="M10 3 L5 8 L10 13" />
        </svg>
        All markets
      </Link>

      {m?.status === "RESOLVED" && (
        <div role="status" data-testid="resolved-banner" className={`flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl border bg-card px-5 py-4 ${m.outcome === "YES" ? "border-yes" : "border-no"}`}>
          <span className={`rounded-md px-3.5 py-1 font-semibold text-bg ${m.outcome === "YES" ? "bg-yes" : "bg-no"}`}>Resolved: {m.outcome === "YES" ? "Yes" : "No"}</span>
          <span>
            {resolvedHolding
              ? resolvedHolding.payoutCents > 0
                ? `Your winning shares paid out ${money(resolvedHolding.payoutCents)}.`
                : "Your shares were on the losing side and paid nothing."
              : portfolio.isFetching
                ? "Settling payouts…"
                : "Winning shares paid $1.00 each. You held none."}
          </span>
        </div>
      )}

      <header className="flex flex-wrap items-end justify-between gap-x-10 gap-y-5">
        <div className="flex min-w-0 flex-[999_1_520px] flex-col gap-3">
          <div className="flex flex-wrap gap-2 text-xs font-medium">
            {m && <KindPill kind={m.kind} />}
            <span className="rounded-full bg-no-deep px-2.5 py-1 font-mono text-amber" data-testid="time-left">
              {closesLabel}
            </span>
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 text-mute">
              <span className={`inline-block h-2 w-2 rounded-full bg-yes ${isOpen ? "animate-pulse" : "opacity-30"}`} />
              {isOpen ? "Live" : "Trading stopped"}
            </span>
          </div>
          <h1 className="font-display text-[26px] font-semibold leading-tight tracking-tight sm:text-[32px]" data-testid="market-question">
            {m?.question ?? "Loading…"}
          </h1>
        </div>
        <dl className="flex flex-wrap gap-x-9 gap-y-3">
          <div>
            <dt className="text-xs text-mute">YES price</dt>
            <dd className="mt-0.5 font-mono text-[28px] text-yes">
              <span data-testid="yes-price">{cents(lastPrice)}</span> <span className="text-[13px] text-soft">{moveLabel}</span>
            </dd>
          </div>
          <div>
            <dt className="text-xs text-mute">Volume</dt>
            <dd className="mt-0.5 font-mono text-[28px]" data-testid="market-volume">{moneyShort(Math.max(lv?.volumeCents ?? 0, m?.volumeCents ?? 0))}</dd>
          </div>
          <div>
            <dt className="text-xs text-mute">Traders</dt>
            <dd className="mt-0.5 font-mono text-[28px]" data-testid="market-traders">{number(Math.max(lv?.traderCount ?? 0, m?.traderCount ?? 0))}</dd>
          </div>
        </dl>
      </header>

      <div className="flex flex-wrap items-start gap-6">
        <div className="flex min-w-0 flex-[999_1_560px] flex-col gap-6">
          <section aria-label="Price chart" className="card">
            <h2 className="mb-4 text-sm font-semibold">YES price, live</h2>
            <PriceChart candles={lv?.candles ?? []} news={headlines} />
          </section>

          <section aria-label="News" className="card">
            <h2 className="mb-2 text-sm font-semibold">News</h2>
            {headlines.length === 0 && <p className="border-t border-line py-3 text-[13px] text-mute">No headlines yet.</p>}
            {headlines.map((h) => (
              <article key={h.id} data-testid="headline" data-source={h.source} className="flex flex-wrap items-baseline gap-x-4 gap-y-1.5 border-t border-line py-3.5">
                <span className="w-20 flex-none font-mono text-xs text-mute" title={clockTime(h.publishedAt)}>
                  {timeAgo(h.publishedAt, now)}
                </span>
                <p className="min-w-0 flex-[1_1_320px] text-[15px]" data-testid="headline-text">{h.text}</p>
                <SourceLabel h={h} />
              </article>
            ))}
          </section>

          <section aria-label="Your open orders" className="card">
            <h2 className="mb-1 text-sm font-semibold">Your orders waiting to fill</h2>
            <p className="mb-2 text-[13px] text-mute">Cash or shares for these are set aside until they fill or you cancel.</p>
            {(orders.data ?? []).length === 0 && <p className="border-t border-line py-3 text-[13px] text-mute">None.</p>}
            {(orders.data ?? []).map((o) => (
              <div key={o.id} data-testid="order-row" className="flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-line py-3">
                <span className="min-w-0 flex-[1_1_240px] font-mono text-[13px] text-soft">
                  {o.action === "BUY" ? "Buy" : "Sell"} {o.shares} {o.side === "YES" ? "Yes" : "No"} at {cents(o.price)} · {o.filledShares} of {o.shares} filled
                  {o.action === "BUY" ? ` · ${money(o.reservedCents)} set aside` : ""}
                </span>
                <button type="button" data-testid="order-cancel" disabled={cancel.isPending} onClick={() => cancel.mutate(o.id)} className="btn btn-ghost">
                  Cancel
                </button>
              </div>
            ))}
            <ErrorNote error={cancel.error} />
          </section>
        </div>

        <div className="flex min-w-0 flex-[1_1_340px] flex-col gap-6 max-[960px]:order-first">
          <OrderTicket
            marketId={id}
            book={currentBook}
            lastPrice={lastPrice}
            availableCents={portfolio.data?.wallet.availableCents}
            yesShares={position?.yesShares ?? 0}
            noShares={position?.noShares ?? 0}
            open={isOpen}
          />
          <OrderBook book={currentBook} />
          <section aria-label="Recent trades" className="card">
            <h2 className="mb-3 text-sm font-semibold">Recent trades</h2>
            {(lv?.trades ?? []).length === 0 && <p className="text-[13px] text-mute">No trades yet.</p>}
            {(lv?.trades ?? []).slice(0, 12).map((t) => {
              const you = t.takerUserId && t.takerUserId === me.data?.id;
              const side = t.takerSide ?? "YES";
              const px = t.takerPrice ?? (side === "YES" ? t.price : 100 - t.price);
              return (
                <div key={t.id} data-testid="trade-row" className={`-mx-2 flex justify-between gap-3 rounded-md px-2 py-1.5 text-[13px] ${you ? "bg-yes-deep" : ""}`}>
                  <span className="min-w-0 truncate">{you ? "You" : t.takerUsername ?? "trader"}</span>
                  <span className="whitespace-nowrap font-mono text-soft">
                    {t.takerAction === "SELL" ? "sold " : ""}
                    {t.shares} {side === "YES" ? "Yes" : "No"} @ {cents(px)}
                  </span>
                </div>
              );
            })}
          </section>
        </div>
      </div>
    </Shell>
  );
}
