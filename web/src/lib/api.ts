import { API_URL } from "./config";
import type {
  AnalyticsMarket, AnalyticsSummary, AnalyticsTrader, Book, EquityPoint, FunnelStep, Headline, IngestedItem,
  Leaderboard, LiveMarket, LiveStats, LiveSummary, Market, Order, Portfolio, Side, Action, Trade, User, WebVital,
} from "./types";

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  let res: Response;
  try {
    res = await fetch(API_URL + path, { ...init, headers, credentials: "include", cache: "no-store" });
  } catch {
    throw new ApiError(0, "NETWORK", "Can't reach the server. Check that the stack is running.");
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  const body = text ? safeJson(text) : undefined;
  if (!res.ok) {
    const code = (body as { error?: string })?.error ?? (res.status === 429 ? "RATE_LIMITED" : `HTTP_${res.status}`);
    const msg =
      (body as { message?: string })?.message ??
      (res.status === 401 ? "Sign in first." : res.status === 403 ? "Not allowed." : res.status === 429 ? "Too many requests, slow down." : "Request failed.");
    throw new ApiError(res.status, code, msg);
  }
  return body as T;
}

function safeJson(t: string): unknown {
  try {
    return JSON.parse(t);
  } catch {
    return undefined;
  }
}

const post = <T>(path: string, body?: unknown, headers?: HeadersInit) =>
  request<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body), headers });

function qs(params: Record<string, string | number | undefined | null>): string {
  const s = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v != null && v !== "") s.set(k, String(v));
  const q = s.toString();
  return q ? "?" + q : "";
}

export const api = {
  // identity
  signup: (username: string, password: string, inviteCode?: string) =>
    post<{ user: User; token: string }>("/api/auth/signup", { username, password, inviteCode: inviteCode || undefined }),
  login: (username: string, password: string) => post<{ user: User; token: string }>("/api/auth/login", { username, password }),
  logout: () => post<void>("/api/auth/logout"),
  me: () => request<User>("/api/auth/me"),

  // trading
  markets: (status = "ALL", kind?: string) => request<Market[]>("/api/markets" + qs({ status, kind })),
  market: (id: string) => request<Market>(`/api/markets/${id}`),
  book: (id: string) => request<Book>(`/api/markets/${id}/book`),
  trades: (id: string, limit = 50) => request<Trade[]>(`/api/markets/${id}/trades` + qs({ limit })),
  createMarket: (body: { question: string; yesStatement?: string; noStatement?: string; kind: string; closeAt?: string; durationMinutes?: number }) =>
    post<Market>("/api/markets", body),
  resolveMarket: (id: string, outcome: Side) => post<Market>(`/api/markets/${id}/resolve`, { outcome }),
  placeOrder: (o: { marketId: string; side: Side; action: Action; price: number; shares: number }, idempotencyKey: string) =>
    post<Order>("/api/orders", o, { "Idempotency-Key": idempotencyKey }),
  cancelOrder: (id: string) => request<Order>(`/api/orders/${id}`, { method: "DELETE" }),
  orders: (status = "OPEN", marketId?: string) => request<Order[]>("/api/orders" + qs({ status, marketId })),
  portfolio: () => request<Portfolio>("/api/portfolio"),

  // news
  news: (marketId?: string, limit = 50) => request<Headline[]>("/api/news" + qs({ marketId, limit })),
  postNews: (body: { marketId: string; text: string; source?: string; url?: string; publisher?: string }) => post<Headline>("/api/news", body),
  ingested: (limit = 100) => request<IngestedItem[]>("/api/admin/news/ingested" + qs({ limit })),

  // realtime
  liveMarkets: () => request<Record<string, LiveSummary>>("/api/live/markets"),
  liveMarket: (id: string) => request<LiveMarket>(`/api/live/markets/${id}`),
  leaderboard: (limit = 50) => request<Leaderboard>("/api/live/leaderboard" + qs({ limit })),
  equity: () => request<EquityPoint[]>("/api/live/equity"),
  stats: () => request<LiveStats>("/api/live/stats"),

  // analytics
  analyticsSummary: () => request<AnalyticsSummary>("/api/analytics/summary"),
  analyticsMarkets: () => request<AnalyticsMarket[]>("/api/analytics/markets"),
  analyticsFunnel: () => request<FunnelStep[]>("/api/analytics/funnel"),
  analyticsVitals: () => request<WebVital[]>("/api/analytics/web-vitals"),
  analyticsTraders: () => request<AnalyticsTrader[]>("/api/analytics/traders"),
};
