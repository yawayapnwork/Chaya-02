import { apiBaseUrl } from "@/lib/config";
import { runtimePublicConfig } from "@/lib/runtime-config";

// Readiness of the web app: 200 only if it can actually serve a working app, i.e.
//   * the browser configuration (CHAYA_PUBLIC_*) is complete -- without it every page fails client-side, and
//   * the backend answers its health endpoint over the server-side network path (CHAYA_API_BASE_URL).
// The backend being DOWN (its own 503) is reported in the body but does not make the web app unready: the backend is
// reachable, and the web app's /status page is exactly where users then learn what is broken. Unreachable is not ready.
// Liveness (process only) is /api/health/live.
export const dynamic = "force-dynamic";

export async function GET() {
  const problems: string[] = [];
  const { missing } = runtimePublicConfig();
  if (missing.length) problems.push(`browser configuration missing: ${missing.join(", ")}`);

  let backend: { reachable: boolean; status?: string; httpStatus?: number } = { reachable: false };
  try {
    const res = await fetch(`${apiBaseUrl()}/api/v1/health`, { cache: "no-store", signal: AbortSignal.timeout(3000) });
    const body = (await res.json().catch(() => ({}))) as { status?: string };
    backend = { reachable: true, status: body.status, httpStatus: res.status };
    if (res.status !== 200 && res.status !== 503) problems.push(`backend health answered HTTP ${res.status}`);
  } catch (e) {
    problems.push(`backend unreachable: ${e instanceof Error ? e.message : String(e)}`);
  }

  const ready = problems.length === 0;
  return Response.json({ status: ready ? "UP" : "DOWN", backend, problems }, { status: ready ? 200 : 503, headers: { "Cache-Control": "no-store" } });
}
