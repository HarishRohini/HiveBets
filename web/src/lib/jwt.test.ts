import { describe, expect, it } from "vitest";
import { decodeSession } from "./jwt";

function token(payload: object): string {
  const b64 = (s: string) => Buffer.from(s).toString("base64").replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
  return `${b64('{"alg":"RS256"}')}.${b64(JSON.stringify(payload))}.sig`;
}

describe("decodeSession", () => {
  it("reads the claims", () => {
    const c = decodeSession(token({ sub: "u1", name: "admin", roles: ["ADMIN", "TRADER"], acct: "HUMAN", exp: 2_000_000_000 }), 1_000);
    expect(c).toEqual({ sub: "u1", name: "admin", roles: ["ADMIN", "TRADER"], acct: "HUMAN", exp: 2_000_000_000 });
  });
  it("handles unicode names", () => {
    expect(decodeSession(token({ sub: "u2", name: "zoë", roles: [] }))?.name).toBe("zoë");
  });
  it("rejects expired, malformed and missing tokens", () => {
    expect(decodeSession(token({ sub: "u1", exp: 10 }), 100)).toBeNull();
    expect(decodeSession("not-a-jwt")).toBeNull();
    expect(decodeSession("a.b.c")).toBeNull();
    expect(decodeSession(undefined)).toBeNull();
  });
});
