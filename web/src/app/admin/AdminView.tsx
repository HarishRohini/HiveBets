"use client";
import Link from "next/link";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { cents, countdown, timeAgo } from "@/lib/format";
import { useNow, useTopics } from "@/lib/hooks";
import type { MarketKind, Side } from "@/lib/types";
import { PageHeader, Shell } from "@/components/Nav";
import { ErrorNote, KindPill } from "@/components/ui";

export function AdminView() {
  const qc = useQueryClient();
  const now = useNow();
  const markets = useQuery({ queryKey: ["markets"], queryFn: () => api.markets("ALL"), refetchInterval: 10000 });
  const ingested = useQuery({ queryKey: ["ingested"], queryFn: () => api.ingested(50), refetchInterval: 60000, retry: 0 });
  useTopics(["markets"], () => qc.invalidateQueries({ queryKey: ["markets"] }));
  const active = (markets.data ?? []).filter((m) => m.status !== "RESOLVED");

  return (
    <Shell>
      <PageHeader title="Admin" sub="Open markets, post headlines and settle markets by hand.">
        <span className="rounded-full bg-no-deep px-3 py-1.5 text-xs font-medium text-amber">Admin only</span>
      </PageHeader>

      <div className="grid gap-5 [grid-template-columns:repeat(auto-fit,minmax(min(420px,100%),1fr))]">
        <CreateMarket />
        <PostHeadline markets={active.map((m) => ({ id: m.id, question: m.question }))} />
      </div>

      <section aria-label="Markets to settle" className="card">
        <h2 className="mb-1 text-sm font-semibold">Open and closed markets</h2>
        <p className="mb-2 text-[13px] text-mute">Resolving cancels unfilled orders and pays $1.00 per winning share. It cannot be undone.</p>
        <ErrorNote error={markets.error} />
        {active.length === 0 && <p className="border-t border-line py-3 text-[13px] text-mute">No open markets.</p>}
        {active.map((m) => (
          <ResolveRow key={m.id} id={m.id} question={m.question} kind={m.kind} last={m.lastPrice ?? null} left={new Date(m.closeAt).getTime() - now} status={m.status} />
        ))}
      </section>

      <section aria-label="Real news ingested" className="card">
        <h2 className="mb-1 text-sm font-semibold">Real news ingested</h2>
        <p className="mb-2 text-[13px] text-mute">Headlines pulled from RSS feeds. Only those close enough to an open real-world market are published to traders.</p>
        {ingested.error ? (
          <p className="border-t border-line py-3 text-[13px] text-mute">Not available yet.</p>
        ) : (ingested.data ?? []).length === 0 ? (
          <p className="border-t border-line py-3 text-[13px] text-mute">Nothing ingested yet.</p>
        ) : (
          (ingested.data ?? []).map((i) => (
            <div key={i.id} data-testid="ingested-row" className="flex flex-wrap items-baseline gap-x-4 gap-y-1 border-t border-line py-2.5 text-[13px]">
              <span className="min-w-0 flex-[1_1_320px]">
                {i.url ? <a href={i.url} target="_blank" rel="noreferrer noopener" className="hover:underline">{i.title ?? i.text}</a> : i.title ?? i.text}
              </span>
              <span className="text-xs text-mute">{i.publisher}</span>
              <span className="font-mono text-xs text-soft">{i.similarity != null ? `sim ${i.similarity.toFixed(2)}` : ""}</span>
              <span className={`pill ${i.matchedMarketId ? "border-yes text-yes" : ""}`}>{i.status ?? (i.matchedMarketId ? "published" : "unmatched")}</span>
              <span className="text-xs text-mute">{i.ingestedAt ? timeAgo(i.ingestedAt, now) : ""}</span>
            </div>
          ))
        )}
      </section>
    </Shell>
  );
}

