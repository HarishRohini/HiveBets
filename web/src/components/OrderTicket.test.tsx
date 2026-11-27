// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

const placeOrder = vi.fn();
vi.mock("@/lib/api", async (orig) => {
  const real = await orig<typeof import("@/lib/api")>();
  return { ...real, api: { ...real.api, placeOrder: (...a: unknown[]) => placeOrder(...a) } };
});
vi.mock("@/lib/clickstream", () => ({ track: vi.fn() }));

import { OrderTicket } from "./OrderTicket";
import { ApiError } from "@/lib/api";

const book = { bids: [{ price: 60, shares: 100 }], asks: [{ price: 63, shares: 50 }] };

function renderTicket(props: Partial<Parameters<typeof OrderTicket>[0]> = {}) {
  const qc = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  const wrap = ({ children }: { children: ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  return render(
    <OrderTicket marketId="m1" book={book} lastPrice={61} availableCents={100_000} yesShares={0} noShares={0} open {...props} />,
    { wrapper: wrap },
  );
}

const id = (t: string) => screen.getByTestId(t);
const text = (t: string) => id(t).textContent;

beforeEach(() => {
  placeOrder.mockReset();
});
afterEach(cleanup);

describe("OrderTicket", () => {
  it("defaults to buying YES at the best ask, 10 shares", () => {
    renderTicket();
    expect((id("ticket-price") as HTMLInputElement).value).toBe("63");
    expect((id("ticket-shares") as HTMLInputElement).value).toBe("10");
    expect(text("ticket-cost")).toBe("$6.30");
    expect(text("ticket-payout")).toBe("$10.00");
    expect(text("ticket-profit")).toBe("+$3.70");
    expect(text("ticket-submit")).toBe("Buy 10 Yes at 63¢");
    expect(id("ticket-side-yes").getAttribute("aria-pressed")).toBe("true");
  });

  it("switching to NO prices off the best bid (100 - 60)", () => {
    renderTicket();
    fireEvent.click(id("ticket-side-no"));
    expect((id("ticket-price") as HTMLInputElement).value).toBe("40");
    expect(text("ticket-submit")).toBe("Buy 10 No at 40¢");
    expect(id("ticket-side-no").getAttribute("aria-pressed")).toBe("true");
  });

  it("selling YES hits the best bid and shows what you receive", () => {
    renderTicket({ yesShares: 20 });
    fireEvent.click(id("ticket-action-sell"));
    expect((id("ticket-price") as HTMLInputElement).value).toBe("60");
    expect(text("ticket-cost")).toBe("$6.00");
    expect(screen.queryByTestId("ticket-profit")).toBeNull();
    expect(text("ticket-submit")).toBe("Sell 10 Yes at 60¢");
  });

  it("refuses to sell shares you do not hold", () => {
    renderTicket({ yesShares: 3 });
    fireEvent.click(id("ticket-action-sell"));
    expect(text("ticket-submit")).toBe("You hold 3 Yes");
    expect((id("ticket-submit") as HTMLButtonElement).disabled).toBe(true);
  });

  it("refuses buys that cost more than available cash", () => {
    renderTicket({ availableCents: 500 });
    expect(text("ticket-submit")).toBe("Not enough funds");
    expect((id("ticket-submit") as HTMLButtonElement).disabled).toBe(true);
  });

  it("the share stepper moves in fives and never goes below one", () => {
    renderTicket();
    fireEvent.click(id("ticket-shares-inc"));
    expect((id("ticket-shares") as HTMLInputElement).value).toBe("15");
    for (let i = 0; i < 5; i++) fireEvent.click(id("ticket-shares-dec"));
    expect((id("ticket-shares") as HTMLInputElement).value).toBe("1");
  });

  it("a typed limit price sticks, is clamped, and can be reset to the best price", () => {
    renderTicket();
    fireEvent.change(id("ticket-price"), { target: { value: "150" } });
    expect((id("ticket-price") as HTMLInputElement).value).toBe("99");
    fireEvent.change(id("ticket-price"), { target: { value: "55" } });
    expect(text("ticket-cost")).toBe("$5.50");
    fireEvent.click(id("ticket-price-reset"));
    expect((id("ticket-price") as HTMLInputElement).value).toBe("63");
  });

  it("submits the order with an idempotency key and shows the fill", async () => {
    placeOrder.mockResolvedValue({ id: "o1", shares: 10, filledShares: 10, price: 63, avgFillPrice: 62, status: "FILLED" });
    renderTicket();
    fireEvent.change(id("ticket-price"), { target: { value: "64" } });
    fireEvent.click(id("ticket-submit"));
    await waitFor(() => expect(text("ticket-toast")).toBe("Filled: bought 10 Yes at 62¢."));
    const [order, key] = placeOrder.mock.calls[0];
    expect(order).toEqual({ marketId: "m1", side: "YES", action: "BUY", price: 64, shares: 10 });
    expect(typeof key).toBe("string");
    expect(key.length).toBeGreaterThan(8);
  });

  it("uses a new key for the next order but reuses it after a network failure", async () => {
    placeOrder
      .mockRejectedValueOnce(new ApiError(0, "NETWORK", "Can't reach the server."))
      .mockResolvedValueOnce({ id: "o1", shares: 10, filledShares: 0, price: 63, avgFillPrice: null, status: "OPEN" })
      .mockResolvedValueOnce({ id: "o2", shares: 10, filledShares: 0, price: 63, avgFillPrice: null, status: "OPEN" });
    renderTicket();
    fireEvent.click(id("ticket-submit"));
    await waitFor(() => expect(text("ticket-toast")).toBe("Can't reach the server."));
    fireEvent.click(id("ticket-submit"));
    await waitFor(() => expect(text("ticket-toast")).toMatch(/^Order placed: buy 10 Yes at 63¢/));
    fireEvent.click(id("ticket-submit"));
    await waitFor(() => expect(placeOrder).toHaveBeenCalledTimes(3));
    const keys = placeOrder.mock.calls.map((c) => c[1]);
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[1]);
  });

  it("shows the server's rejection message", async () => {
    placeOrder.mockImplementation(async () => { throw new ApiError(409, "MARKET_CLOSED", "This market is closed for trading."); });
    renderTicket();
    fireEvent.click(id("ticket-submit"));
    await waitFor(() => expect(text("ticket-toast")).toBe("This market is closed for trading."));
  });

  it("is disabled when the market is closed", () => {
    renderTicket({ open: false });
    expect(text("ticket-submit")).toBe("Market closed");
    expect((id("ticket-submit") as HTMLButtonElement).disabled).toBe(true);
  });

  it("shows the position", () => {
    renderTicket({ yesShares: 10, noShares: 5 });
    expect(text("position")).toBe("10 Yes · 5 No");
  });
});
