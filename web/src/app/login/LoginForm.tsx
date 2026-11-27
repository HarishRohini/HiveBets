"use client";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { number } from "@/lib/format";

export function LoginForm() {
  const [mode, setMode] = useState<"in" | "up">("in");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [invite, setInvite] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const params = useSearchParams();
  const stats = useQuery({ queryKey: ["stats"], queryFn: api.stats, refetchInterval: 15000 });
  const up = mode === "up";

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      if (up) await api.signup(username.trim(), password, invite.trim() || undefined);
      else await api.login(username.trim(), password);
      const next = params.get("next");
      window.location.assign(next && next.startsWith("/") && !next.startsWith("//") ? next : "/markets");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong.");
      setBusy(false);
    }
  }

  const tab = (on: boolean) =>
    `min-h-11 rounded-[7px] font-medium transition-colors ${on ? "bg-line text-white" : "bg-transparent text-soft hover:text-white"}`;

  return (
    <div className="flex min-h-screen flex-wrap bg-bg">
      <div className="flex min-w-0 flex-[1_1_420px] flex-col justify-between gap-12 border-b border-line bg-panel px-6 py-10 sm:px-12 sm:py-14 md:border-b-0 md:border-r">
        <div className="font-display text-2xl font-bold tracking-tight text-white">HiveBets</div>
        <div className="flex flex-col gap-5">
          <p className="font-display text-[34px] font-semibold leading-[1.08] tracking-tight sm:text-[44px]">Trade on what happens next.</p>
          <p className="max-w-[440px] text-base text-soft">
            Yes-or-no markets that open, move with the news and settle in minutes. You start with $1,000 of play money.
          </p>
        </div>
        <dl className="flex flex-wrap gap-x-10 gap-y-4">
          <div>
            <dt className="text-xs text-mute">Traders online</dt>
            <dd className="mt-1 font-mono text-[22px]">{number(stats.data?.tradersOnline ?? 0)}</dd>
          </div>
          <div>
            <dt className="text-xs text-mute">Starting bankroll</dt>
            <dd className="mt-1 font-mono text-[22px]">$1,000</dd>
          </div>
          <div>
            <dt className="text-xs text-mute">Simulated markets settle in</dt>
            <dd className="mt-1 font-mono text-[22px] text-amber">5–15 min</dd>
          </div>
        </dl>
      </div>

      <div className="flex min-w-0 flex-[1_1_420px] items-center justify-center px-6 py-14">
        <form onSubmit={submit} className="flex w-full max-w-[400px] flex-col gap-6" noValidate>
          <div role="tablist" aria-label="Account" className="grid grid-cols-2 gap-1 rounded-[10px] border border-line bg-card p-1">
            <button type="button" role="tab" aria-selected={!up} data-testid="tab-sign-in" onClick={() => { setMode("in"); setError(null); }} className={tab(!up)}>
              Sign in
            </button>
            <button type="button" role="tab" aria-selected={up} data-testid="tab-sign-up" onClick={() => { setMode("up"); setError(null); }} className={tab(up)}>
              Create account
            </button>
          </div>

          <h1 className="font-display text-[28px] font-semibold tracking-tight">{up ? "Create your account" : "Welcome back"}</h1>

          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <label htmlFor="login-username" className="text-[13px] text-soft">Username</label>
              <input
                id="login-username"
                data-testid="login-username"
                className="field"
                autoComplete="username"
                placeholder="e.g. quiet-quant-0502"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="login-password" className="text-[13px] text-soft">Password</label>
              <input
                id="login-password"
                data-testid="login-password"
                className="field"
                type="password"
                autoComplete={up ? "new-password" : "current-password"}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
              {up && <span className="text-xs text-mute">At least 8 characters. Usernames use a–z, 0–9, - and _.</span>}
            </div>
            {up && (
              <div className="flex flex-col gap-1.5">
                <label htmlFor="signup-invite" className="text-[13px] text-soft">
                  Invite code <span className="text-mute">(optional)</span>
                </label>
                <input id="signup-invite" data-testid="signup-invite" className="field font-mono" value={invite} onChange={(e) => setInvite(e.target.value)} autoComplete="off" />
                <span className="text-xs text-mute">AI traders join with a code so they are labelled as AI.</span>
              </div>
            )}
          </div>

          {error && (
            <p role="alert" data-testid="login-error" className="rounded-lg border border-no px-3 py-2 text-[13px] text-soft">
              {error}
            </p>
          )}

          <button type="submit" data-testid="login-submit" disabled={busy} className="btn btn-primary min-h-12 w-full">
            {busy ? "Please wait…" : up ? "Create account and start with $1,000" : "Sign in"}
          </button>

          <p className="text-[13px] text-mute">
            {up ? "Play money only. Nothing here can be deposited or withdrawn." : "Too many wrong attempts lock sign-in for a minute."}
          </p>
        </form>
      </div>
    </div>
  );
}
