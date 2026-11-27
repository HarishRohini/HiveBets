export interface SessionClaims {
  sub: string;
  name: string;
  roles: string[];
  acct: string;
  exp?: number;
}

function b64urlDecode(s: string): string {
  let b = s.replace(/-/g, "+").replace(/_/g, "/");
  while (b.length % 4) b += "=";
  const bin = atob(b);
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/**
 * Reads the JWT payload without checking the signature. Only for UI routing hints;
 * the gateway verifies every API call. Returns null for malformed or expired tokens.
 */
export function decodeSession(token: string | undefined | null, nowSec = Math.floor(Date.now() / 1000)): SessionClaims | null {
  if (!token) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const c = JSON.parse(b64urlDecode(parts[1]));
    if (typeof c.sub !== "string") return null;
    if (typeof c.exp === "number" && c.exp < nowSec) return null;
    const roles = Array.isArray(c.roles) ? c.roles.map(String) : typeof c.roles === "string" ? c.roles.split(",") : [];
    return { sub: c.sub, name: String(c.name ?? ""), roles, acct: String(c.acct ?? "HUMAN"), exp: c.exp };
  } catch {
    return null;
  }
}
