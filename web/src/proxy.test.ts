import { describe, expect, it } from "vitest";
import { NextRequest } from "next/server";
import { proxy } from "./proxy";

function token(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "RS256" })}.${b64(claims)}.sig`;
}

const trader = token({ sub: "u1", name: "alice", roles: ["TRADER"], acct: "HUMAN", exp: 4102444800 });
const admin = token({ sub: "u2", name: "admin", roles: ["ADMIN", "TRADER"], acct: "HUMAN", exp: 4102444800 });
const expired = token({ sub: "u1", name: "alice", roles: ["TRADER"], exp: 1 });

function run(path: string, session?: string) {
  const req = new NextRequest("http://localhost:3000" + path, {
    headers: session ? { cookie: `hb_session=${session}` } : {},
  });
  const res = proxy(req);
  return { status: res.status, location: res.headers.get("location") };
}

describe("proxy routing hints", () => {
  it("sends signed-out visitors to /login with the page they wanted", () => {
    const r = run("/portfolio?tab=open");
    expect(r.status).toBe(307);
    const u = new URL(r.location!);
    expect(u.pathname).toBe("/login");
    expect(u.searchParams.get("next")).toBe("/portfolio?tab=open");
  });

  it("does not add next= for the home page", () => {
    expect(new URL(run("/").location!).search).toBe("");
  });

  it("treats an expired token as signed out", () => {
    expect(new URL(run("/markets", expired).location!).pathname).toBe("/login");
  });

  it("lets signed-in traders through", () => {
    expect(run("/markets", trader).location).toBeNull();
    expect(run("/markets/abc", trader).location).toBeNull();
  });

  it("redirects non-admins away from admin pages", () => {
    for (const p of ["/analytics", "/admin", "/admin/anything"]) {
      const u = new URL(run(p, trader).location!);
      expect(u.pathname).toBe("/markets");
      expect(u.searchParams.get("denied")).toBe(p.slice(1));
    }
  });

  it("lets admins into admin pages", () => {
    expect(run("/analytics", admin).location).toBeNull();
    expect(run("/admin", admin).location).toBeNull();
  });

  it("does not treat look-alike paths as admin pages", () => {
    expect(run("/administrators", trader).location).toBeNull();
  });

  it("sends signed-in users away from /login", () => {
    expect(new URL(run("/login", trader).location!).pathname).toBe("/markets");
    expect(run("/login").location).toBeNull();
  });
});
