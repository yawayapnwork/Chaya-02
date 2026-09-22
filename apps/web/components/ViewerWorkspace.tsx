"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { NotSignedInError, signIn, userManager } from "@/lib/auth";
import { clearPublicViewerSession, publicViewerVenueId, setPublicViewerSession } from "@/lib/session";
import { ApiError, type Floor, type Venue, listFloors, listVenues } from "@/lib/capture-api";
import { type Poi, listPois } from "@/lib/poi-api";
import {
  type Reconstruction,
  type ReconstructionVersion,
  exchangePublicLink,
  fetchArtifact,
  getReconstruction,
  listReconstructions,
} from "@/lib/reconstruction-api";
import { detectDeviceProfile, type DeviceProfile } from "@/lib/device-profile";
import { formatBytes, formatDate, straightLineDistance } from "@/lib/viewer-format";
import { type SearchResult } from "@/lib/search-api";
import SplatViewerCanvas from "@/components/SplatViewerCanvas";
import SemanticSearchPanel from "@/components/SemanticSearchPanel";

type Phase = "checking" | "signed-out" | "ready";
type SceneLoad =
  | { phase: "idle" }
  | { phase: "downloading"; percent: number }
  | { phase: "preparing"; percent: number }
  | { phase: "ready"; splatCount: number }
  | { phase: "error"; message: string };

function message(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}

