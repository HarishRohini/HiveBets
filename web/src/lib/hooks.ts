"use client";
import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "./api";
import { liveSocket, type WsMessage } from "./ws";

export function useMe() {
  return useQuery({
    queryKey: ["me"],
    queryFn: api.me,
    retry: (n, e) => !(e instanceof ApiError && (e.status === 401 || e.status === 403)) && n < 2,
    staleTime: 60_000,
  });
}

export function useIsAdmin(): boolean {
  const me = useMe();
  return !!me.data?.roles?.includes("ADMIN");
}

/** True while the WebSocket is connected; pages poll when it is false. */
export function useWsConnected(): boolean {
  const [c, setC] = useState(false);
  useEffect(() => {
    const s = liveSocket();
    setC(s.connected);
    return s.onStatus(setC);
  }, []);
  return c;
}

/** Subscribes to WS topics for the component's lifetime. The handler may change between renders. */
export function useTopics(topics: string[], handler: (m: WsMessage) => void) {
  const ref = useRef(handler);
  ref.current = handler;
  const key = topics.join("|");
  useEffect(() => {
    if (!key) return;
    const s = liveSocket();
    const offs = key.split("|").map((t) => s.subscribe(t, (m) => ref.current(m)));
    return () => offs.forEach((o) => o());
  }, [key]);
}

/** Polling interval to use for live data: off when the socket is up, 5 s when it is down. */
export function usePollInterval(): number | false {
  const connected = useWsConnected();
  return connected ? false : 5000;
}

export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(t);
  }, [intervalMs]);
  return now;
}

export function useInvalidateOn(topics: string[], keys: unknown[][]) {
  const qc = useQueryClient();
  useTopics(topics, () => keys.forEach((k) => qc.invalidateQueries({ queryKey: k })));
}
