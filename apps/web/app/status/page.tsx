import { fetchBackendStatus } from "@/lib/api";

export const dynamic = "force-dynamic";

export default async function StatusPage() {
  const status = await fetchBackendStatus();
  return (
    <main className="mx-auto max-w-2xl p-8">
      <h1 className="text-2xl font-semibold">System status</h1>
      {status.reachable ? (
        <dl className="mt-4 grid grid-cols-[10rem_1fr] gap-2">
          <dt>Backend health</dt>
          <dd>{status.health.status}</dd>
          <dt>Backend version</dt>
          <dd>{status.version.version}</dd>
          <dt>API version</dt>
          <dd>{status.version.apiVersion}</dd>
        </dl>
      ) : (
        <p role="alert" className="mt-4 text-red-700">
          Backend unreachable: {status.reason}
        </p>
      )}
    </main>
  );
}
