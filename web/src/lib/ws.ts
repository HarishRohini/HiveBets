"use client";
import { wsUrl } from "./config";

export interface WsMessage<T = unknown> {
  topic: string;
  type: string;
  data: T;
}

type Handler = (m: WsMessage) => void;
type StatusListener = (connected: boolean) => void;

/**
 * One WebSocket per tab. Topics are reference counted; subscriptions are re-sent after
 * every reconnect. Reconnects with capped exponential backoff.
 */
class LiveSocket {
  private ws: WebSocket | null = null;
  private handlers = new Map<string, Set<Handler>>();
  private statusListeners = new Set<StatusListener>();
  private retry = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  connected = false;

  private ensure() {
    if (typeof window === "undefined") return;
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) return;
    if (this.timer) return;
    let ws: WebSocket;
    try {
      ws = new WebSocket(wsUrl());
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.ws = ws;
    ws.onopen = () => {
      this.retry = 0;
      this.setConnected(true);
      const topics = [...this.handlers.keys()];
      if (topics.length) ws.send(JSON.stringify({ op: "subscribe", topics }));
    };
    ws.onmessage = (ev) => {
      let m: WsMessage;
      try {
        m = JSON.parse(String(ev.data));
      } catch {
        return;
      }
      this.handlers.get(m.topic)?.forEach((h) => h(m));
    };
    ws.onclose = () => {
      this.setConnected(false);
      this.ws = null;
      if (this.handlers.size) this.scheduleReconnect();
    };
    ws.onerror = () => ws.close();
  }

  private scheduleReconnect() {
    if (this.timer) return;
    const delay = Math.min(15000, 500 * 2 ** this.retry++);
    this.timer = setTimeout(() => {
      this.timer = null;
      this.ensure();
    }, delay);
  }

  private setConnected(c: boolean) {
    this.connected = c;
    this.statusListeners.forEach((l) => l(c));
  }

  onStatus(l: StatusListener): () => void {
    this.statusListeners.add(l);
    return () => this.statusListeners.delete(l);
  }

  subscribe(topic: string, h: Handler): () => void {
    let set = this.handlers.get(topic);
    const fresh = !set;
    if (!set) {
      set = new Set();
      this.handlers.set(topic, set);
    }
    set.add(h);
    this.ensure();
    if (fresh && this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify({ op: "subscribe", topics: [topic] }));
    return () => {
      const s = this.handlers.get(topic);
      if (!s) return;
      s.delete(h);
      if (s.size === 0) {
        this.handlers.delete(topic);
        if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify({ op: "unsubscribe", topics: [topic] }));
      }
    };
  }
}

let socket: LiveSocket | null = null;
export function liveSocket(): LiveSocket {
  if (!socket) socket = new LiveSocket();
  return socket;
}
