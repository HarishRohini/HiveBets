import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "./api";

function respond(status: number, body?: unknown, raw?: string) {
  const text = raw ?? (body === undefined ? "" : JSON.stringify(body));
  return vi.fn().mockResolvedValue(new Response(status === 204 ? null : text, { status }));
}

afterEach(() => vi.unstubAllGlobals());

describe("api client", () => {
  it("sends credentials, JSON body and the Idempotency-Key header", async () => {
    const f = respond(201, { id: "o1" });
    vi.stubGlobal("fetch", f);
    const o = await api.placeOrder({ marketId: "m1", side: "YES", action: "BUY", price: 60, shares: 10 }, "key-1");
    expect(o).toEqual({ id: "o1" });
    const [url, init] = f.mock.calls[0];
    expect(url).toBe("http://localhost:8080/api/orders");
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("include");
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe("key-1");
    expect(new Headers(init.headers).get("Content-Type")).toBe("application/json");
    expect(JSON.parse(init.body)).toEqual({ marketId: "m1", side: "YES", action: "BUY", price: 60, shares: 10 });
  });

  it("builds query strings and skips empty values", async () => {
    const f = respond(200, []);
    vi.stubGlobal("fetch", f);
    await api.orders("OPEN", undefined);
    expect(f.mock.calls[0][0]).toBe("http://localhost:8080/api/orders?status=OPEN");
  });

  it("returns undefined for 204", async () => {
    vi.stubGlobal("fetch", respond(204));
    await expect(api.logout()).resolves.toBeUndefined();
  });

  it("surfaces the server's {error, message}", async () => {
    vi.stubGlobal("fetch", respond(409, { error: "INSUFFICIENT_FUNDS", message: "Not enough available cash for this order." }));
    const e = await api.portfolio().catch((x) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(e.status).toBe(409);
    expect(e.code).toBe("INSUFFICIENT_FUNDS");
    expect(e.message).toBe("Not enough available cash for this order.");
  });

  it("maps a bare 401 to a sign-in message", async () => {
    vi.stubGlobal("fetch", respond(401));
    const e = await api.me().catch((x) => x);
    expect(e.code).toBe("HTTP_401");
    expect(e.message).toBe("Sign in first.");
  });

  it("maps a bare 429 to RATE_LIMITED", async () => {
    vi.stubGlobal("fetch", respond(429, undefined, "not json"));
    const e = await api.markets().catch((x) => x);
    expect(e.code).toBe("RATE_LIMITED");
    expect(e.message).toBe("Too many requests, slow down.");
  });

  it("maps a bare 403", async () => {
    vi.stubGlobal("fetch", respond(403));
    const e = await api.analyticsSummary().catch((x) => x);
    expect(e.message).toBe("Not allowed.");
  });

  it("turns a network failure into NETWORK with status 0", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("fetch failed")));
    const e = await api.stats().catch((x) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(e.status).toBe(0);
    expect(e.code).toBe("NETWORK");
  });
});
