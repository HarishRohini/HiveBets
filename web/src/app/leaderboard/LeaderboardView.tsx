"use client";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { money, number } from "@/lib/format";
import { useMe, usePollInterval, useTopics } from "@/lib/hooks";
import type { LeaderRow } from "@/lib/types";
import { PageHeader, Shell } from "@/components/Nav";
import { ErrorNote } from "@/components/ui";

function hitRate(r: LeaderRow): string {
  return r.marketsResolved > 0 ? `${Math.round((r.marketsWon / r.marketsResolved) * 100)}%` : "—";
}

export function LeaderboardView() {
  const qc = useQueryClient();
  const poll = usePollInterval();
  const me = useMe();
  const board = useQuery({ queryKey: ["leaderboard"], queryFn: () => api.leaderboard(50), refetchInterval: poll || 30000 });
  useTopics(["leaderboard"], () => qc.invalidateQueries({ queryKey: ["leaderboard"] }));

  const rows = board.data?.rows ?? [];
  const mine = board.data?.me;
  const showMe = mine && !rows.some((r) => r.userId === mine.userId);
  const above = mine ? rows.find((r) => r.rank === mine.rank - 1) : undefined;

  const row = (r: LeaderRow) => {
    const you = r.userId === me.data?.id;
    return (
      <tr key={r.userId} data-testid="leader-row" className={`border-t border-line ${you ? "bg-yes-deep" : ""}`}>
        <td className="px-3 py-3 font-mono text-soft">{r.rank}</td>
        <td className="px-3 py-3">
          <span className={you ? "font-semibold" : ""}>{you ? `${r.username} (you)` : r.username}</span>{" "}
          <span className="ml-1.5 rounded-full border border-edge px-2 py-0.5 text-[11px] text-soft" data-testid="account-type">
            {r.accountType === "AGENT" ? "AI" : "Human"}
          </span>
        </td>
        <td className="px-3 py-3 text-right font-mono">{number(r.marketsTraded)}</td>
        <td className="px-3 py-3 text-right font-mono">{hitRate(r)}</td>
        <td className={`px-3 py-3 text-right font-mono ${r.profitCents < 0 ? "text-no" : "text-yes"}`}>{money(r.profitCents, { sign: true })}</td>
      </tr>
    );
  };

  return (
    <Shell>
      <PageHeader title="Leaderboard" sub="Ranked by profit, with open positions valued at the last price. Updates live." />
      <ErrorNote error={board.error} />
      <div className="flex flex-wrap items-start gap-5">
        <section aria-label="Rankings" className="card min-w-0 flex-[999_1_560px]">
          <div className="relative overflow-x-auto">
            <table className="w-full min-w-[600px] border-collapse text-sm">
              <thead>
                <tr className="text-left text-xs text-mute">
                  <th scope="col" className="w-12 px-3 py-2 font-medium">Rank</th>
                  <th scope="col" className="px-3 py-2 font-medium">Trader</th>
                  <th scope="col" className="px-3 py-2 text-right font-medium">Markets</th>
                  <th scope="col" className="px-3 py-2 text-right font-medium">Right side</th>
                  <th scope="col" className="px-3 py-2 text-right font-medium">Profit</th>
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 && (
                  <tr><td colSpan={5} className="border-t border-line px-3 py-3 text-[13px] text-mute">Nobody has traded yet.</td></tr>
                )}
                {rows.map(row)}
                {showMe && mine && (
                  <>
                    <tr aria-hidden="true"><td colSpan={5} className="px-3 py-1 text-center text-mute">⋯</td></tr>
                    {row(mine)}
                  </>
                )}
              </tbody>
            </table>
          </div>
        </section>

        <section aria-label="Your standing" className="flex min-w-0 flex-[1_1_320px] flex-col gap-3.5 rounded-xl border border-yes bg-card p-5">
          <h2 className="text-sm font-semibold">Your standing</h2>
          {mine ? (
            <>
              <div className="flex items-baseline gap-2.5">
                <span className="font-mono text-[40px] leading-none" data-testid="my-rank">{mine.rank}</span>
                <span className="text-mute">of {number(board.data?.total ?? 0)} traders</span>
              </div>
              <dl className="flex flex-col gap-2 text-[13px]">
                <div className="flex justify-between"><dt className="text-mute">Profit</dt><dd className={`font-mono ${mine.profitCents < 0 ? "text-no" : "text-yes"}`}>{money(mine.profitCents, { sign: true })}</dd></div>
                <div className="flex justify-between"><dt className="text-mute">Markets traded</dt><dd className="font-mono">{mine.marketsTraded}</dd></div>
                <div className="flex justify-between"><dt className="text-mute">On the right side</dt><dd className="font-mono">{hitRate(mine)}</dd></div>
                {above && (
                  <div className="flex justify-between"><dt className="text-mute">Gap to rank {above.rank}</dt><dd className="font-mono">{money(above.profitCents - mine.profitCents)}</dd></div>
                )}
              </dl>
            </>
          ) : (
            <p className="text-[13px] text-mute">Place a trade to get on the board.</p>
          )}
          <Link href="/portfolio" className="btn btn-ghost w-full">Open portfolio</Link>
        </section>
      </div>
    </Shell>
  );
}
