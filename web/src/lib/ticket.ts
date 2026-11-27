import type { Action, Book, Side } from "./types";

export const MIN_PRICE = 1;
export const MAX_PRICE = 99;

export function clampPrice(p: number): number {
  if (!Number.isFinite(p)) return 50;
  return Math.min(MAX_PRICE, Math.max(MIN_PRICE, Math.round(p)));
}

/** Converts a price between YES terms and the given side's own terms. */
export function toSidePrice(yesPrice: number, side: Side): number {
  return side === "YES" ? yesPrice : 100 - yesPrice;
}

/**
 * The price that would fill right away, in the side's own terms.
 * Buying YES takes the best YES ask; buying NO takes the best YES bid (NO at 100 - bid).
 * Selling YES hits the best bid; selling NO hits the best ask.
 */
export function marketablePrice(book: Book | undefined, side: Side, action: Action, last: number | null): number {
  const bestBid = book?.bids?.[0]?.price;
  const bestAsk = book?.asks?.[0]?.price;
  let yes: number | undefined;
  if (side === "YES") yes = action === "BUY" ? bestAsk : bestBid;
  else yes = action === "BUY" ? bestBid : bestAsk;
  if (yes == null) yes = last ?? 50;
  return clampPrice(toSidePrice(yes, side));
}

export interface TicketMaths {
  /** Cash reserved now for a buy, or cash received for a sell, in cents. */
  costCents: number;
  /** What the shares pay if the side happens, in cents. */
  payoutCents: number;
  /** For a buy: payout minus cost. For a sell: proceeds minus the payout given up (negative or zero). */
  profitCents: number;
}

export function ticketMaths(action: Action, price: number, shares: number): TicketMaths {
  const p = clampPrice(price);
  const n = Math.max(0, Math.floor(shares));
  const cost = p * n;
  const payout = 100 * n;
  if (action === "BUY") return { costCents: cost, payoutCents: payout, profitCents: payout - cost };
  return { costCents: cost, payoutCents: payout, profitCents: cost - payout };
}

export function canAfford(action: Action, price: number, shares: number, availableCents: number, heldShares: number): boolean {
  if (shares <= 0) return false;
  if (action === "BUY") return clampPrice(price) * shares <= availableCents;
  return shares <= heldShares;
}
