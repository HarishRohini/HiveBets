"use client";
import type { Book } from "@/lib/types";
import { cents, number } from "@/lib/format";

/** YES-terms book: sellers (asks) above, buyers (bids) below, best prices nearest the middle. */
export function OrderBook({ book }: { book: Book | undefined }) {
  const asks = (book?.asks ?? []).slice(0, 6);
  const bids = (book?.bids ?? []).slice(0, 6);
  const max = Math.max(1, ...asks.map((a) => a.shares), ...bids.map((b) => b.shares));
  const row = (p: number, q: number, tone: "ask" | "bid", testid: string) => (
    <div key={tone + p} data-testid={testid} className="relative flex justify-between px-2 py-[5px] font-mono text-[13px]">
      <span
        aria-hidden="true"
        className={`absolute bottom-0.5 right-0 top-0.5 rounded-[3px] opacity-[0.16] transition-[width] duration-500 ${tone === "ask" ? "bg-no" : "bg-yes"}`}
        style={{ width: `${Math.round((q / max) * 100)}%` }}
      />
      <span className={`relative ${tone === "ask" ? "text-no" : "text-yes"}`}>{cents(p)}</span>
      <span className="relative">{number(q)}</span>
    </div>
  );
  return (
    <section aria-label="Order book" className="card">
      <div className="mb-3 flex items-baseline justify-between">
        <h2 className="text-sm font-semibold">Order book</h2>
        <span className="text-xs text-mute">YES price · Shares</span>
      </div>
      {asks.length === 0 && <p className="px-2 py-1 text-[13px] text-mute">No sellers yet.</p>}
      {[...asks].reverse().map((a) => row(a.price, a.shares, "ask", "book-ask"))}
      <div className="my-2 flex justify-between border-y border-line p-2 text-xs text-mute">
        <span>Sellers above, buyers below</span>
        <span>
          bid <span className="font-mono text-yes" data-testid="best-bid">{cents(bids[0]?.price)}</span> · ask{" "}
          <span className="font-mono text-no" data-testid="best-ask">{cents(asks[0]?.price)}</span>
        </span>
      </div>
      {bids.map((b) => row(b.price, b.shares, "bid", "book-bid"))}
      {bids.length === 0 && <p className="px-2 py-1 text-[13px] text-mute">No buyers yet.</p>}
    </section>
  );
}
