// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("web-vitals", () => ({ onLCP: vi.fn(), onINP: vi.fn(), onCLS: vi.fn(), onTTFB: vi.fn() }));

let fetchMock: ReturnType<typeof vi.fn>;
let beacon: ReturnType<typeof vi.fn>;

async function load() {
  vi.resetModules();
  return import("./clickstream");
}

function sentEvents(call: unknown[]): Array<{ type: string; props: Record<string, unknown>; sessionId: string; path: string }> {
  return JSON.parse((call[1] as { body: string }).body).events;
}

beforeEach(() => {
  vi.useFakeTimers();
  sessionStorage.clear();
  fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 202 }));
  beacon = vi.fn().mockReturnValue(true);
  vi.stubGlobal("fetch", fetchMock);
  Object.defineProperty(navigator, "sendBeacon", { value: beacon, configurable: true });
  window.history.replaceState({}, "", "/markets/abc");
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("clickstream", () => {
  it("keeps one session id per tab", async () => {
    const c = await load();
    const id = c.sessionId();
    expect(id).toMatch(/.+/);
    expect(c.sessionId()).toBe(id);
    expect(sessionStorage.getItem("hb_session_id")).toBe(id);
  });

  it("batches events and flushes after 5 seconds", async () => {
    const c = await load();
    c.track("page_view", { a: 1 });
    c.track("click", { testid: "nav-markets" });
    expect(fetchMock).not.toHaveBeenCalled();
    vi.advanceTimersByTime(5000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://localhost:8080/api/events");
    expect(init.headers["Content-Type"]).toBe("application/json");
    expect(init.keepalive).toBe(true);
    const events = sentEvents(fetchMock.mock.calls[0]);
    expect(events.map((e) => e.type)).toEqual(["page_view", "click"]);
    expect(events[0].path).toBe("/markets/abc");
    expect(events[1].props).toEqual({ testid: "nav-markets" });
  });

  it("flushes immediately at 50 events", async () => {
    const c = await load();
    for (let i = 0; i < 50; i++) c.track("click", { i });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(sentEvents(fetchMock.mock.calls[0])).toHaveLength(50);
    vi.advanceTimersByTime(10_000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("does nothing when the queue is empty", async () => {
    const c = await load();
    c.flush();
    c.flush(true);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(beacon).not.toHaveBeenCalled();
  });

  it("uses sendBeacon with text/plain on page hide", async () => {
    const c = await load();
    c.startClickstream();
    window.dispatchEvent(new Event("pagehide"));
    expect(beacon).toHaveBeenCalledTimes(1);
    const [url, blob] = beacon.mock.calls[0];
    expect(url).toBe("http://localhost:8080/api/events");
    expect(blob.type).toBe("text/plain");
    const events = JSON.parse(await blob.text()).events;
    expect(events.map((e: { type: string }) => e.type)).toEqual(["session_start", "session_end"]);
  });

  it("records clicks on elements with a data-testid", async () => {
    const c = await load();
    c.startClickstream();
    document.body.innerHTML = '<button data-testid="ticket-submit"><span id="inner">Buy</span></button><p id="plain">x</p>';
    document.getElementById("inner")!.click();
    document.getElementById("plain")!.click();
    c.flush();
    const events = sentEvents(fetchMock.mock.calls[0]);
    const clicks = events.filter((e) => e.type === "click");
    expect(clicks).toHaveLength(1);
    expect(clicks[0].props).toEqual({ testid: "ticket-submit" });
  });
});
