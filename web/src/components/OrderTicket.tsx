"use client";
import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { track } from "@/lib/clickstream";
import { cents, money } from "@/lib/format";
import { canAfford, clampPrice, marketablePrice, ticketMaths } from "@/lib/ticket";
import type { Action, Book, Order, Side } from "@/lib/types";

interface Props {
  marketId: string;
  book: Book | undefined;
  lastPrice: number | null;
  availableCents: number | undefined;
  yesShares: number;
  noShares: number;
  open: boolean;
}

function newKey(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : String(Date.now()) + Math.random();
}

export function OrderTicket({ marketId, book, lastPrice, availableCents, yesShares, noShares, open }: Props) {
  const qc = useQueryClient();
  const [side, setSide] = useState<Side>("YES");
  const [action, setAction] = useState<Action>("BUY");
  const [shares, setShares] = useState(10);
  const [userPrice, setUserPrice] = useState<number>(50);
  const [priceTouched, setPriceTouched] = useState(false);
  const [toast, setToast] = useState<{ text: string; tone: Side | "err" } | null>(null);
  const key = useRef(newKey());
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const suggested = marketablePrice(book, side, action, lastPrice);
  // Follow the best price until the trader types their own limit.
  const price = priceTouched ? userPrice : suggested;

  const held = side === "YES" ? yesShares : noShares;
  const maths = ticketMaths(action, price, shares);
  const affordable = availableCents == null ? true : canAfford(action, price, shares, availableCents, held);

  const place = useMutation({
    mutationFn: () => api.placeOrder({ marketId, side, action, price: clampPrice(price), shares }, key.current),
    onSuccess: (o: Order) => {
      key.current = newKey();
      track("order_submitted", { marketId, side, action, price, shares, status: o.status, filled: o.filledShares });
      const word = side === "YES" ? "Yes" : "No";
      const verb = action === "BUY" ? "Bought" : "Sold";
      let text: string;
      if (o.filledShares >= o.shares) text = `Filled: ${verb.toLowerCase()} ${o.shares} ${word} at ${cents(o.avgFillPrice ?? o.price)}.`;
      else if (o.filledShares > 0) text = `${verb} ${o.filledShares} of ${o.shares} ${word}; the rest waits at ${cents(o.price)}.`;
      else text = `Order placed: ${action === "BUY" ? "buy" : "sell"} ${o.shares} ${word} at ${cents(o.price)}, waiting to fill.`;
      showToast(text, side);
      setPriceTouched(false);
      qc.invalidateQueries({ queryKey: ["orders", marketId] });
      qc.invalidateQueries({ queryKey: ["portfolio"] });
      qc.invalidateQueries({ queryKey: ["live", marketId] });
    },
    onError: (e: unknown) => {
      // A network failure may have reached the server: keep the key so a retry is not a duplicate.
      if (!(e instanceof ApiError && e.status === 0)) key.current = newKey();
      track("order_submitted", { marketId, side, action, price, shares, status: "ERROR", error: e instanceof ApiError ? e.code : "UNKNOWN" });
      showToast(e instanceof Error ? e.message : "Order failed.", "err");
    },
  });

  function showToast(text: string, tone: Side | "err") {
    setToast({ text, tone });
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), 5000);
  }

  const sideBtn = (s: Side) => {
    const on = side === s;
    const c = s === "YES" ? "border-yes bg-yes-deep" : "border-no bg-no-deep";
    return `min-h-14 rounded-lg border font-semibold transition-colors ${on ? `${c} text-white` : "border-edge bg-transparent text-soft hover:text-white"}`;
  };
  const actBtn = (a: Action) =>
    `min-h-11 rounded-[7px] font-medium transition-colors ${action === a ? "bg-line text-white" : "text-soft hover:text-white"}`;

  const yesPx = marketablePrice(book, "YES", "BUY", lastPrice);
  const noPx = marketablePrice(book, "NO", "BUY", lastPrice);
  const word = side === "YES" ? "Yes" : "No";
  const accent = side === "YES" ? "bg-yes" : "bg-no";

  let submitLabel = `${action === "BUY" ? "Buy" : "Sell"} ${shares} ${word} at ${cents(clampPrice(price))}`;
  if (!open) submitLabel = "Market closed";
  else if (place.isPending) submitLabel = "Placing order…";
  else if (!affordable) submitLabel = action === "BUY" ? "Not enough funds" : `You hold ${held} ${word}`;

  return (
    <section aria-label="Order ticket" className="card flex flex-col gap-4">
      <div className="flex items-baseline justify-between gap-3">
        <h2 className="text-sm font-semibold">Place an order</h2>
        <span className="text-xs text-mute">
          Available <span className="font-mono text-ink" data-testid="ticket-available">{money(availableCents)}</span>
        </span>
      </div>

      <div role="group" aria-label="Buy or sell" className="grid grid-cols-2 gap-1 rounded-[10px] border border-line bg-bg p-1">
        <button type="button" data-testid="ticket-action-buy" aria-pressed={action === "BUY"} onClick={() => { setAction("BUY"); setPriceTouched(false); }} className={actBtn("BUY")}>
          Buy
        </button>
        <button type="button" data-testid="ticket-action-sell" aria-pressed={action === "SELL"} onClick={() => { setAction("SELL"); setPriceTouched(false); }} className={actBtn("SELL")}>
          Sell
        </button>
      </div>

      <div className="grid grid-cols-2 gap-2">
        <button type="button" data-testid="ticket-side-yes" aria-pressed={side === "YES"} onClick={() => { setSide("YES"); setPriceTouched(false); }} className={sideBtn("YES")}>
          Yes <span className="font-mono font-medium">{cents(yesPx)}</span>
        </button>
        <button type="button" data-testid="ticket-side-no" aria-pressed={side === "NO"} onClick={() => { setSide("NO"); setPriceTouched(false); }} className={sideBtn("NO")}>
          No <span className="font-mono font-medium">{cents(noPx)}</span>
        </button>
      </div>

      <div className="flex items-center justify-between gap-3">
        <span id="shares-label" className="text-xs text-mute">Shares</span>
        <div role="group" aria-labelledby="shares-label" className="flex items-center gap-2">
          <button type="button" aria-label="Fewer shares" data-testid="ticket-shares-dec" onClick={() => setShares((s) => Math.max(1, s - 5))} className="h-11 w-11 rounded-lg border border-edge bg-bg text-lg">
            −
          </button>
          <input
            aria-label="Number of shares"
            data-testid="ticket-shares"
            inputMode="numeric"
            className="h-11 w-20 rounded-lg border border-edge bg-bg text-center font-mono text-lg"
            value={shares}
            onChange={(e) => {
              const n = parseInt(e.target.value.replace(/\D/g, ""), 10);
              setShares(Number.isFinite(n) ? Math.min(10000, Math.max(1, n)) : 1);
            }}
          />
          <button type="button" aria-label="More shares" data-testid="ticket-shares-inc" onClick={() => setShares((s) => Math.min(10000, s + 5))} className="h-11 w-11 rounded-lg border border-edge bg-bg text-lg">
            +
          </button>
        </div>
      </div>

      <div className="flex items-center justify-between gap-3">
        <label htmlFor="ticket-price" className="text-xs text-mute">
          Limit price <span className="text-mute">({word}, 1–99¢)</span>
        </label>
        <div className="flex items-center gap-2">
          {priceTouched && (
            <button type="button" data-testid="ticket-price-reset" onClick={() => setPriceTouched(false)} className="text-xs text-mute underline hover:text-white">
              best {cents(suggested)}
            </button>
          )}
          <input
            id="ticket-price"
            data-testid="ticket-price"
            type="number"
            min={1}
            max={99}
            step={1}
            className="h-11 w-24 rounded-lg border border-edge bg-bg px-3 text-right font-mono text-lg"
            value={price}
            onChange={(e) => {
              setPriceTouched(true);
              setUserPrice(clampPrice(Number(e.target.value)));
            }}
          />
        </div>
      </div>

      <dl className="flex flex-col gap-2 text-[13px]">
        <div className="flex justify-between">
          <dt className="text-mute">{action === "BUY" ? "Cost" : "You receive"}</dt>
          <dd className="font-mono" data-testid="ticket-cost">{money(maths.costCents)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-mute">{action === "BUY" ? `Payout if ${word.toLowerCase()}` : `Payout given up if ${word.toLowerCase()}`}</dt>
          <dd className="font-mono" data-testid="ticket-payout">{money(maths.payoutCents)}</dd>
        </div>
        {action === "BUY" && (
          <div className="flex justify-between">
            <dt className="text-mute">Profit if right</dt>
            <dd className="font-mono" data-testid="ticket-profit">{money(maths.profitCents, { sign: true })}</dd>
          </div>
        )}
      </dl>

      <button
        type="button"
        data-testid="ticket-submit"
        disabled={!open || place.isPending || !affordable}
        onClick={() => place.mutate()}
        className={`btn min-h-12 font-semibold text-bg ${open ? accent : "bg-mute"} ${place.isPending ? "scale-[0.97] opacity-60" : ""}`}
      >
        {submitLabel}
      </button>

      <div
        role="status"
        aria-live="polite"
        data-testid="ticket-toast"
        className={`rounded-lg border bg-bg px-3 py-2.5 text-[13px] transition-all duration-200 ${toast ? "translate-y-0 opacity-100" : "-translate-y-2 opacity-0"} ${toast?.tone === "NO" || toast?.tone === "err" ? "border-no" : "border-yes"}`}
      >
        {toast?.text ?? "No order yet"}
      </div>

      <div className="flex justify-between gap-3 border-t border-line pt-3 text-[13px]">
        <span className="text-mute">Your position</span>
        <span data-testid="position" className="font-mono">
          {[yesShares ? `${yesShares} Yes` : "", noShares ? `${noShares} No` : ""].filter(Boolean).join(" · ") || "None yet"}
        </span>
      </div>
    </section>
  );
}
