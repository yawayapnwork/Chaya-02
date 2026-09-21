import { apiBaseUrl } from "./config.ts";

export type BackendStatus =
  | { reachable: true; health: { status: string }; version: { name: string; version: string; apiVersion: string } }
  | { reachable: false; reason: string };

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url, { cache: "no-store", signal: AbortSignal.timeout(3000) });
  if (!res.ok) throw new Error(`${url} responded HTTP ${res.status}`);
  return (await res.json()) as T;
}

export async function fetchBackendStatus(): Promise<BackendStatus> {
  try {
    const base = `${apiBaseUrl()}/api/v1`;
    const [health, version] = await Promise.all([
      getJson<{ status: string }>(`${base}/health`),
      getJson<{ name: string; version: string; apiVersion: string }>(`${base}/version`),
    ]);
    return { reachable: true, health, version };
  } catch (e) {
    return { reachable: false, reason: e instanceof Error ? e.message : String(e) };
  }
}
