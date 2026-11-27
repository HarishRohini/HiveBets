// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

class FakeSocket {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;
  static all: FakeSocket[] = [];
  readyState = FakeSocket.CONNECTING;
  sent: unknown[] = [];
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  constructor(public url: string) {
    FakeSocket.all.push(this);
  }
  send(s: string) {
    this.sent.push(JSON.parse(s));
  }
  close() {
    this.readyState = FakeSocket.CLOSED;
    this.onclose?.();
  }
  open() {
    this.readyState = FakeSocket.OPEN;
    this.onopen?.();
  }
  emit(m: unknown) {
    this.onmessage?.({ data: JSON.stringify(m) });
  }
}

async function freshSocket() {
  vi.resetModules();
  const { liveSocket } = await import("./ws");
  return liveSocket();
}

beforeEach(() => {
  FakeSocket.all = [];
  vi.stubGlobal("WebSocket", FakeSocket);
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("live socket", () => {
  it("connects to the gateway /ws and subscribes on open", async () => {
    const s = await freshSocket();
    s.subscribe("markets", () => {});
    s.subscribe("market:m1", () => {});
    expect(FakeSocket.all).toHaveLength(1);
    expect(FakeSocket.all[0].url).toBe("ws://localhost:8080/ws");
    FakeSocket.all[0].open();
    expect(FakeSocket.all[0].sent).toEqual([{ op: "subscribe", topics: ["markets", "market:m1"] }]);
    expect(s.connected).toBe(true);
  });

  it("routes messages by topic and reference-counts subscriptions", async () => {
    const s = await freshSocket();
    const a = vi.fn(), b = vi.fn();
    const offA = s.subscribe("leaderboard", a);
    const offB = s.subscribe("leaderboard", b);
    const ws = FakeSocket.all[0];
    ws.open();
    ws.emit({ topic: "leaderboard", type: "wallet.changed", data: { x: 1 } });
    ws.emit({ topic: "other", type: "x", data: {} });
    expect(a).toHaveBeenCalledTimes(1);
    expect(b).toHaveBeenCalledWith({ topic: "leaderboard", type: "wallet.changed", data: { x: 1 } });
    offA();
    expect(ws.sent).toHaveLength(1); // still one listener, no unsubscribe yet
    offB();
    expect(ws.sent.at(-1)).toEqual({ op: "unsubscribe", topics: ["leaderboard"] });
  });

  it("sends a subscribe for a new topic on an open socket", async () => {
    const s = await freshSocket();
    s.subscribe("markets", () => {});
    FakeSocket.all[0].open();
    s.subscribe("user", () => {});
    expect(FakeSocket.all[0].sent.at(-1)).toEqual({ op: "subscribe", topics: ["user"] });
  });

  it("reconnects with growing backoff and resubscribes everything", async () => {
    const s = await freshSocket();
    const status = vi.fn();
    s.onStatus(status);
    s.subscribe("markets", () => {});
    s.subscribe("user", () => {});
    FakeSocket.all[0].open();
    FakeSocket.all[0].close();
    expect(status).toHaveBeenLastCalledWith(false);

    vi.advanceTimersByTime(499);
    expect(FakeSocket.all).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FakeSocket.all).toHaveLength(2); // first retry after 500 ms

    FakeSocket.all[1].close(); // fails again before opening
    vi.advanceTimersByTime(999);
    expect(FakeSocket.all).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(FakeSocket.all).toHaveLength(3); // second retry after 1 s

    FakeSocket.all[2].open();
    expect(FakeSocket.all[2].sent).toEqual([{ op: "subscribe", topics: ["markets", "user"] }]);
    expect(status).toHaveBeenLastCalledWith(true);
  });

  it("does not reconnect once nothing is subscribed", async () => {
    const s = await freshSocket();
    const off = s.subscribe("markets", () => {});
    FakeSocket.all[0].open();
    off();
    FakeSocket.all[0].close();
    vi.advanceTimersByTime(60_000);
    expect(FakeSocket.all).toHaveLength(1);
  });

  it("delivers the server's user:<id> messages to 'user' subscribers", async () => {
    const s = await freshSocket();
    const h = vi.fn();
    s.subscribe("user", h);
    FakeSocket.all[0].open();
    FakeSocket.all[0].emit({ topic: "user:3f2a", type: "wallet.changed", data: { equityCents: 1 } });
    expect(h).toHaveBeenCalledWith({ topic: "user:3f2a", type: "wallet.changed", data: { equityCents: 1 } });
  });

  it("ignores malformed frames", async () => {
    const s = await freshSocket();
    const h = vi.fn();
    s.subscribe("markets", h);
    FakeSocket.all[0].open();
    FakeSocket.all[0].onmessage?.({ data: "not json" });
    expect(h).not.toHaveBeenCalled();
  });
});
