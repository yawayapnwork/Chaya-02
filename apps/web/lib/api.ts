import { apiBaseUrl } from "./config.ts";

/** Mirrors dev.chaya.api.health.HealthService.HealthReport. */
export interface HealthReport {
  status: "UP" | "DEGRADED" | "DOWN";
  database: string;
  storage: string;
  identityProvider: string;
  malwareScanner: string;
  processing: { status: string; queuedJobs: number | null; runningJobs: number | null; oldestQueuedAgeSeconds: number | null };
  checkedAt: string;
}

export type BackendStatus =
  | { reachable: true; health: HealthReport; version: { name: string; version: string; apiVersion: string } | null }
  | { reachable: false; reason: string };

async function getJson<T>(url: string, acceptStatuses: number[] = []): Promise<T> {
  const res = await fetch(url, { cache: "no-store", signal: AbortSignal.timeout(8000) });
  if (!res.ok && !acceptStatuses.includes(res.status)) throw new Error(`${url} responded HTTP ${res.status}`);
  return (await res.json()) as T;
}

export async function fetchBackendStatus(): Promise<BackendStatus> {
  const base = (() => {
    try {
      return `${apiBaseUrl()}/api/v1`;
    } catch (e) {
      return e instanceof Error ? e : new Error(String(e));
    }
  })();
  if (base instanceof Error) return { reachable: false, reason: base.message };
  try {
    // 503 carries the same report with the failing component named: show it rather than "unreachable".
    const health = await getJson<HealthReport>(`${base}/health`, [503]);
    const version = await getJson<{ name: string; version: string; apiVersion: string }>(`${base}/version`).catch(() => null);
    return { reachable: true, health, version };
  } catch (e) {
    return { reachable: false, reason: e instanceof Error ? e.message : String(e) };
  }
}
