export type Role = "ADMIN" | "TRADER" | "ENGINE";
export type AccountType = "HUMAN" | "AGENT" | "SERVICE";
export type Side = "YES" | "NO";
export type Action = "BUY" | "SELL";
export type MarketKind = "SIMULATED" | "REAL";
export type MarketStatus = "OPEN" | "CLOSED" | "RESOLVED";

export interface User {
  id: string;
  username: string;
  roles: Role[];
  accountType: AccountType;
  createdAt?: string;
}

export interface Market {
  id: string;
  question: string;
  yesStatement?: string | null;
  noStatement?: string | null;
  kind: MarketKind;
  status: MarketStatus;
  closeAt: string;
  createdAt: string;
  resolvedAt?: string | null;
  outcome?: Side | null;
  lastPrice?: number | null;
  volumeCents: number;
  tradeCount: number;
  traderCount: number;
}

export interface BookLevel {
  price: number;
  shares: number;
}

export interface Book {
  bids: BookLevel[];
  asks: BookLevel[];
}

export interface Trade {
  id: string;
  marketId: string;
  price: number;
  shares: number;
  kind?: string;
  takerUserId?: string;
  takerUsername?: string;
  takerSide?: Side;
  takerAction?: Action;
  takerPrice?: number;
  makerUsername?: string;
  executedAt: string;
}

export interface Order {
  id: string;
  marketId: string;
  side: Side;
  action: Action;
  price: number;
  shares: number;
  filledShares: number;
  status: "OPEN" | "FILLED" | "CANCELLED";
  reservedCents: number;
  avgFillPrice?: number | null;
  createdAt: string;
  updatedAt?: string;
}

export interface Headline {
  id: string;
  marketId: string | null;
  text: string;
  source: "synthetic" | "real" | "manual";
  url?: string | null;
  publisher?: string | null;
  publishedAt: string;
}

export interface Candle {
  t: number;
  o: number;
  h: number;
  l: number;
  c: number;
  v: number;
}

export interface LiveMarket {
  marketId: string;
  lastPrice: number | null;
  book: Book;
  candles: Candle[];
  trades: Trade[];
  volumeCents: number;
  traderCount: number;
  news: Headline[];
}

export interface LiveSummary {
  lastPrice: number | null;
  volumeCents: number;
  traderCount: number;
  spark: number[];
}

export interface Wallet {
  availableCents: number;
  reservedCents: number;
  equityCents: number;
  profitCents: number;
  startingCents: number;
}

export interface Position {
  marketId: string;
  question: string;
  kind: MarketKind;
  status: MarketStatus;
  closeAt: string;
  yesShares: number;
  noShares: number;
  yesCostCents: number;
  noCostCents: number;
  lastPrice: number | null;
  valueCents: number;
  profitCents: number;
}

export interface ResolvedHolding {
  marketId: string;
  question: string;
  outcome: Side;
  yesShares: number;
  noShares: number;
  costCents: number;
  payoutCents: number;
  profitCents: number;
  resolvedAt: string;
}

export interface Portfolio {
  wallet: Wallet;
  positions: Position[];
  resolved: ResolvedHolding[];
}

export interface LeaderRow {
  rank: number;
  userId: string;
  username: string;
  accountType: AccountType;
  profitCents: number;
  equityCents: number;
  marketsTraded: number;
  marketsWon: number;
  marketsResolved: number;
}

export interface Leaderboard {
  rows: LeaderRow[];
  me: LeaderRow | null;
  total: number;
}

export interface EquityPoint {
  t: number;
  equityCents: number;
}

export interface LiveStats {
  tradersOnline: number;
  connections: number;
}

export interface AnalyticsSummary {
  asOf: string | null;
  marketsResolved: number;
  trades: number;
  volumeCents: number;
  sessions: number;
  orders: number;
}

export interface AnalyticsMarket {
  marketId: string;
  question: string;
  kind: MarketKind;
  status: MarketStatus;
  volumeCents: number;
  trades: number;
  traders: number;
  avgSpreadCents: number | null;
  avgDepthShares: number | null;
}

export interface FunnelStep {
  step: string;
  sessions: number;
}

export interface WebVital {
  metric: string;
  page: string;
  p75: number;
  samples: number;
}

export interface AnalyticsTrader {
  userId: string;
  username: string;
  accountType: AccountType;
  profitCents: number;
  trades: number;
  volumeCents: number;
}

export interface IngestedItem {
  id: string;
  title?: string;
  text?: string;
  url?: string;
  publisher?: string;
  matchedMarketId?: string | null;
  similarity?: number | null;
  status?: string;
  publishedAt?: string;
  ingestedAt?: string;
}
