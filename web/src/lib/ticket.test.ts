import { describe, expect, it } from "vitest";
import { canAfford, clampPrice, marketablePrice, ticketMaths } from "./ticket";

const book = { bids: [{ price: 60, shares: 100 }], asks: [{ price: 63, shares: 50 }] };

describe("ticket maths", () => {
  it("buy costs price x shares and pays 100 per share", () => {
    expect(ticketMaths("BUY", 63, 10)).toEqual({ costCents: 630, payoutCents: 1000, profitCents: 370 });
  });
  it("sell receives price x shares and gives up the payout", () => {
    expect(ticketMaths("SELL", 70, 10)).toEqual({ costCents: 700, payoutCents: 1000, profitCents: -300 });
  });
  it("clamps prices to 1-99", () => {
    expect(clampPrice(0)).toBe(1);
    expect(clampPrice(120)).toBe(99);
    expect(clampPrice(NaN)).toBe(50);
  });
});

describe("marketable price", () => {
  it("buying YES takes the best ask", () => expect(marketablePrice(book, "YES", "BUY", null)).toBe(63));
  it("buying NO takes 100 minus the best bid", () => expect(marketablePrice(book, "NO", "BUY", null)).toBe(40));
  it("selling YES hits the best bid", () => expect(marketablePrice(book, "YES", "SELL", null)).toBe(60));
  it("selling NO hits 100 minus the best ask", () => expect(marketablePrice(book, "NO", "SELL", null)).toBe(37));
  it("falls back to the last price, then 50", () => {
    expect(marketablePrice({ bids: [], asks: [] }, "NO", "BUY", 70)).toBe(30);
    expect(marketablePrice(undefined, "YES", "BUY", null)).toBe(50);
  });
});

describe("affordability", () => {
  it("checks cash for buys and shares for sells", () => {
    expect(canAfford("BUY", 50, 10, 500, 0)).toBe(true);
    expect(canAfford("BUY", 50, 11, 500, 0)).toBe(false);
    expect(canAfford("SELL", 50, 5, 0, 5)).toBe(true);
    expect(canAfford("SELL", 50, 6, 0, 5)).toBe(false);
  });
});