function CreateMarket() {
  const qc = useQueryClient();
  const [question, setQuestion] = useState("");
  const [yes, setYes] = useState("");
  const [no, setNo] = useState("");
  const [kind, setKind] = useState<MarketKind>("SIMULATED");
  const [mode, setMode] = useState<"duration" | "date">("duration");
  const [minutes, setMinutes] = useState(10);
  const [closeAt, setCloseAt] = useState("");
  const [done, setDone] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: () =>
      api.createMarket({
        question: question.trim(),
        yesStatement: yes.trim() || undefined,
        noStatement: no.trim() || undefined,
        kind,
        ...(mode === "duration" ? { durationMinutes: minutes } : { closeAt: new Date(closeAt).toISOString() }),
      }),
    onSuccess: (m) => {
      setDone(m.id);
      setQuestion("");
      setYes("");
      setNo("");
      qc.invalidateQueries({ queryKey: ["markets"] });
    },
  });

  return (
    <section aria-label="Create a market" className="card">
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          setDone(null);
          create.mutate();
        }}
      >
        <h2 className="text-sm font-semibold">Create a market</h2>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="cm-question" className="text-[13px] text-soft">Question</label>
          <textarea id="cm-question" data-testid="create-question" className="field" required minLength={8} value={question} onChange={(e) => setQuestion(e.target.value)} placeholder="Will the Mars colony council pass the water-rights vote?" />
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <label htmlFor="cm-yes" className="text-[13px] text-soft">“Yes happened” statement <span className="text-mute">(optional)</span></label>
            <input id="cm-yes" data-testid="create-yes" className="field" value={yes} onChange={(e) => setYes(e.target.value)} />
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="cm-no" className="text-[13px] text-soft">“No happened” statement <span className="text-mute">(optional)</span></label>
            <input id="cm-no" data-testid="create-no" className="field" value={no} onChange={(e) => setNo(e.target.value)} />
          </div>
        </div>
        <fieldset className="flex flex-wrap gap-2">
          <legend className="mb-1.5 text-[13px] text-soft">Kind</legend>
          {(["SIMULATED", "REAL"] as MarketKind[]).map((k) => (
            <button key={k} type="button" data-testid={`create-kind-${k.toLowerCase()}`} aria-pressed={kind === k} onClick={() => setKind(k)} className={`min-h-11 rounded-full border px-4 ${kind === k ? "border-yes bg-yes-deep text-white" : "border-edge text-soft"}`}>
              {k === "REAL" ? "Real world" : "Simulated"}
            </button>
          ))}
        </fieldset>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1.5 text-[13px] text-soft">Closes</legend>
          <div className="flex flex-wrap items-center gap-3">
            <label className="flex items-center gap-2 text-[13px]">
              <input type="radio" name="close-mode" data-testid="create-mode-duration" checked={mode === "duration"} onChange={() => setMode("duration")} /> after
            </label>
            <input aria-label="Minutes until close" data-testid="create-minutes" type="number" min={1} max={100000} className="field w-28" value={minutes} disabled={mode !== "duration"} onChange={(e) => setMinutes(Math.max(1, Number(e.target.value) || 1))} />
            <span className="text-[13px] text-mute">minutes</span>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <label className="flex items-center gap-2 text-[13px]">
              <input type="radio" name="close-mode" data-testid="create-mode-date" checked={mode === "date"} onChange={() => setMode("date")} /> at
            </label>
            <input aria-label="Close date and time" data-testid="create-close-at" type="datetime-local" className="field w-auto" value={closeAt} disabled={mode !== "date"} required={mode === "date"} onChange={(e) => setCloseAt(e.target.value)} />
          </div>
        </fieldset>
        <ErrorNote error={create.error} />
        {done && (
          <p role="status" data-testid="create-done" className="text-[13px] text-soft">
            Market opened. <Link href={`/markets/${done}`} className="text-yes underline">Open it</Link>
          </p>
        )}
        <button type="submit" data-testid="create-submit" disabled={create.isPending} className="btn btn-primary self-start">
          {create.isPending ? "Creating…" : "Open market"}
        </button>
      </form>
    </section>
  );
}

