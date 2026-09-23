"use client";

import { type ReactNode, useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { NotSignedInError, signIn, userManager } from "@/lib/auth";
import { ApiError, type Venue, listVenues } from "@/lib/capture-api";
import {
  type Audit,
  type Coverage,
  type Failures,
  type JobAction,
  type JobStatus,
  type Jobs,
  type OpsAccess,
  type OpsSection,
  type Overview,
  type Rescans,
  type SearchAnalytics,
  type Storage,
  getAudit,
  getCoverage,
  getFailures,
  getJobs,
  getOpsAccess,
  getOverview,
  getRescans,
  getSearchAnalytics,
  getStorage,
  performJobAction,
} from "@/lib/ops-api";
import {
  JOB_STATUSES,
  type LoadFailure,
  classifyFailure,
  formatAge,
  formatDuration,
  formatMs,
  formatPercent,
  gapLabel,
  jobStatusLabel,
  reconstructionQualityLabel,
  zeroResultRate,
} from "@/lib/ops-view";
import { explainStageError, stageLabel } from "@/lib/pipeline-view";
import { formatBytes } from "@/lib/upload-plan";
import { formatDate } from "@/lib/viewer-format";

// ---- loading model --------------------------------------------------------------------------------

type Load<T> =
  | { state: "loading" }
  | { state: "forbidden" }
  | { state: "ready"; data: T; refreshing: boolean }
  | { state: "failed"; kind: LoadFailure; message: string };

function toFailure(e: unknown): { state: "failed"; kind: LoadFailure; message: string } {
  if (e instanceof NotSignedInError) return { state: "failed", kind: "signed-out", message: e.message };
  if (e instanceof ApiError) return { state: "failed", kind: classifyFailure(e.status), message: e.message };
  return { state: "failed", kind: "error", message: e instanceof Error ? e.message : String(e) };
}

/**
 * Runs `fetcher` whenever it (or `refresh`) changes. A null fetcher means the caller's role may not read the
 * section, so nothing is requested. While a refresh is in flight the previous data stays visible (marked
 * refreshing) instead of blanking an operational view.
 */
function useSection<T>(fetcher: (() => Promise<T>) | null, refresh: number): Load<T> {
  const [result, setResult] = useState<{ fetcher: () => Promise<T>; refresh: number; load: Load<T> } | null>(null);
  useEffect(() => {
    if (!fetcher) return;
    let cancelled = false;
    fetcher().then(
      (data) => {
        if (!cancelled) setResult({ fetcher, refresh, load: { state: "ready", data, refreshing: false } });
      },
      (e) => {
        if (!cancelled) setResult({ fetcher, refresh, load: toFailure(e) });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [fetcher, refresh]);
  if (!fetcher) return { state: "forbidden" };
  if (!result || result.fetcher !== fetcher) return { state: "loading" };
  if (result.refresh !== refresh) {
    return result.load.state === "ready" ? { ...result.load, refreshing: true } : { state: "loading" };
  }
  return result.load;
}

// ---- presentation primitives -----------------------------------------------------------------------

const SECTION_ROLES: Record<OpsSection, string> = {
  OVERVIEW: "admin, venue-manager, operator or viewer",
  COVERAGE: "admin, venue-manager or operator",
  JOBS: "admin, venue-manager or operator",
  FAILURES: "admin, venue-manager or operator",
  STORAGE: "admin, venue-manager or operator",
  RESCANS: "admin, venue-manager or operator",
  SEARCH_ANALYTICS: "admin or venue-manager",
  AUDIT: "admin or venue-manager",
};

const TH = "border-b px-2 py-1.5 text-left text-xs font-semibold uppercase tracking-wide text-zinc-500";
const TD = "border-b px-2 py-1.5 align-top";

function Section<T>({
  id,
  title,
  section,
  load,
  isEmpty,
  emptyText,
  onRetry,
  controls,
  children,
}: {
  id: string;
  title: string;
  section: OpsSection;
  load: Load<T>;
  isEmpty?: (data: T) => boolean;
  emptyText?: string;
  onRetry: () => void;
  controls?: ReactNode;
  children: (data: T) => ReactNode;
}) {
  let body: ReactNode;
  if (load.state === "loading") {
    body = <p className="text-sm text-zinc-500" aria-busy="true">Loading…</p>;
  } else if (load.state === "forbidden" || (load.state === "failed" && load.kind === "unauthorized")) {
    body = (
      <p className="text-sm text-zinc-600" data-testid={`${id}-unauthorized`}>
        Not available for your role. This section requires {SECTION_ROLES[section]}.
      </p>
    );
  } else if (load.state === "failed" && load.kind === "signed-out") {
    body = (
      <p role="alert" className="text-sm text-red-800">
        Your session has ended.{" "}
        <button className="underline" onClick={() => signIn("/ops")}>Sign in again</button>
      </p>
    );
  } else if (load.state === "failed") {
    body = (
      <div role="alert" className="rounded border border-red-300 bg-red-50 p-2 text-sm text-red-900">
        Could not load this section: {load.message}{" "}
        <button className="underline" onClick={onRetry}>Try again</button>
      </div>
    );
  } else if (isEmpty?.(load.data)) {
    body = <p className="text-sm text-zinc-600">{emptyText}</p>;
  } else {
    body = children(load.data);
  }
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="rounded border p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 id={`${id}-title`} className="text-lg font-semibold">
          {title}
          {load.state === "ready" && load.refreshing && <span className="ml-2 text-xs font-normal text-zinc-500">refreshing…</span>}
        </h2>
        {controls}
      </div>
      {body}
    </section>
  );
}

function When({ iso }: { iso: string | null | undefined }) {
  if (!iso) return <span className="text-zinc-500">—</span>;
  return <time dateTime={iso}>{formatDate(iso)}</time>;
}

function DaysPicker({ value, onChange, label }: { value: number; onChange: (d: number) => void; label: string }) {
  return (
    <label className="text-sm">
      {label}{" "}
      <select className="rounded border px-1 py-0.5" value={value} onChange={(e) => onChange(Number(e.target.value))}>
        {[7, 30, 90, 365].map((d) => (
          <option key={d} value={d}>last {d} days</option>
        ))}
      </select>
    </label>
  );
}

function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="rounded border px-3 py-2">
      <div className="text-xs uppercase tracking-wide text-zinc-500">{label}</div>
      <div className="text-xl font-semibold tabular-nums">{value}</div>
      {hint && <div className="text-xs text-zinc-500">{hint}</div>}
    </div>
  );
}

function ActionButton({
  label,
  action,
  allowedByRole,
  busy,
  onRun,
}: {
  label: string;
  action: JobAction;
  allowedByRole: boolean;
  busy: boolean;
  onRun: () => void;
}) {
  if (!action.available) {
    return <span className="text-xs text-zinc-500">{label}: {action.reason}</span>;
  }
  if (!allowedByRole) {
    return <span className="text-xs text-zinc-500">{label} available (your role cannot run it)</span>;
  }
  return (
    <button className="rounded border px-2 py-0.5 text-xs disabled:opacity-50" disabled={busy} onClick={onRun}>
      {label}
    </button>
  );
}

// ---- dashboard -------------------------------------------------------------------------------------

type Phase = "checking" | "signed-out" | "no-role" | "ready" | "error";

const NAV: { id: string; label: string }[] = [
  { id: "overview", label: "Overview" },
  { id: "version", label: "Current version" },
  { id: "freshness", label: "Freshness" },
  { id: "coverage", label: "Coverage gaps" },
  { id: "jobs", label: "Jobs" },
  { id: "failures", label: "Failures" },
  { id: "storage", label: "Storage & cost" },
  { id: "search", label: "POI & search" },
  { id: "rescans", label: "Re-scans" },
  { id: "audit", label: "Audit" },
];

export default function OpsDashboard() {
  const searchParams = useSearchParams();
  const prefillVenueId = searchParams.get("venue");

  const [phase, setPhase] = useState<Phase>("checking");
  const [pageError, setPageError] = useState<string | null>(null);
  const [venues, setVenues] = useState<Venue[]>([]);
  const [venueId, setVenueId] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [refreshedAt, setRefreshedAt] = useState<Date | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [jobFilter, setJobFilter] = useState<JobStatus | null>(null);
  const [failureDays, setFailureDays] = useState(30);
  const [searchDays, setSearchDays] = useState(30);
  const [actionBusy, setActionBusy] = useState(false);
  const [actionMessage, setActionMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const reload = useCallback(() => {
    setRefresh((r) => r + 1);
    setRefreshedAt(new Date());
  }, []);

  // ---- sign-in and venues ----
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const user = await userManager().getUser();
        if (cancelled) return;
        if (!user || user.expired) return setPhase("signed-out");
        const vs = await listVenues();
        if (cancelled) return;
        setVenues(vs);
        const initial = vs.find((v) => v.id === prefillVenueId) ?? (vs.length === 1 ? vs[0] : undefined);
        if (initial) setVenueId(initial.id);
        setRefreshedAt(new Date());
        setPhase("ready");
      } catch (e) {
        if (cancelled) return;
        if (e instanceof NotSignedInError || (e instanceof ApiError && e.status === 401)) return setPhase("signed-out");
        if (e instanceof ApiError && e.status === 403) return setPhase("no-role");
        setPageError(e instanceof Error ? e.message : String(e));
        setPhase("error");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [prefillVenueId]);

  useEffect(() => {
    if (!autoRefresh || !venueId) return;
    const t = setInterval(reload, 30_000);
    return () => clearInterval(t);
  }, [autoRefresh, venueId, reload]);

  // ---- what this caller may see (decided by the server) ----
  const accessFetcher = useMemo(() => (venueId ? () => getOpsAccess(venueId) : null), [venueId]);
  const access = useSection<OpsAccess>(accessFetcher, refresh);
  // A stable string of the permitted sections, so a refresh of /access (a new object with the same content) does
  // not recreate every section's fetcher and blank the page.
  const allowedKey = access.state === "ready" ? permittedKey(access.data) : null;
  const mayControl = access.state === "ready" && access.data.mayControlProcessing;

  const overviewF = useMemo(() => gated(allowedKey, "OVERVIEW", () => getOverview(venueId)), [allowedKey, venueId]);
  const coverageF = useMemo(() => gated(allowedKey, "COVERAGE", () => getCoverage(venueId)), [allowedKey, venueId]);
  const jobsF = useMemo(() => gated(allowedKey, "JOBS", () => getJobs(venueId, jobFilter)), [allowedKey, venueId, jobFilter]);
  const failuresF = useMemo(() => gated(allowedKey, "FAILURES", () => getFailures(venueId, failureDays)), [allowedKey, venueId, failureDays]);
  const storageF = useMemo(() => gated(allowedKey, "STORAGE", () => getStorage(venueId)), [allowedKey, venueId]);
  const searchF = useMemo(() => gated(allowedKey, "SEARCH_ANALYTICS", () => getSearchAnalytics(venueId, searchDays)), [allowedKey, venueId, searchDays]);
  const rescansF = useMemo(() => gated(allowedKey, "RESCANS", () => getRescans(venueId)), [allowedKey, venueId]);
  const auditF = useMemo(() => gated(allowedKey, "AUDIT", () => getAudit(venueId)), [allowedKey, venueId]);

  const overview = useGated<Overview>(overviewF, refresh);
  const coverage = useGated<Coverage>(coverageF, refresh);
  const jobs = useGated<Jobs>(jobsF, refresh);
  const failures = useGated<Failures>(failuresF, refresh);
  const storage = useGated<Storage>(storageF, refresh);
  const search = useGated<SearchAnalytics>(searchF, refresh);
  const rescans = useGated<Rescans>(rescansF, refresh);
  const audit = useGated<Audit>(auditF, refresh);

  const runAction = async (kind: "retry" | "cancel", jobId: string, action: JobAction, what: string) => {
    setActionBusy(true);
    setActionMessage(null);
    try {
      await performJobAction(venueId, kind, jobId, action);
      setActionMessage({ ok: true, text: `${kind === "retry" ? "Retry requested" : "Cancellation requested"} for ${what}.` });
      reload();
    } catch (e) {
      setActionMessage({ ok: false, text: `${kind === "retry" ? "Retry" : "Cancel"} failed for ${what}: ${e instanceof Error ? e.message : String(e)}` });
    } finally {
      setActionBusy(false);
    }
  };

  // ---- page-level states ----
  if (phase === "checking") return <main className="p-8" aria-busy="true">Loading…</main>;
  if (phase === "signed-out") {
    return (
      <main className="mx-auto max-w-2xl p-8">
        <h1 className="text-2xl font-semibold">Operations dashboard</h1>
        <p className="mt-2 text-zinc-600">Sign in with a venue staff account to see operations data.</p>
        <button className="mt-4 rounded bg-black px-3 py-1.5 text-white" onClick={() => signIn("/ops")}>Sign in</button>
      </main>
    );
  }
  if (phase === "no-role") {
    return (
      <main className="mx-auto max-w-2xl p-8">
        <h1 className="text-2xl font-semibold">Operations dashboard</h1>
        <p role="alert" className="mt-2 text-red-800" data-testid="ops-unauthorized">
          Your account has no venue role (admin, venue-manager, operator or viewer), so the operations dashboard is not available.
        </p>
      </main>
    );
  }
  if (phase === "error") {
    return (
      <main className="mx-auto max-w-2xl p-8">
        <h1 className="text-2xl font-semibold">Operations dashboard</h1>
        <p role="alert" className="mt-2 text-red-800">Could not load your venues: {pageError}</p>
        <button className="mt-3 underline" onClick={() => window.location.reload()}>Reload</button>
      </main>
    );
  }

  return (
    <main className="mx-auto w-full max-w-6xl space-y-4 p-4 sm:p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Operations dashboard</h1>
          <p className="text-sm text-zinc-600">
            Live data from the Chaya API. Nothing is estimated: missing data is shown as missing.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <label>
            Venue{" "}
            <select className="rounded border px-2 py-1" value={venueId} onChange={(e) => setVenueId(e.target.value)} data-testid="ops-venue">
              <option value="">Select…</option>
              {venues.map((v) => (
                <option key={v.id} value={v.id}>{v.name}</option>
              ))}
            </select>
          </label>
          <button className="rounded border px-2 py-1 disabled:opacity-50" disabled={!venueId} onClick={reload}>Refresh</button>
          <label className="flex items-center gap-1">
            <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} /> every 30 s
          </label>
          {refreshedAt && <span className="text-xs text-zinc-500">requested {refreshedAt.toLocaleTimeString()}</span>}
        </div>
      </header>

      {venues.length === 0 && (
        <p className="rounded border p-4 text-sm text-zinc-600" data-testid="ops-no-venues">
          No venues are assigned to your account. An administrator can grant access by adding the venue to your account&apos;s venue list.
        </p>
      )}
      {venues.length > 0 && !venueId && <p className="rounded border p-4 text-sm text-zinc-600">Select a venue to see its operations data.</p>}

      {venueId && access.state === "loading" && <p className="text-sm text-zinc-500" aria-busy="true">Checking your access…</p>}
      {venueId && access.state === "failed" && (
        <p role="alert" className="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-900">
          {access.kind === "not-found"
            ? "This venue does not exist or is not visible to your account."
            : access.kind === "unauthorized"
              ? "Your role does not permit the operations dashboard for this venue."
              : access.kind === "signed-out"
                ? "Your session has ended; sign in again."
                : `Could not check your access: ${access.message}`}{" "}
          {access.kind === "signed-out" ? (
            <button className="underline" onClick={() => signIn("/ops")}>Sign in</button>
          ) : (
            <button className="underline" onClick={reload}>Try again</button>
          )}
        </p>
      )}

      {venueId && access.state === "ready" && (
        <>
          <p className="text-xs text-zinc-500">
            Your roles: {access.data.roles.join(", ").toLowerCase() || "none"} ·{" "}
            {mayControl ? "you may retry and cancel processing" : "read-only access"}
          </p>
          <nav aria-label="Dashboard sections" className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
            {NAV.map((n) => (
              <a key={n.id} href={`#${n.id}`} className="underline">{n.label}</a>
            ))}
          </nav>

          {actionMessage && (
            <p role="status" className={`rounded border p-2 text-sm ${actionMessage.ok ? "border-green-300 bg-green-50 text-green-900" : "border-red-300 bg-red-50 text-red-900"}`}>
              {actionMessage.text}
            </p>
          )}

          {/* 1. Venue overview */}
          <Section id="overview" title="Venue overview" section="OVERVIEW" load={overview} onRetry={reload}>
            {(o) => (
              <div className="space-y-3">
                <dl className="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-1 text-sm">
                  <dt className="text-zinc-500">Venue</dt><dd>{o.venueName} <span className="font-mono text-xs text-zinc-500">({o.venueSlug})</span></dd>
                  <dt className="text-zinc-500">Timezone</dt><dd>{o.timezone}</dd>
                  <dt className="text-zinc-500">Created</dt><dd><When iso={o.venueCreatedAt} /></dd>
                  <dt className="text-zinc-500">Data as of</dt><dd><When iso={o.asOf} /> (server time)</dd>
                </dl>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  <Stat label="Floors" value={o.floorCount} />
                  <Stat label="Capture sessions" value={o.captureCount} />
                  <Stat label="POIs" value={o.poiCount} />
                  <Stat label="Jobs failed" value={o.jobCounts.FAILED} hint={`${o.jobCounts.QUEUED} queued · ${o.jobCounts.RUNNING} running`} />
                </div>
              </div>
            )}
          </Section>

          {/* 2. Current reconstruction version */}
          <Section
            id="version"
            title="Current reconstruction version"
            section="OVERVIEW"
            load={overview}
            onRetry={reload}
            isEmpty={(o) => o.floors.length === 0}
            emptyText="This venue has no floors yet, so there is no reconstruction."
          >
            {(o) => (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr>
                      <th className={TH}>Floor</th>
                      <th className={TH}>Finalized version</th>
                      <th className={TH}>Drafts</th>
                      <th className={TH}>Latest reconstruction</th>
                      <th className={TH}>Quality</th>
                      <th className={TH}>Viewer</th>
                    </tr>
                  </thead>
                  <tbody>
                    {o.floors.map((f) => {
                      const v = f.currentVersion;
                      const r = f.latestReconstruction;
                      const q = r ? reconstructionQualityLabel(r.runQuality, r.runStatus) : null;
                      return (
                        <tr key={f.floorId}>
                          <td className={TD}>L{f.level} · {f.name}</td>
                          <td className={TD}>
                            {v ? (
                              <>
                                v{v.versionNumber} · <When iso={v.finalizedAt} />
                                {v.incremental && (
                                  <div className="text-xs text-zinc-600">
                                    region re-scan · alignment confidence {v.alignmentConfidence?.toFixed(3) ?? "—"}, residual{" "}
                                    {v.alignmentResidualM != null ? `${v.alignmentResidualM.toFixed(3)} m` : "—"}
                                  </div>
                                )}
                              </>
                            ) : (
                              <span className="text-zinc-500">none finalized</span>
                            )}
                          </td>
                          <td className={`${TD} tabular-nums`}>{f.draftVersions}</td>
                          <td className={TD}>{r ? <When iso={r.generatedAt} /> : <span className="text-zinc-500">none</span>}</td>
                          <td className={TD}>
                            {q ? <span className={q.final ? "text-green-800" : "font-medium text-amber-800"}>{q.label}</span> : "—"}
                          </td>
                          <td className={TD}>
                            {r?.hasViewerAsset ? (
                              <Link className="underline" href={`/viewer?venue=${o.venueId}&floor=${f.floorId}`}>Open</Link>
                            ) : r ? (
                              <span className="text-zinc-500">no .ksplat</span>
                            ) : (
                              "—"
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Section>

          {/* 3. Scan freshness */}
          <Section
            id="freshness"
            title="Scan freshness"
            section="OVERVIEW"
            load={overview}
            onRetry={reload}
            isEmpty={(o) => o.floors.length === 0}
            emptyText="This venue has no floors yet."
          >
            {(o) => (
              <div className="space-y-2">
                <p className="text-xs text-zinc-500">
                  Ages are measured from stored capture and reconstruction timestamps against server time (<When iso={o.asOf} />).
                  Oldest first. A region re-scan refreshes only its region, not the whole floor.
                </p>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr>
                        <th className={TH}>Floor</th>
                        <th className={TH}>Last full-floor capture</th>
                        <th className={TH}>Last reconstruction</th>
                        <th className={TH}>Last region re-scan</th>
                        <th className={TH}>Last capture started</th>
                      </tr>
                    </thead>
                    <tbody>
                      {[...o.floors]
                        .sort((a, b) => (b.freshness.lastFullCaptureAgeSeconds ?? Infinity) - (a.freshness.lastFullCaptureAgeSeconds ?? Infinity))
                        .map((f) => (
                          <tr key={f.floorId}>
                            <td className={TD}>L{f.level} · {f.name}</td>
                            <td className={TD}>
                              <div className={f.freshness.lastFullCaptureAt ? "" : "font-medium text-amber-800"}>{formatAge(f.freshness.lastFullCaptureAgeSeconds)}</div>
                              <div className="text-xs text-zinc-500"><When iso={f.freshness.lastFullCaptureAt} /></div>
                            </td>
                            <td className={TD}>
                              <div>{formatAge(f.freshness.lastReconstructedAgeSeconds)}</div>
                              <div className="text-xs text-zinc-500"><When iso={f.freshness.lastReconstructedAt} /></div>
                            </td>
                            <td className={TD}>
                              <div>{formatAge(f.freshness.lastRegionRescanAgeSeconds)}</div>
                              <div className="text-xs text-zinc-500"><When iso={f.freshness.lastRegionRescanAt} /></div>
                            </td>
                            <td className={TD}><When iso={f.freshness.lastCaptureStartedAt} /></td>
                          </tr>
                        ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </Section>

          {/* 4. Coverage gaps */}
          <Section
            id="coverage"
            title="Coverage gaps"
            section="COVERAGE"
            load={coverage}
            onRetry={reload}
            isEmpty={(c) => c.floors.length === 0}
            emptyText="This venue has no floors yet."
          >
            {(c) => (
              <div className="space-y-3">
                <p className="text-xs text-zinc-500">
                  Measured coverage is recomputed by the capture planner from the stored room outline and reported positions of each
                  floor&apos;s most recent capture that has an outline. Floors without one have no coverage measurement.
                </p>
                <ul className="space-y-2">
                  {c.floors.map((f) => (
                    <li key={f.floorId} className="rounded border p-3 text-sm">
                      <div className="flex flex-wrap items-baseline justify-between gap-2">
                        <span className="font-medium">L{f.level} · {f.name}</span>
                        {f.measured?.coverageAvailable && (
                          <span className="tabular-nums">
                            {formatPercent(f.measured.coveragePercent)} covered · {f.measured.uncoveredAreaM2?.toFixed(1) ?? "—"} m² uncovered
                          </span>
                        )}
                      </div>
                      {f.gaps.length === 0 ? (
                        <p className="mt-1 text-green-800">No gaps recorded.</p>
                      ) : (
                        <ul className="mt-1 list-inside list-disc text-amber-900">
                          {f.gaps.map((g) => (
                            <li key={g}>
                              {gapLabel(g)}
                              {g === "COVERAGE_UNAVAILABLE" && f.measured?.coverageUnavailableReason ? `: ${f.measured.coverageUnavailableReason}` : ""}
                            </li>
                          ))}
                        </ul>
                      )}
                      {f.measured && (
                        <p className="mt-1 text-xs text-zinc-500">
                          Measured from capture <span className="font-mono">{f.measured.captureId.slice(0, 8)}</span> ({f.measured.captureStatus}), started{" "}
                          <When iso={f.measured.captureStartedAt} />
                        </p>
                      )}
                      {f.measured && f.measured.uncoveredZones.length > 0 && (
                        <table className="mt-2 text-xs">
                          <thead>
                            <tr><th className={TH}>Zone centroid (x, y)</th><th className={TH}>Area</th><th className={TH}>Reason</th></tr>
                          </thead>
                          <tbody>
                            {f.measured.uncoveredZones.map((z, i) => (
                              <tr key={i}>
                                <td className={`${TD} font-mono`}>{z.centroidX.toFixed(2)}, {z.centroidY.toFixed(2)}</td>
                                <td className={`${TD} tabular-nums`}>{z.areaM2.toFixed(2)} m²</td>
                                <td className={TD}>{z.reason}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </Section>

          {/* 5. Processing jobs */}
          <Section
            id="jobs"
            title="Processing jobs"
            section="JOBS"
            load={jobs}
            onRetry={reload}
            controls={
              jobFilter && (
                <button className="text-sm underline" onClick={() => setJobFilter(null)}>Show all statuses</button>
              )
            }
          >
            {(j) => (
              <div className="space-y-3">
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
                  {JOB_STATUSES.map((s) => (
                    <button
                      key={s.key}
                      className={`text-left ${jobFilter === s.key ? "ring-2 ring-black" : ""} rounded`}
                      aria-pressed={jobFilter === s.key}
                      onClick={() => setJobFilter(jobFilter === s.key ? null : s.key)}
                    >
                      <Stat label={s.label} value={j.counts[s.key] ?? 0} />
                    </button>
                  ))}
                </div>
                {j.jobs.length === 0 ? (
                  <p className="text-sm text-zinc-600">
                    {jobFilter ? `No ${jobStatusLabel(jobFilter).toLowerCase()} jobs.` : "No processing jobs have been created for this venue."}
                  </p>
                ) : (
                  <div className="overflow-x-auto">
                    <p className="mb-1 text-xs text-zinc-500">Most recent {j.jobs.length} (limit {j.limit}).</p>
                    <table className="w-full text-sm">
                      <thead>
                        <tr>
                          <th className={TH}>Stage</th>
                          <th className={TH}>Status</th>
                          <th className={TH}>Attempt</th>
                          <th className={TH}>Queued</th>
                          <th className={TH}>Started</th>
                          <th className={TH}>Finished</th>
                          <th className={TH}>Worker</th>
                          <th className={TH}>Error / actions</th>
                        </tr>
                      </thead>
                      <tbody>
                        {j.jobs.map((job) => (
                          <tr key={job.id}>
                            <td className={TD}>
                              {stageLabel(job.stage)}
                              <div className="font-mono text-xs text-zinc-500">{job.id.slice(0, 8)}</div>
                            </td>
                            <td className={`${TD} ${job.status === "FAILED" ? "text-red-700" : ""}`}>{jobStatusLabel(job.status)}</td>
                            <td className={`${TD} tabular-nums`}>{job.retryCount + 1} of {job.maxRetries + 1}</td>
                            <td className={TD}><When iso={job.queuedAt} /></td>
                            <td className={TD}><When iso={job.startedAt} /></td>
                            <td className={TD}><When iso={job.finishedAt} /></td>
                            <td className={`${TD} font-mono text-xs`}>{job.workerId ?? "—"}</td>
                            <td className={TD}>
                              {job.errorCode && (
                                <div className="text-red-800">
                                  <span className="font-mono text-xs">{job.errorCode}</span> — {explainStageError(job.errorCode, job.errorMessage, null)}
                                </div>
                              )}
                              <div className="mt-1 flex flex-col gap-1">
                                {job.status === "FAILED" && (
                                  <ActionButton label="Retry" action={job.retry} allowedByRole={mayControl} busy={actionBusy}
                                    onRun={() => runAction("retry", job.id, job.retry, stageLabel(job.stage))} />
                                )}
                                {(job.status === "QUEUED" || job.status === "RUNNING") && (
                                  <ActionButton label={job.cancel.via === "PIPELINE_RUN" ? "Cancel run" : "Cancel"} action={job.cancel}
                                    allowedByRole={mayControl} busy={actionBusy}
                                    onRun={() => runAction("cancel", job.id, job.cancel, stageLabel(job.stage))} />
                                )}
                              </div>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            )}
          </Section>

          {/* 6. Processing failures */}
          <Section
            id="failures"
            title="Processing failures"
            section="FAILURES"
            load={failures}
            onRetry={reload}
            controls={<DaysPicker label="Failure history:" value={failureDays} onChange={setFailureDays} />}
            isEmpty={(f) => f.failedRuns.length === 0 && f.failures.length === 0}
            emptyText={`No failed runs, and no stage failures in the last ${failureDays} days.`}
          >
            {(f) => (
              <div className="space-y-4">
                <div>
                  <h3 className="mb-1 text-sm font-semibold">Runs currently failed ({f.failedRuns.length})</h3>
                  {f.failedRuns.length === 0 ? (
                    <p className="text-sm text-zinc-600">No pipeline run is currently in the FAILED state.</p>
                  ) : (
                    <table className="w-full text-sm">
                      <thead>
                        <tr><th className={TH}>Failed at</th><th className={TH}>Stage</th><th className={TH}>Error</th><th className={TH}>Retry</th></tr>
                      </thead>
                      <tbody>
                        {f.failedRuns.map((r) => (
                          <tr key={r.runId}>
                            <td className={TD}><When iso={r.finishedAt} /></td>
                            <td className={TD}>{r.failureStage ? stageLabel(r.failureStage) : "—"}</td>
                            <td className={TD}>
                              <span className="font-mono text-xs">{r.failureCode}</span> — {explainStageError(r.failureCode, r.failureMessage, null)}
                              {r.failureMessage && <div className="text-xs text-zinc-600">{r.failureMessage}</div>}
                            </td>
                            <td className={TD}>
                              <ActionButton label="Retry run" action={r.retry} allowedByRole={mayControl} busy={actionBusy}
                                onRun={() => runAction("retry", r.runId, r.retry, `the run failed at ${r.failureStage ? stageLabel(r.failureStage) : "?"}`)} />
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>

                {f.countsByCode.length > 0 && (
                  <div>
                    <h3 className="mb-1 text-sm font-semibold">Failures by stage and code (last {f.days} days)</h3>
                    <ul className="flex flex-wrap gap-2 text-xs">
                      {f.countsByCode.map((c) => (
                        <li key={`${c.stage}:${c.errorCode}`} className="rounded border px-2 py-1">
                          {stageLabel(c.stage)} · <span className="font-mono">{c.errorCode ?? "no code"}</span> · <strong>{c.count}</strong>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                <div className="overflow-x-auto">
                  <h3 className="mb-1 text-sm font-semibold">Failure history (last {f.days} days, newest first)</h3>
                  {f.failures.length === 0 ? (
                    <p className="text-sm text-zinc-600">No stage failures in this period.</p>
                  ) : (
                    <table className="w-full text-sm">
                      <thead>
                        <tr>
                          <th className={TH}>Failed at</th>
                          <th className={TH}>Stage</th>
                          <th className={TH}>Attempt</th>
                          <th className={TH}>Error</th>
                          <th className={TH}>Exit</th>
                          <th className={TH}>Worker</th>
                          <th className={TH}>Job now</th>
                          <th className={TH}>Retry availability</th>
                        </tr>
                      </thead>
                      <tbody>
                        {f.failures.map((x) => (
                          <tr key={`${x.jobId}:${x.attempt}:${x.source}`}>
                            <td className={TD}><When iso={x.failedAt} /></td>
                            <td className={TD}>{stageLabel(x.stage)}</td>
                            <td className={`${TD} tabular-nums`}>{x.attempt}</td>
                            <td className={TD}>
                              <span className="font-mono text-xs">{x.errorCode ?? "—"}</span>
                              {x.errorMessage && <div className="text-xs text-zinc-700">{x.errorMessage}</div>}
                            </td>
                            <td className={`${TD} tabular-nums`}>{x.exitStatus ?? "—"}</td>
                            <td className={`${TD} font-mono text-xs`}>{x.workerId ?? "—"}</td>
                            <td className={TD}>{jobStatusLabel(x.currentJobStatus)}</td>
                            <td className={TD}>
                              {x.retry.available ? (
                                <span className="text-green-800">available{x.retry.via === "PIPELINE_RUN" ? " (retry the run above)" : ""}</span>
                              ) : (
                                <span className="text-xs text-zinc-600">{x.retry.reason}</span>
                              )}
                              {x.retry.available && x.retry.via === "JOB" && (
                                <div className="mt-1">
                                  <ActionButton label="Retry job" action={x.retry} allowedByRole={mayControl} busy={actionBusy}
                                    onRun={() => runAction("retry", x.jobId, x.retry, stageLabel(x.stage))} />
                                </div>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              </div>
            )}
          </Section>

          {/* 7. Storage / artifacts, processing time, cost */}
          <Section id="storage" title="Storage, artifacts and processing cost" section="STORAGE" load={storage} onRetry={reload}>
            {(s) => (
              <div className="space-y-4 text-sm">
                <p className="text-xs text-zinc-500">
                  Sizes are those recorded when each object was verified against object storage, not a live bucket listing.
                  Raw bucket <span className="font-mono">{s.rawBucket}</span> · derived bucket <span className="font-mono">{s.derivedBucket}</span>.
                </p>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  <Stat label="Derived artifacts" value={s.artifactCount} hint={formatBytes(s.artifactBytes)} />
                  <Stat
                    label="Raw media accepted"
                    value={s.rawMedia.find((m) => m.status === "ACCEPTED")?.count ?? 0}
                    hint={formatBytes(s.rawMedia.find((m) => m.status === "ACCEPTED")?.declaredBytes ?? 0)}
                  />
                  <Stat label="Last artifact" value={<span className="text-sm font-normal"><When iso={s.lastArtifactAt} /></span>} />
                  <Stat label="PII staging records" value={s.piiArtifactCount} hint={`${formatBytes(s.piiArtifactBytes)} recorded`} />
                </div>
                {s.piiPurgeIncompleteEvents > 0 && (
                  <p role="alert" className="rounded border border-amber-400 bg-amber-50 p-2 text-amber-900">
                    {s.piiPurgeIncompleteEvents} PII purge{s.piiPurgeIncompleteEvents === 1 ? " was" : "s were"} incomplete for this venue
                    (audit action pipeline.pii_purge_incomplete). Some PII staging objects may still exist in the derived bucket.
                  </p>
                )}
                <div className="grid gap-4 md:grid-cols-2">
                  <div>
                    <h3 className="mb-1 font-semibold">Raw capture media by status</h3>
                    {s.rawMedia.length === 0 ? (
                      <p className="text-zinc-600">No media uploaded.</p>
                    ) : (
                      <table className="w-full">
                        <thead><tr><th className={TH}>Status</th><th className={TH}>Files</th><th className={TH}>Declared size</th></tr></thead>
                        <tbody>
                          {s.rawMedia.map((m) => (
                            <tr key={m.status}>
                              <td className={TD}>{m.status}</td>
                              <td className={`${TD} tabular-nums`}>{m.count}</td>
                              <td className={`${TD} tabular-nums`}>{formatBytes(m.declaredBytes)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                  <div>
                    <h3 className="mb-1 font-semibold">Derived artifacts by kind</h3>
                    {s.artifacts.length === 0 ? (
                      <p className="text-zinc-600">No artifacts produced.</p>
                    ) : (
                      <table className="w-full">
                        <thead><tr><th className={TH}>Kind</th><th className={TH}>Count</th><th className={TH}>Size</th><th className={TH}>Partial</th></tr></thead>
                        <tbody>
                          {s.artifacts.map((a) => (
                            <tr key={a.kind}>
                              <td className={`${TD} font-mono text-xs`}>{a.kind}</td>
                              <td className={`${TD} tabular-nums`}>{a.count}</td>
                              <td className={`${TD} tabular-nums`}>{formatBytes(a.bytes)}</td>
                              <td className={`${TD} tabular-nums`}>{a.partialCount}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                </div>
                <div className="grid gap-4 md:grid-cols-2">
                  <div>
                    <h3 className="mb-1 font-semibold">Processing cost</h3>
                    {s.cost.available && s.cost.amount != null ? (
                      <p className="tabular-nums">{s.cost.amount} {s.cost.currency}</p>
                    ) : (
                      <div data-testid="ops-cost-unavailable">
                        <p className="font-medium">Cost data unavailable</p>
                        {s.cost.reason && <p className="text-xs text-zinc-600">{s.cost.reason}</p>}
                      </div>
                    )}
                  </div>
                  <div>
                    <h3 className="mb-1 font-semibold">Measured worker time by stage</h3>
                    {s.stageTime.length === 0 ? (
                      <p className="text-zinc-600">No stage has been executed.</p>
                    ) : (
                      <table className="w-full">
                        <thead><tr><th className={TH}>Stage</th><th className={TH}>Runs</th><th className={TH}>Total</th><th className={TH}>Average</th></tr></thead>
                        <tbody>
                          {s.stageTime.map((t) => (
                            <tr key={t.stage}>
                              <td className={TD}>{stageLabel(t.stage)}</td>
                              <td className={`${TD} tabular-nums`}>{t.executions}</td>
                              <td className={`${TD} tabular-nums`}>{formatDuration(t.totalSeconds)}</td>
                              <td className={`${TD} tabular-nums`}>{formatDuration(t.avgSeconds)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                </div>
              </div>
            )}
          </Section>

          {/* 8. POI and search analytics */}
          <Section
            id="search"
            title="POI and search analytics"
            section="SEARCH_ANALYTICS"
            load={search}
            onRetry={reload}
            controls={<DaysPicker label="Search period:" value={searchDays} onChange={setSearchDays} />}
          >
            {(a) => {
              const rate = zeroResultRate(a.zeroResultCount, a.queryCount);
              return (
                <div className="space-y-4 text-sm">
                  <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                    <Stat label="POIs" value={a.pois.total} hint={`${a.pois.manual} manual · ${a.pois.autoDetected} auto-detected`} />
                    <Stat label="Awaiting embedding" value={a.pois.awaitingEmbedding} hint="not yet searchable semantically" />
                    <Stat label={`Queries (${a.days} d)`} value={a.queryCount} hint={`${a.distinctSearchers} distinct searchers`} />
                    <Stat label="Zero-result queries" value={a.zeroResultCount} hint={rate === null ? "no queries" : `${formatPercent(rate)} of queries`} />
                    <Stat label="Avg latency" value={formatMs(a.avgLatencyMs)} hint={`p95 ${formatMs(a.p95LatencyMs)}`} />
                    <Stat label="Last query" value={<span className="text-sm font-normal"><When iso={a.lastQueryAt} /></span>} />
                  </div>
                  {a.queryCount === 0 ? (
                    <p className="text-zinc-600">No searches were made at this venue in the last {a.days} days.</p>
                  ) : (
                    <div className="grid gap-4 md:grid-cols-2">
                      <div>
                        <h3 className="mb-1 font-semibold">Most searched terms</h3>
                        <p className="mb-1 text-xs text-zinc-500">
                          Normalized query text as typed. Which objects each search returned is not logged, so objects cannot be ranked directly.
                        </p>
                        <table className="w-full">
                          <thead><tr><th className={TH}>Term</th><th className={TH}>Searches</th><th className={TH}>Avg results</th></tr></thead>
                          <tbody>
                            {a.topTerms.map((t) => (
                              <tr key={t.term}>
                                <td className={TD}>{t.term}</td>
                                <td className={`${TD} tabular-nums`}>{t.count}</td>
                                <td className={`${TD} tabular-nums`}>{t.avgResultCount?.toFixed(1) ?? "—"}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                      <div>
                        <h3 className="mb-1 font-semibold">Zero-result queries</h3>
                        {a.zeroResultTerms.length === 0 ? (
                          <p className="text-zinc-600">Every search returned at least one result.</p>
                        ) : (
                          <table className="w-full">
                            <thead><tr><th className={TH}>Term</th><th className={TH}>Searches</th><th className={TH}>Last</th></tr></thead>
                            <tbody>
                              {a.zeroResultTerms.map((t) => (
                                <tr key={t.term}>
                                  <td className={TD}>{t.term}</td>
                                  <td className={`${TD} tabular-nums`}>{t.count}</td>
                                  <td className={TD}><When iso={t.lastSearchedAt} /></td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              );
            }}
          </Section>

          {/* 9. Re-scan history */}
          <Section
            id="rescans"
            title="Re-scan history"
            section="RESCANS"
            load={rescans}
            onRetry={reload}
            isEmpty={(r) => r.rescans.length === 0}
            emptyText="No region re-scans have been started for this venue."
          >
            {(r) => (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr>
                      <th className={TH}>Started</th>
                      <th className={TH}>Floor</th>
                      <th className={TH}>From</th>
                      <th className={TH}>Region</th>
                      <th className={TH}>Capture</th>
                      <th className={TH}>Run</th>
                      <th className={TH}>Result version</th>
                      <th className={TH}>Alignment</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.rescans.map((x) => (
                      <tr key={x.captureId}>
                        <td className={TD}>
                          <When iso={x.createdAt} />
                          <div className="font-mono text-xs text-zinc-500">{x.operatorId}</div>
                        </td>
                        <td className={TD}>{x.floorName ?? "—"}</td>
                        <td className={TD}>v{x.parentVersionNumber}</td>
                        <td className={`${TD} tabular-nums`}>{x.regionAreaM2 != null ? `${x.regionAreaM2.toFixed(1)} m²` : "—"}</td>
                        <td className={TD}>{x.captureStatus}</td>
                        <td className={TD}>
                          {x.runStatus ?? <span className="text-zinc-500">not started</span>}
                          {x.runFailureCode && (
                            <div className="text-xs text-red-800">
                              {x.runFailureStage ? stageLabel(x.runFailureStage) : ""} · <span className="font-mono">{x.runFailureCode}</span>
                            </div>
                          )}
                        </td>
                        <td className={TD}>
                          {x.resultVersionNumber != null ? (
                            <>
                              v{x.resultVersionNumber}{" "}
                              <span className={x.resultVersionStatus === "FINALIZED" ? "text-green-800" : "text-amber-800"}>{x.resultVersionStatus}</span>
                              {x.finalizedAt && <div className="text-xs text-zinc-500"><When iso={x.finalizedAt} /></div>}
                            </>
                          ) : (
                            "—"
                          )}
                        </td>
                        <td className={`${TD} text-xs`}>
                          {x.alignmentConfidence != null ? (
                            <>
                              confidence {x.alignmentConfidence.toFixed(3)}
                              {x.alignmentResidualM != null && <div>residual {x.alignmentResidualM.toFixed(3)} m</div>}
                            </>
                          ) : (
                            "—"
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Section>

          {/* 10. Audit activity */}
          <Section
            id="audit"
            title="Audit activity"
            section="AUDIT"
            load={audit}
            onRetry={reload}
            isEmpty={(a) => a.entries.length === 0}
            emptyText="No audited activity for this venue yet."
          >
            {(a) => (
              <div className="overflow-x-auto">
                <p className="mb-1 text-xs text-zinc-500">Most recent {a.entries.length} entries (limit {a.limit}). The audit log is append-only.</p>
                <table className="w-full text-sm">
                  <thead>
                    <tr>
                      <th className={TH}>When</th>
                      <th className={TH}>Actor</th>
                      <th className={TH}>Action</th>
                      <th className={TH}>Resource</th>
                      <th className={TH}>Outcome</th>
                    </tr>
                  </thead>
                  <tbody>
                    {a.entries.map((e) => (
                      <tr key={e.id}>
                        <td className={TD}><When iso={e.occurredAt} /></td>
                        <td className={TD}>
                          <span className="text-xs text-zinc-500">{e.actorType}</span>{" "}
                          <span className="font-mono text-xs">{e.actorId}</span>
                        </td>
                        <td className={`${TD} font-mono text-xs`}>{e.action}</td>
                        <td className={`${TD} text-xs`}>
                          {e.resourceType}
                          {e.resourceId && <span className="font-mono text-zinc-500"> {e.resourceId.slice(0, 8)}</span>}
                        </td>
                        <td className={`${TD} ${e.outcome === "SUCCESS" ? "" : "font-medium text-red-800"}`}>{e.outcome}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Section>
        </>
      )}
    </main>
  );
}

function permittedKey(a: OpsAccess): string {
  return (Object.keys(a.sections) as OpsSection[]).filter((s) => a.sections[s]).sort().join(",");
}

/** undefined while access is unknown, null when the role may not read the section, else the fetcher. */
function gated<T>(allowedKey: string | null, section: OpsSection, f: () => Promise<T>): (() => Promise<T>) | null | undefined {
  if (allowedKey === null) return undefined;
  return allowedKey.split(",").includes(section) ? f : null;
}

/**
 * useSection for a fetcher gated on the caller's access: `undefined` (access still loading) shows "loading",
 * `null` (the role may not read the section) shows the unauthorized state without making a request.
 */
function useGated<T>(fetcher: (() => Promise<T>) | null | undefined, refresh: number): Load<T> {
  const load = useSection<T>(fetcher ?? null, refresh);
  return fetcher === undefined ? { state: "loading" } : load;
}
