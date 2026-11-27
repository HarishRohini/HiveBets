/** Formats integer cents as dollars, e.g. 104260 -> "$1,042.60". */
export function money(cents: number | null | undefined, opts: { sign?: boolean } = {}): string {
  if (cents == null || Number.isNaN(cents)) return "—";
  const neg = cents < 0;
  const abs = Math.abs(Math.round(cents));
  const body =
    "$" +
    (abs / 100).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  if (neg) return "-" + body;
  return opts.sign ? "+" + body : body;
}

/** Whole-dollar variant for big tiles, e.g. 1842000 -> "$18,420". */
export function moneyShort(cents: number | null | undefined): string {
  if (cents == null) return "—";
  const neg = cents < 0;
  const s = "$" + Math.round(Math.abs(cents) / 100).toLocaleString("en-US");
  return neg ? "-" + s : s;
}

/** A price in cents, e.g. 63 -> "63¢". */
export function cents(p: number | null | undefined): string {
  return p == null ? "—" : `${Math.round(p)}¢`;
}

/** mm:ss for under an hour, "3 h", "2 days" beyond. Zero or less -> "00:00". */
export function countdown(msLeft: number): string {
  if (msLeft <= 0) return "00:00";
  const s = Math.floor(msLeft / 1000);
  if (s < 3600) {
    const m = Math.floor(s / 60);
    const r = s % 60;
    return `${String(m).padStart(2, "0")}:${String(r).padStart(2, "0")}`;
  }
  const h = Math.floor(s / 3600);
  if (h < 48) return `${h} h`;
  return `${Math.floor(h / 24)} days`;
}

export function timeAgo(iso: string, now = Date.now()): string {
  const d = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000));
  if (d < 60) return `${d}s ago`;
  if (d < 3600) return `${Math.floor(d / 60)} min ago`;
  if (d < 86400) return `${Math.floor(d / 3600)} h ago`;
  return `${Math.floor(d / 86400)} d ago`;
}

export function clockTime(iso: string): string {
  return new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function number(n: number | null | undefined): string {
  return n == null ? "—" : n.toLocaleString("en-US");
}