export default function ViewerWorkspace() {
  const searchParams = useSearchParams();
  const linkSecret = searchParams.get("link");
  // Optional deep-link from elsewhere in the app (e.g. "View reconstruction" after a capture finishes
  // processing). Only used to prefill the pickers below; the normal venue/floor fetches still run and
  // validate them, so an unknown or inaccessible id just leaves the picker on "Select...".
  const prefillVenueId = searchParams.get("venue");
  const prefillFloorId = searchParams.get("floor");

  const [phase, setPhase] = useState<Phase>("checking");
  const [isPublicLink, setIsPublicLink] = useState(false);
  const [linkError, setLinkError] = useState<string | null>(null);

  const [venues, setVenues] = useState<Venue[]>([]);
  const [venueId, setVenueId] = useState("");
  const [floors, setFloors] = useState<Floor[]>([]);
  const [floorId, setFloorId] = useState("");

  const [versions, setVersions] = useState<ReconstructionVersion[]>([]);
  const [runId, setRunId] = useState("");
  const [reconstruction, setReconstruction] = useState<Reconstruction | null>(null);
  const [reconstructionError, setReconstructionError] = useState<string | null>(null);

  const [pois, setPois] = useState<Poi[]>([]);
  const [selectedPoiId, setSelectedPoiId] = useState<string | null>(null);
  const [routeFromId, setRouteFromId] = useState<string>("");
  const [routeToId, setRouteToId] = useState<string>("");

  const [sceneLoad, setSceneLoad] = useState<SceneLoad>({ phase: "idle" });
  const blobUrlRef = useRef<string | null>(null);
  const floorPrefillConsumedRef = useRef(false);
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  const deviceProfile: DeviceProfile = useMemo(() => detectDeviceProfile(), []);

  // ---- bootstrap: a public link, or a Keycloak session ------------------------------------------
  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (linkSecret) {
        try {
          const token = await exchangePublicLink(linkSecret);
          if (cancelled) return;
          setPublicViewerSession(token.token, token.expiresAt, token.venueId);
          setIsPublicLink(true);
          setVenueId(token.venueId);
          setPhase("ready");
        } catch (e) {
          if (!cancelled) {
            setLinkError(message(e));
            setPhase("signed-out");
          }
        }
        return;
      }
      clearPublicViewerSession();
      try {
        const user = await userManager().getUser();
        if (cancelled) return;
        if (!user || user.expired) return setPhase("signed-out");
        setPhase("ready");
        const vs = await listVenues();
        if (cancelled) return;
        setVenues(vs);
        if (prefillVenueId && vs.some((v) => v.id === prefillVenueId)) setVenueId(prefillVenueId);
        else if (vs.length === 1) setVenueId(vs[0].id);
      } catch (e) {
        if (!cancelled) {
          if (!(e instanceof NotSignedInError)) setLinkError(message(e));
          setPhase("signed-out");
        }
      }
    })();
    return () => {
      cancelled = true;
    };
    // prefillVenueId is read once at mount time by design (it seeds the initial pick, see the
    // floor effect below for the matching floor prefill); re-running this on every keystroke of a
    // URL that never changes after load would be pointless.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linkSecret]);

  // A public link is venue-scoped only; its own venue never needs (or is allowed) a picker.
  const publicVenueId = publicViewerVenueId();

  useEffect(() => {
    if (!venueId) return;
    let cancelled = false;
    listFloors(venueId).then(
      (fs) => {
        if (cancelled) return;
        setFloors(fs);
        const usePrefill = !floorPrefillConsumedRef.current && prefillFloorId && fs.some((f) => f.id === prefillFloorId);
        floorPrefillConsumedRef.current = true;
        setFloorId((prev) => {
          if (usePrefill) return prefillFloorId;
          if (fs.some((f) => f.id === prev)) return prev;
          return fs.length === 1 ? fs[0].id : "";
        });
      },
      (e) => {
        if (!cancelled) setLinkError(message(e));
      },
    );
    return () => {
      cancelled = true;
    };
    // prefillFloorId is consumed at most once (floorPrefillConsumedRef), deliberately not a dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [venueId]);

  useEffect(() => {
    if (!venueId || !floorId) return;
    let cancelled = false;
    listReconstructions(venueId, floorId).then(
      (vs) => {
        if (cancelled) return;
        setVersions(vs);
        setRunId(vs.length > 0 ? vs[0].runId : "");
        if (vs.length === 0) {
          setReconstruction(null);
          setReconstructionError(null);
          setSceneLoad({ phase: "idle" });
        }
      },
      (e) => {
        if (!cancelled) setReconstructionError(message(e));
      },
    );
    listPois(venueId).then(
      (all) => {
        if (!cancelled) setPois(all.filter((p) => p.floorId === floorId));
      },
      () => {
        if (!cancelled) setPois([]); // POI overlays are a nice-to-have; never block the viewer over them
      },
    );
    return () => {
      cancelled = true;
    };
  }, [venueId, floorId, reloadToken]);

  useEffect(() => {
    if (!venueId || !runId) return;
    let cancelled = false;
    getReconstruction(venueId, runId).then(
      (r) => {
        if (cancelled) return;
        setReconstruction(r);
        setReconstructionError(null);
      },
      (e) => {
        if (cancelled) return;
        setReconstruction(null);
        setReconstructionError(message(e));
        setSceneLoad({ phase: "idle" });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [venueId, runId, reloadToken]);

  // ---- download the .ksplat once a reconstruction is known -------------------------------------
  useEffect(() => {
    const ksplat = reconstruction?.artifacts.find((a) => a.kind === "KSPLAT");
    if (!ksplat) return; // reconstruction is null: the effects above already reset sceneLoad to idle
    let cancelled = false;
    const run = async () => {
      setSceneLoad({ phase: "downloading", percent: 0 });
      setSelectedPoiId(null);
      try {
        const blob = await fetchArtifact(ksplat.url, (loaded, total) => {
          if (cancelled) return;
          setSceneLoad({ phase: "downloading", percent: total ? Math.round((loaded / total) * 100) : 0 });
        });
        if (cancelled) return;
        if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current);
        const url = URL.createObjectURL(blob);
        blobUrlRef.current = url;
        setBlobUrl(url);
        setSceneLoad({ phase: "preparing", percent: 0 });
      } catch (e) {
        if (!cancelled) setSceneLoad({ phase: "error", message: message(e) });
      }
    };
    run();
    return () => {
      cancelled = true;
    };
  }, [reconstruction, reloadToken]);

  useEffect(
    () => () => {
      if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current);
    },
    [],
  );

  const routeFrom = pois.find((p) => p.id === routeFromId) ?? null;
  const routeTo = pois.find((p) => p.id === routeToId) ?? null;
  const selectedPoi = pois.find((p) => p.id === selectedPoiId) ?? null;

  function onSelectSearchResult(result: SearchResult) {
    if (result.floorId !== floorId) setFloorId(result.floorId);
    setSelectedPoiId(result.poiId);
  }

  if (phase === "checking") return <p className="p-8">Loading…</p>;

  if (phase === "signed-out") {
    return (
      <main className="mx-auto max-w-2xl p-8">
        <h1 className="text-2xl font-semibold">Digital twin viewer</h1>
        {linkError ? (
          <p role="alert" className="mt-4 rounded border border-red-300 bg-red-50 p-3 text-red-800">{linkError}</p>
        ) : (
          <p className="mt-2 text-zinc-600">Sign in to view a venue&apos;s reconstructions, or open a public viewing link.</p>
        )}
        <button className="mt-6 rounded bg-black px-4 py-2 text-white" onClick={() => signIn("/viewer")}>
          Sign in
        </button>
      </main>
    );
  }

  return (
    <main className="flex h-screen flex-col">
      <header className="flex flex-wrap items-center gap-3 border-b bg-white p-3">
        <h1 className="text-lg font-semibold">Digital twin viewer</h1>
        {isPublicLink && (
          <span className="rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-900">Public viewing link</span>
        )}

        {!isPublicLink && (
          <label className="text-sm">
            Venue{" "}
            <select className="rounded border p-1" value={venueId} onChange={(e) => setVenueId(e.target.value)}>
              <option value="">Select…</option>
              {venues.map((v) => (
                <option key={v.id} value={v.id}>{v.name}</option>
              ))}
            </select>
          </label>
        )}
        {isPublicLink && <span className="text-sm text-zinc-600">Venue: {publicVenueId}</span>}

        <label className="text-sm">
          Floor{" "}
          <select className="rounded border p-1" value={floorId} onChange={(e) => setFloorId(e.target.value)} disabled={!venueId}>
            <option value="">Select…</option>
            {floors.map((f) => (
              <option key={f.id} value={f.id}>{f.name} (level {f.level})</option>
            ))}
          </select>
        </label>

        <label className="text-sm">
          Reconstruction{" "}
          <select className="rounded border p-1" value={runId} onChange={(e) => setRunId(e.target.value)} disabled={versions.length === 0}>
            {versions.length === 0 && <option value="">No reconstructions</option>}
            {versions.map((v, i) => (
              <option key={v.runId} value={v.runId}>
                {formatDate(v.generatedAt)} {i === 0 ? "(latest)" : ""}
              </option>
            ))}
          </select>
        </label>
      </header>

      <div className="relative flex flex-1 overflow-hidden">
        <div className="relative flex-1">
          {!floorId && (
            <div className="flex h-full items-center justify-center text-zinc-500">Select a venue and floor to view its reconstruction.</div>
          )}

          {floorId && versions.length === 0 && !reconstructionError && (
            <div className="flex h-full flex-col items-center justify-center gap-2 text-zinc-600" data-testid="no-reconstruction">
              <p className="text-lg font-medium">No reconstruction available.</p>
              <p className="text-sm">This floor has not produced a viewable reconstruction yet.</p>
            </div>
          )}

          {reconstructionError && (
            <div className="flex h-full flex-col items-center justify-center gap-3 text-zinc-600">
              <p className="text-lg font-medium text-red-700">No reconstruction available.</p>
              <p className="max-w-md text-center text-sm">{reconstructionError}</p>
              <button className="rounded border px-3 py-1.5 text-sm" onClick={() => setReloadToken((t) => t + 1)}>
                Try again
              </button>
            </div>
          )}

          {reconstruction && blobUrl && sceneLoad.phase !== "error" && (
            <SplatViewerCanvas
              key={blobUrl}
              blobUrl={blobUrl}
              deviceProfile={deviceProfile}
              pois={pois}
              selectedPoiId={selectedPoiId}
              onSelectPoi={setSelectedPoiId}
              routeFrom={routeFrom}
              routeTo={routeTo}
              onProgress={(percent) => setSceneLoad({ phase: "preparing", percent })}
              onLoaded={(splatCount) => setSceneLoad({ phase: "ready", splatCount })}
              onError={(msg) => setSceneLoad({ phase: "error", message: msg })}
            />
          )}

          {(sceneLoad.phase === "downloading" || sceneLoad.phase === "preparing") && (
            <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-3 bg-zinc-950/70 text-white">
              <p className="text-sm">{sceneLoad.phase === "downloading" ? "Downloading reconstruction…" : "Preparing scene…"}</p>
              <div className="h-2 w-64 overflow-hidden rounded bg-white/20">
                <div className="h-full bg-white transition-[width]" style={{ width: `${sceneLoad.percent}%` }} />
              </div>
              <p className="text-xs text-white/70">{sceneLoad.percent}%</p>
            </div>
          )}

          {sceneLoad.phase === "error" && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-zinc-950/90 text-white">
              <p className="text-lg font-medium">Could not load the reconstruction</p>
              <p className="max-w-md text-center text-sm text-white/80">{sceneLoad.message}</p>
              <button className="rounded border border-white/40 px-3 py-1.5 text-sm" onClick={() => setReloadToken((t) => t + 1)}>
                Retry
              </button>
            </div>
          )}
        </div>

        {venueId && (
          <aside className="w-80 shrink-0 space-y-4 overflow-y-auto border-l bg-white p-4 text-sm">
            <SemanticSearchPanel venueId={venueId} floorId={floorId} onSelectResult={onSelectSearchResult} />

            {reconstruction && (
              <section>
                <h2 className="font-medium">Reconstruction</h2>
                <dl className="mt-1 grid grid-cols-[6rem_1fr] gap-1 text-xs text-zinc-700">
                  <dt>Generated</dt><dd>{formatDate(reconstruction.generatedAt)}</dd>
                  <dt>Run status</dt><dd>{reconstruction.runStatus}</dd>
                  <dt>Quality</dt><dd>{reconstruction.runQuality ?? "—"}</dd>
                  {sceneLoad.phase === "ready" && <>
                    <dt>Splats</dt><dd>{sceneLoad.splatCount.toLocaleString()}</dd>
                  </>}
                  {reconstruction.artifacts.find((a) => a.kind === "KSPLAT") && (
                    <>
                      <dt>Asset size</dt>
                      <dd>{formatBytes(reconstruction.artifacts.find((a) => a.kind === "KSPLAT")!.sizeBytes)}</dd>
                    </>
                  )}
                  <dt>Device tier</dt><dd className="capitalize">{deviceProfile.tier}</dd>
                </dl>
              </section>
            )}

            <section>
              <h2 className="font-medium">Points of interest ({pois.length})</h2>
              <ul className="mt-1 max-h-48 space-y-1 overflow-y-auto">
                {pois.map((p) => (
                  <li key={p.id}>
                    <button
                      className={`w-full rounded px-2 py-1 text-left ${p.id === selectedPoiId ? "bg-amber-100" : "hover:bg-zinc-100"}`}
                      onClick={() => setSelectedPoiId(p.id === selectedPoiId ? null : p.id)}
                    >
                      {p.label}
                    </button>
                  </li>
                ))}
                {pois.length === 0 && <li className="text-xs text-zinc-500">No POIs recorded for this floor.</li>}
              </ul>
            </section>

            {selectedPoi && (
              <section className="rounded border border-amber-300 bg-amber-50 p-3" data-testid="poi-details">
                <h3 className="font-medium">{selectedPoi.label}</h3>
                {selectedPoi.category && <p className="text-xs uppercase tracking-wide text-zinc-500">{selectedPoi.category}</p>}
                {selectedPoi.description && <p className="mt-1">{selectedPoi.description}</p>}
                {selectedPoi.tags.length > 0 && <p className="mt-1 text-xs text-zinc-600">{selectedPoi.tags.join(", ")}</p>}
                <p className="mt-1 font-mono text-xs text-zinc-500">
                  {selectedPoi.x.toFixed(2)}, {selectedPoi.y.toFixed(2)}, {selectedPoi.z.toFixed(2)}
                </p>
              </section>
            )}

            <section>
              <h2 className="font-medium">Navigation route</h2>
              <div className="mt-1 space-y-2">
                <label className="block text-xs">
                  From
                  <select className="mt-0.5 block w-full rounded border p-1" value={routeFromId} onChange={(e) => setRouteFromId(e.target.value)}>
                    <option value="">Select a POI…</option>
                    {pois.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
                  </select>
                </label>
                <label className="block text-xs">
                  To
                  <select className="mt-0.5 block w-full rounded border p-1" value={routeToId} onChange={(e) => setRouteToId(e.target.value)}>
                    <option value="">Select a POI…</option>
                    {pois.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
                  </select>
                </label>
                {routeFrom && routeTo && (
                  <p className="text-xs text-zinc-600">
                    Straight-line preview only ({straightLineDistance(routeFrom, routeTo).toFixed(1)} scene units) — no baked navigation
                    route is available for this floor yet.
                  </p>
                )}
              </div>
            </section>
          </aside>
        )}
      </div>
    </main>
  );
}
