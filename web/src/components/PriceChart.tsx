"use client";
import { useEffect, useRef } from "react";
import {
  AreaSeries, ColorType, createChart, createSeriesMarkers, LineStyle,
  type IChartApi, type ISeriesApi, type ISeriesMarkersPluginApi, type SeriesMarker, type Time, type UTCTimestamp,
} from "lightweight-charts";
import type { Candle, Headline } from "@/lib/types";

const BUCKET = 10;

/** Live YES price line (candle closes) with a marker for every headline. */
export function PriceChart({ candles, news, height = 280 }: { candles: Candle[]; news: Headline[]; height?: number }) {
  const el = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const series = useRef<ISeriesApi<"Area"> | null>(null);
  const markers = useRef<ISeriesMarkersPluginApi<Time> | null>(null);

  useEffect(() => {
    if (!el.current) return;
    const c = createChart(el.current, {
      height,
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: "#161B22" }, textColor: "#9BA7B4", fontFamily: "var(--font-mono-jb), monospace", fontSize: 11, attributionLogo: false },
      grid: { vertLines: { visible: false }, horzLines: { color: "#262D36" } },
      rightPriceScale: { borderVisible: false, scaleMargins: { top: 0.1, bottom: 0.1 } },
      timeScale: { borderVisible: false, timeVisible: true, secondsVisible: true },
      crosshair: { horzLine: { color: "#3A4450" }, vertLine: { color: "#3A4450" } },
      handleScroll: false,
      handleScale: false,
    });
    const s = c.addSeries(AreaSeries, {
      lineColor: "#58A6FF",
      lineWidth: 2,
      topColor: "rgba(88,166,255,0.22)",
      bottomColor: "rgba(88,166,255,0.02)",
      priceFormat: { type: "custom", formatter: (p: number) => `${Math.round(p)}¢`, minMove: 1 },
      autoscaleInfoProvider: () => ({ priceRange: { minValue: 0, maxValue: 100 } }),
    });
    s.createPriceLine({ price: 50, color: "#3A4450", lineWidth: 1, lineStyle: LineStyle.Dashed, axisLabelVisible: false, title: "" });
    chart.current = c;
    series.current = s;
    markers.current = createSeriesMarkers(s, []);
    return () => {
      c.remove();
      chart.current = null;
      series.current = null;
      markers.current = null;
    };
  }, [height]);

  useEffect(() => {
    const s = series.current;
    if (!s) return;
    const data = [...candles]
      .sort((a, b) => a.t - b.t)
      .filter((c, i, arr) => i === 0 || c.t !== arr[i - 1].t)
      .map((c) => ({ time: c.t as UTCTimestamp, value: c.c }));
    s.setData(data);
    const times = data.map((d) => d.time as number);
    const ms: SeriesMarker<Time>[] = [];
    if (times.length) {
      for (const h of news) {
        const t = Math.floor(new Date(h.publishedAt).getTime() / 1000 / BUCKET) * BUCKET;
        // Snap to the latest candle at or before the headline, so the marker always has a bar.
        let snap: number | undefined;
        for (const x of times) if (x <= t) snap = x;
        if (snap == null) continue;
        ms.push({ time: snap as UTCTimestamp, position: "aboveBar", color: "#F0883E", shape: "arrowDown", text: "" });
      }
    }
    ms.sort((a, b) => (a.time as number) - (b.time as number));
    markers.current?.setMarkers(ms);
    chart.current?.timeScale().fitContent();
  }, [candles, news]);

  return (
    <div>
      <div ref={el} role="img" aria-label="Live YES price line with headline markers" style={{ height }} data-testid="price-chart" />
      <div className="mt-3 flex flex-wrap gap-x-5 gap-y-2 text-xs text-mute">
        <span>
          <span className="mr-1.5 inline-block h-0.5 w-3.5 bg-yes align-middle" />
          YES price
        </span>
        <span>
          <span className="mr-1.5 inline-block align-middle text-no">▼</span>
          Headline published
        </span>
      </div>
      {candles.length === 0 && <p className="mt-2 text-[13px] text-mute">No trades yet. The line starts with the first trade.</p>}
    </div>
  );
}

/** Folds one trade into 10-second candles, keeping the last 360 buckets (an hour). */
export function addTradeToCandles(candles: Candle[], price: number, shares: number, atIso: string): Candle[] {
  const t = Math.floor(new Date(atIso).getTime() / 1000 / BUCKET) * BUCKET;
  const out = candles.slice();
  const last = out[out.length - 1];
  if (last && last.t === t) {
    out[out.length - 1] = { ...last, h: Math.max(last.h, price), l: Math.min(last.l, price), c: price, v: last.v + shares };
  } else if (!last || t > last.t) {
    out.push({ t, o: price, h: price, l: price, c: price, v: shares });
  }
  return out.slice(-360);
}
