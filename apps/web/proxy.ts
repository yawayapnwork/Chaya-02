// Per-request security headers (Next.js 16 Proxy, formerly Middleware). The CSP names the API and identity-provider
// origins of the RUNNING container, which next.config.ts cannot know: it is evaluated when the image is built.
// The static headers (Referrer-Policy, X-Frame-Options, ...) stay in next.config.ts.
import { NextResponse, type NextRequest } from "next/server";
import { contentSecurityPolicy } from "@/lib/security-headers";

export function proxy(request: NextRequest) {
  // Same precedence as lib/runtime-config.ts: runtime CHAYA_PUBLIC_* first, build-time NEXT_PUBLIC_* second.
  const { enforced, reportOnly } = contentSecurityPolicy(
    {
      apiBaseUrl: process.env.CHAYA_PUBLIC_API_BASE_URL || process.env.NEXT_PUBLIC_API_BASE_URL,
      oidcIssuer: process.env.CHAYA_PUBLIC_OIDC_ISSUER || process.env.NEXT_PUBLIC_OIDC_ISSUER,
    },
    { isDev: process.env.NODE_ENV === "development", pathname: request.nextUrl.pathname },
  );
  const response = NextResponse.next();
  response.headers.set("Content-Security-Policy", enforced);
  response.headers.set("Content-Security-Policy-Report-Only", reportOnly);
  return response;
}

export const config = {
  // Documents and API routes; build output and the favicon carry no policy of their own.
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
