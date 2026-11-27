"use client";
import { API_URL } from "./config";

export type ClientEventType = "page_view" | "click" | "order_submitted" | "session_start" | "session_end" | "web_vital";

interface ClientEvent {
  type: ClientEventType;
  sessionId: string;
  path: string;
  ts: string;
  props: Record<string, unknown>;
}

const SESSION_KEY = "hb_session_id";
const FLUSH_MS = 5000;
const MAX_BATCH = 50;

let queue: ClientEvent[] = [];
let timer: ReturnType<typeof setTimeout> | null = null;
let started = false;

function uuid(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

export function sessionId(): string {
  try {
    let id = sessionStorage.getItem(SESSION_KEY);
    if (!id) {
      id = uuid();
      sessionStorage.setItem(SESSION_KEY, id);
    }
    return id;
  } catch {
    return "no-storage";
  }
}

export function track(type: ClientEventType, props: Record<string, unknown> = {}) {
  if (typeof window === "undefined") return;
  queue.push({ type, sessionId: sessionId(), path: window.location.pathname, ts: new Date().toISOString(), props });
  if (queue.length >= MAX_BATCH) flush();
  else if (!timer) timer = setTimeout(flush, FLUSH_MS);
}

export function flush(useBeacon = false) {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  if (!queue.length) return;
  const body = JSON.stringify({ events: queue });
  queue = [];
  const url = API_URL + "/api/events";
  if (useBeacon && navigator.sendBeacon) {
    // text/plain keeps the beacon a "simple" CORS request; the gateway parses the body as JSON either way.
    navigator.sendBeacon(url, new Blob([body], { type: "text/plain" }));
    return;
  }
  fetch(url, { method: "POST", body, headers: { "Content-Type": "application/json" }, credentials: "include", keepalive: true }).catch(() => {});
}

/** Starts session tracking, click capture and web vitals. Safe to call more than once. */
export function startClickstream() {
  if (started || typeof window === "undefined") return;
  started = true;
  let isNew = false;
  try {
    isNew = !sessionStorage.getItem(SESSION_KEY);
  } catch {}
  sessionId();
  if (isNew) track("session_start", { referrer: document.referrer || null, viewport: `${innerWidth}x${innerHeight}` });

  document.addEventListener(
    "click",
    (e) => {
      const el = (e.target as Element | null)?.closest?.("[data-testid]");
      if (el) track("click", { testid: el.getAttribute("data-testid") });
    },
    { capture: true },
  );
  window.addEventListener("pagehide", () => {
    track("session_end", {});
    flush(true);
  });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") flush(true);
  });

  import("web-vitals").then(({ onLCP, onINP, onCLS, onTTFB }) => {
    const report = (m: { name: string; value: number; rating: string; id: string }) =>
      track("web_vital", { metric: m.name, value: Math.round(m.value * 1000) / 1000, rating: m.rating, id: m.id });
    onLCP(report);
    onINP(report);
    onCLS(report);
    onTTFB(report);
  });
}
