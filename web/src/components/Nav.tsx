"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { money, number } from "@/lib/format";
import { useInvalidateOn, useMe } from "@/lib/hooks";

const LINKS = [
  { href: "/markets", label: "Markets", id: "nav-markets" },
  { href: "/portfolio", label: "Portfolio", id: "nav-portfolio" },
  { href: "/leaderboard", label: "Leaderboard", id: "nav-leaderboard" },
];

export function Nav() {
  const path = usePathname();
  const me = useMe();
  const isAdmin = !!me.data?.roles?.includes("ADMIN");
  const stats = useQuery({ queryKey: ["stats"], queryFn: api.stats, refetchInterval: 15000 });
  const portfolio = useQuery({ queryKey: ["portfolio"], queryFn: api.portfolio, enabled: !!me.data, refetchInterval: 30000 });
  useInvalidateOn(me.data ? ["user"] : [], [["portfolio"]]);

  const links = [...LINKS];
  const active = (href: string) => path === href || path.startsWith(href + "/");

  async function signOut() {
    try {
      await api.logout();
    } finally {
      window.location.assign("/login");
    }
  }

  return (
    <nav aria-label="Main" className="flex flex-wrap items-center gap-x-8 gap-y-3 border-b border-line bg-panel px-4 py-3 sm:px-6">
      <Link href="/markets" data-testid="nav-home" className="py-2.5 font-display text-xl font-bold tracking-tight text-white">
        HiveBets
      </Link>
      <div className="flex flex-auto flex-wrap gap-1">
        {links.map((l) => (
          <Link
            key={l.href}
            href={l.href}
            data-testid={l.id}
            aria-current={active(l.href) ? "page" : undefined}
            className={`rounded-lg px-3.5 py-3 ${active(l.href) ? "bg-raised font-medium text-white" : "text-soft hover:text-white"}`}
          >
            {l.label}
          </Link>
        ))}
        {isAdmin && (
          <>
            <Link
              href="/analytics"
              data-testid="nav-analytics"
              aria-current={active("/analytics") ? "page" : undefined}
              className={`rounded-lg px-3.5 py-3 ${active("/analytics") ? "bg-raised font-medium text-white" : "text-soft hover:text-white"}`}
            >
              Analytics <span className="ml-1 rounded-full bg-no-deep px-2 py-0.5 text-[11px] text-amber">Admin</span>
            </Link>
            <Link
              href="/admin"
              data-testid="nav-admin"
              aria-current={active("/admin") ? "page" : undefined}
              className={`rounded-lg px-3.5 py-3 ${active("/admin") ? "bg-raised font-medium text-white" : "text-soft hover:text-white"}`}
            >
              Admin
            </Link>
          </>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-3 text-[13px] text-mute">
        <span className="flex items-center gap-2" data-testid="traders-online">
          <span className="inline-block h-2 w-2 rounded-full bg-yes" aria-hidden="true" />
          {number(stats.data?.tradersOnline ?? 0)} traders online
        </span>
        {portfolio.data && (
          <span data-testid="nav-cash" title="Available cash" className="rounded-lg border border-line px-3 py-2 font-mono text-ink">
            {money(portfolio.data.wallet.availableCents)}
          </span>
        )}
        {me.data && (
          <span className="flex items-center gap-2">
            <span data-testid="nav-username" className="text-soft">
              {me.data.username}
            </span>
            <button type="button" data-testid="nav-sign-out" onClick={signOut} className="btn btn-ghost min-h-[40px] px-3 text-[13px]">
              Sign out
            </button>
          </span>
        )}
      </div>
    </nav>
  );
}

export function Shell({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen">
      <Nav />
      <main className="mx-auto flex max-w-[1360px] flex-col gap-6 px-4 pb-10 pt-8 sm:px-6 [&>*]:min-w-0">{children}</main>
    </div>
  );
}

export function PageHeader({ title, sub, children }: { title: string; sub?: string; children?: React.ReactNode }) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-x-8 gap-y-4">
      <div className="flex flex-col gap-1.5">
        <h1 className="font-display text-[32px] font-semibold tracking-tight">{title}</h1>
        {sub && <p className="text-mute">{sub}</p>}
      </div>
      {children && <div className="flex flex-wrap items-center gap-2">{children}</div>}
    </header>
  );
}
