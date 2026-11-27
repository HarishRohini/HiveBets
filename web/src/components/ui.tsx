"use client";
import type { Headline, MarketKind, Side } from "@/lib/types";

export function KindPill({ kind }: { kind: MarketKind }) {
  return (
    <span className="pill" data-testid="market-kind">
      {kind === "REAL" ? "Real world" : "Simulated"}
    </span>
  );
}

export function SideChip({ side }: { side: Side }) {
  return (
    <span className={`rounded-md px-2.5 py-0.5 text-xs font-semibold text-bg ${side === "YES" ? "bg-yes" : "bg-no"}`}>
      {side === "YES" ? "Yes" : "No"}
    </span>
  );
}

export function SourceLabel({ h }: { h: Headline }) {
  if (h.source === "real")
    return (
      <span className="text-xs text-mute">
        Real ·{" "}
        {h.url ? (
          <a href={h.url} target="_blank" rel="noreferrer noopener" className="underline decoration-edge hover:text-white" data-testid="headline-link">
            {h.publisher || "source"}
          </a>
        ) : (
          h.publisher || "news feed"
        )}
      </span>
    );
  return <span className="text-xs text-mute">{h.source === "synthetic" ? "Simulated" : "Admin"}</span>;
}

export function Stat({ label, value, tone, testid }: { label: string; value: React.ReactNode; tone?: "yes" | "no"; testid?: string }) {
  return (
    <div className="card py-[18px]">
      <dt className="text-xs text-mute">{label}</dt>
      <dd data-testid={testid} className={`mt-1.5 font-mono text-[22px] leading-tight sm:text-[28px] ${tone === "yes" ? "text-yes" : tone === "no" ? "text-no" : ""}`}>
        {value}
      </dd>
    </div>
  );
}

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null;
  const msg = error instanceof Error ? error.message : String(error);
  return (
    <p role="alert" className="rounded-lg border border-no px-3 py-2 text-[13px] text-soft">
      {msg}
    </p>
  );
}

export function Empty({ children }: { children: React.ReactNode }) {
  return <p className="border-t border-line py-3 text-[13px] text-mute">{children}</p>;
}

export function Sparkline({ points, width = 120, height = 40 }: { points: number[]; width?: number; height?: number }) {
  if (points.length < 2) return <svg aria-hidden="true" width={width} height={height} />;
  const min = Math.min(...points);
  const max = Math.max(...points);
  const span = Math.max(4, max - min);
  const lo = min - (span - (max - min)) / 2;
  const d = points
    .map((p, i) => `${i === 0 ? "M" : "L"}${((i / (points.length - 1)) * width).toFixed(1)},${(height - ((p - lo) / span) * height).toFixed(1)}`)
    .join(" ");
  return (
    <svg aria-hidden="true" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" style={{ width, height }} className="block">
      <path d={d} fill="none" stroke="#58A6FF" strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
    </svg>
  );
}
