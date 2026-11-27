import { NextResponse, type NextRequest } from "next/server";
import { decodeSession } from "@/lib/jwt";

const ADMIN_PATHS = ["/analytics", "/admin"];

/** Routing hints only: the gateway enforces every rule again on the API. */
export function proxy(req: NextRequest) {
  const { pathname, search } = req.nextUrl;
  const claims = decodeSession(req.cookies.get("hb_session")?.value);

  if (pathname === "/login") {
    if (claims) return NextResponse.redirect(new URL("/markets", req.url));
    return NextResponse.next();
  }
  if (!claims) {
    const url = new URL("/login", req.url);
    if (pathname !== "/") url.searchParams.set("next", pathname + search);
    return NextResponse.redirect(url);
  }
  if (ADMIN_PATHS.some((p) => pathname === p || pathname.startsWith(p + "/")) && !claims.roles.includes("ADMIN")) {
    const url = new URL("/markets", req.url);
    url.searchParams.set("denied", pathname.slice(1));
    return NextResponse.redirect(url);
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/|favicon.ico|.*\\.(?:png|svg|ico|txt|js|css)$).*)"],
};
