import { fetchBackendStatus } from "@/lib/api";

export const dynamic = "force-dynamic";

const TONE: Record<string, string> = {
  UP: "text-green-800",
  IDLE: "text-green-800",
  ACTIVE: "text-green-800",
  DEGRADED: "font-semibold text-amber-800",
  STALLED: "font-semibold text-amber-800",
  DISABLED: "font-semibold text-amber-800",
  DOWN: "font-semibold text-red-800",
  UNKNOWN: "font-semibold text-red-800",
};

function Value({ v }: { v: string }) {
  return <span className={TONE[v] ?? ""}>{v}</span>;
}

export default async function StatusPage() {
  const status = await fetchBackendStatus();
  if (!status.reachable) {
    return (
      <main className="mx-auto max-w-2xl p-8">
        <h1 className="text-2xl font-semibold">System status</h1>
        <p role="alert" className="mt-4 text-red-700">Backend unreachable: {status.reason}</p>
      </main>
    );
  }
  const h = status.health;
  return (
    <main className="mx-auto max-w-2xl p-8">
      <h1 className="text-2xl font-semibold">System status</h1>
      {h.status !== "UP" && (
        <p role="alert" className={`mt-4 rounded border p-3 ${h.status === "DOWN" ? "border-red-300 bg-red-50 text-red-900" : "border-amber-400 bg-amber-50 text-amber-900"}`}>
          {h.status === "DOWN"
            ? "The platform is DOWN: a core dependency is unreachable (see below). Requests that need it fail with HTTP 503."
            : "The platform is DEGRADED: it works, but uploads cannot be accepted or processing is not progressing (see below)."}
        </p>
      )}
      <dl className="mt-4 grid grid-cols-[12rem_1fr] gap-2">
        <dt>Overall</dt><dd><Value v={h.status} /></dd>
        <dt>Database</dt><dd><Value v={h.database} /></dd>
        <dt>Object storage</dt><dd><Value v={h.storage} /></dd>
        <dt>Identity provider</dt><dd><Value v={h.identityProvider} /></dd>
        <dt>Malware scanner</dt>
        <dd>
          <Value v={h.malwareScanner} />
          {h.malwareScanner !== "UP" && <span className="text-sm text-zinc-600"> — uploads are quarantined, not accepted</span>}
        </dd>
        <dt>Processing queue</dt>
        <dd>
          <Value v={h.processing.status} />
          {h.processing.queuedJobs != null && (
            <span className="text-sm text-zinc-600">
              {" "}— {h.processing.queuedJobs} queued, {h.processing.runningJobs} running
              {h.processing.oldestQueuedAgeSeconds != null && `, oldest waiting ${Math.round(h.processing.oldestQueuedAgeSeconds / 60)} min`}
            </span>
          )}
          {h.processing.status === "STALLED" && <div className="text-sm text-amber-900">No worker is claiming jobs. Check that a reconstruction worker is running.</div>}
        </dd>
        <dt>Checked at</dt><dd>{h.checkedAt}</dd>
        {status.version && (
          <>
            <dt>Backend version</dt><dd>{status.version.version}</dd>
            <dt>API version</dt><dd>{status.version.apiVersion}</dd>
          </>
        )}
      </dl>
    </main>
  );
}
