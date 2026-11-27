"use client";

/** Simple responsive SVG line with an optional dashed baseline, for small dashboards. */
export function LineChart({ values, baseline, height = 150, label }: { values: number[]; baseline?: number; height?: number; label: string }) {
  const W = 800;
  if (values.length < 2) return <p className="text-[13px] text-mute">Not enough history yet. This fills in as you trade.</p>;
  const all = baseline != null ? [...values, baseline] : values;
  const min = Math.min(...all);
  const max = Math.max(...all);
  const pad = Math.max(1, (max - min) * 0.1);
  const lo = min - pad;
  const span = max + pad - lo;
  const y = (v: number) => (height - ((v - lo) / span) * height).toFixed(1);
  const pts = values.map((v, i) => `${((i / (values.length - 1)) * W).toFixed(1)},${y(v)}`);
  const line = "M" + pts.join(" L");
  return (
    <svg role="img" aria-label={label} viewBox={`0 0 ${W} ${height}`} preserveAspectRatio="none" className="block w-full" style={{ height }}>
      {baseline != null && <path d={`M0,${y(baseline)} H${W}`} stroke="#3A4450" strokeDasharray="4 4" fill="none" vectorEffect="non-scaling-stroke" />}
      <path d={`${line} L${W},${height} L0,${height} Z`} fill="#58A6FF" opacity={0.12} />
      <path d={line} stroke="#58A6FF" strokeWidth={2} fill="none" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

export function Bars({ rows }: { rows: { name: string; value: number; label: string; tone?: "yes" | "no" }[] }) {
  const max = Math.max(1, ...rows.map((r) => Math.abs(r.value)));
  return (
    <div className="flex flex-col gap-2.5">
      {rows.map((r) => (
        <div key={r.name} className="flex items-center gap-3 text-[13px]">
          <span className="w-[132px] flex-none truncate" title={r.name}>{r.name}</span>
          <span className="h-[18px] min-w-0 flex-auto overflow-hidden rounded bg-raised">
            <span className={`block h-full rounded ${r.tone === "no" ? "bg-no" : "bg-yes"}`} style={{ width: `${Math.round((Math.abs(r.value) / max) * 100)}%` }} />
          </span>
          <span className="w-24 flex-none text-right font-mono">{r.label}</span>
        </div>
      ))}
    </div>
  );
}