function PostHeadline({ markets }: { markets: { id: string; question: string }[] }) {
  const qc = useQueryClient();
  const [marketId, setMarketId] = useState("");
  const [text, setText] = useState("");
  const [ok, setOk] = useState(false);
  const post = useMutation({
    mutationFn: () => api.postNews({ marketId: marketId || markets[0]?.id, text: text.trim() }),
    onSuccess: (h) => {
      setText("");
      setOk(true);
      qc.invalidateQueries({ queryKey: ["news", h.marketId] });
    },
  });
  return (
    <section aria-label="Post a headline" className="card">
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          setOk(false);
          post.mutate();
        }}
      >
        <h2 className="text-sm font-semibold">Post a headline</h2>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="ph-market" className="text-[13px] text-soft">Market</label>
          <select id="ph-market" data-testid="headline-market" className="field" value={marketId || markets[0]?.id || ""} onChange={(e) => setMarketId(e.target.value)}>
            {markets.length === 0 && <option value="">No open markets</option>}
            {markets.map((m) => (
              <option key={m.id} value={m.id}>{m.question}</option>
            ))}
          </select>
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="ph-text" className="text-[13px] text-soft">Headline</label>
          <textarea id="ph-text" data-testid="headline-input" className="field" required minLength={5} maxLength={280} value={text} onChange={(e) => setText(e.target.value)} placeholder="Ice miners’ union withdraws its objection to the water-rights bill" />
        </div>
        <ErrorNote error={post.error} />
        {ok && <p role="status" data-testid="headline-done" className="text-[13px] text-soft">Published. Traders on that market see it now.</p>}
        <button type="submit" data-testid="headline-submit" disabled={post.isPending || markets.length === 0} className="btn btn-primary self-start">
          {post.isPending ? "Posting…" : "Publish headline"}
        </button>
      </form>
    </section>
  );
}

function ResolveRow({ id, question, kind, last, left, status }: { id: string; question: string; kind: MarketKind; last: number | null; left: number; status: string }) {
  const qc = useQueryClient();
  const [confirm, setConfirm] = useState<Side | null>(null);
  const resolve = useMutation({
    mutationFn: (o: Side) => api.resolveMarket(id, o),
    onSuccess: () => {
      setConfirm(null);
      qc.invalidateQueries({ queryKey: ["markets"] });
    },
  });
  return (
    <div data-testid="admin-market-row" data-market-id={id} className="flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-line py-3">
      <KindPill kind={kind} />
      <Link href={`/markets/${id}`} className="min-w-0 flex-[1_1_300px] hover:underline">{question}</Link>
      <span className="font-mono text-xs text-soft">last {cents(last)}</span>
      <span className="font-mono text-xs text-amber">{status === "OPEN" && left > 0 ? countdown(left) : "Closed"}</span>
      {confirm ? (
        <span className="flex flex-wrap items-center gap-2">
          <span className="text-[13px]">Resolve {confirm === "YES" ? "Yes" : "No"}?</span>
          <button type="button" data-testid="resolve-confirm" disabled={resolve.isPending} onClick={() => resolve.mutate(confirm)} className={`btn font-semibold text-bg ${confirm === "YES" ? "bg-yes" : "bg-no"}`}>
            {resolve.isPending ? "Resolving…" : "Confirm"}
          </button>
          <button type="button" data-testid="resolve-cancel" onClick={() => setConfirm(null)} className="btn btn-ghost">Back</button>
        </span>
      ) : (
        <span className="flex gap-2">
          <button type="button" data-testid="resolve-yes" onClick={() => setConfirm("YES")} className="btn btn-ghost border-yes text-yes">Resolve Yes</button>
          <button type="button" data-testid="resolve-no" onClick={() => setConfirm("NO")} className="btn btn-ghost border-no text-no">Resolve No</button>
        </span>
      )}
      {resolve.error && <div className="w-full"><ErrorNote error={resolve.error} /></div>}
    </div>
  );
}
