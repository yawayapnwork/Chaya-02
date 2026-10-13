"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { NotSignedInError, signIn, userManager } from "@/lib/auth";
import { clearPublicViewerSession, publicViewerVenueId, setPublicViewerSession } from "@/lib/session";
import { ApiError, type Capture, type Floor, type ProcessingStatus, type Venue, getProcessing, listCaptures, listFloors, listVenues } from "@/lib/capture-api";
import { type Poi, listPois } from "@/lib/poi-api";
import { isCanonical, toCanonical } from "@/lib/coordinate-frame";
import {
  type Reconstruction,
  type ReconstructionVersion,
  exchangePublicLink,
  fetchArtifact,
  getReconstruction,
  listReconstructions,
} from "@/lib/reconstruction-api";
import { detectDeviceProfile, type DeviceProfile } from "@/lib/device-profile";
import { formatBytes, formatDate } from "@/lib/viewer-format";
import { checkSceneScope } from "@/lib/artifact-integrity";
import { type Scoped, forScene, initialVersion, sceneKey, versionLabel } from "@/lib/version-scope";
import { type SearchResult } from "@/lib/search-api";
import { type RouteResponse, planRoute } from "@/lib/navigation-api";
import {
  type RouteFailure,
  type RouteProfile,
  describeRouteFailure,
  formatDistance,
  formatDuration,
  routeOverlay,
  routeSummary,
} from "@/lib/viewer-route";
import { type ViewerFailure, classifyViewerError } from "@/lib/viewer-errors";
import { ArtifactCache } from "@/lib/artifact-cache";
import { viewerResources } from "@/lib/viewer-resources";
import { type ProcessingProgress, pendingCapture, processingProgress } from "@/lib/viewer-progress";
import SplatViewerCanvas, { type SplatViewerHandle } from "@/components/SplatViewerCanvas";
import SemanticSearchPanel from "@/components/SemanticSearchPanel";

type Phase = "checking" | "signed-out" | "ready";
type SceneLoad =
  | { phase: "idle" }
  | { phase: "downloading"; percent: number }
  | { phase: "preparing"; percent: number }
  | { phase: "ready"; splatCount: number }
  | { phase: "error"; failure: ViewerFailure };

/** Verified .ksplat bytes, shared by every viewer on the page (lib/artifact-cache): switching back to a version, or
 * retrying after a graphics failure, does not download the model again. Access is still checked on every scene: the
 * reconstruction (and its artifact reference) is fetched with the caller's credentials before a cached entry is used. */
const artifactCache = new ArtifactCache();

/** How often the progress of a floor's in-progress reconstruction is refreshed. */
const PROGRESS_POLL_MS = 15_000;

/** One accessibility profile's answer from the routing API: a route, or the reason there is none. */
type RouteOutcome = { ok: true; route: RouteResponse } | { ok: false; failure: RouteFailure; detail: string };
const PROFILES: RouteProfile[] = ["STANDARD", "STEP_FREE"];
const PROFILE_LABEL: Record<RouteProfile, string> = { STANDARD: "Standard route", STEP_FREE: "Step-free route" };

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
  // A capture links to exactly its own reconstruction (?run=); used once, and only if that run is a listed (published)
  // version of the floor -- the list never holds a draft, failed or uncalibrated run.
  const prefillRunId = searchParams.get("run");
  const runPrefillConsumedRef = useRef(false);

  const [phase, setPhase] = useState<Phase>("checking");
  const [isPublicLink, setIsPublicLink] = useState(false);
  const [linkError, setLinkError] = useState<string | null>(null);

  const [venues, setVenues] = useState<Venue[]>([]);
  const [venueId, setVenueId] = useState("");
  const [floors, setFloors] = useState<Floor[]>([]);
  const [floorId, setFloorId] = useState("");

  const [versions, setVersions] = useState<ReconstructionVersion[]>([]);
  const versionsRef = useRef<ReconstructionVersion[]>([]); // read when a reconstruction arrives, not a reason to refetch it
  const [runId, setRunId] = useState("");
  const [reconstruction, setReconstruction] = useState<Reconstruction | null>(null);
  const [reconstructionError, setReconstructionError] = useState<ViewerFailure | null>(null);
  const [venueError, setVenueError] = useState<ViewerFailure | null>(null);
  // The newest capture of this floor that is still becoming a model, and its run's progress (venue team only).
  const [pending, setPending] = useState<{ floorId: string; capture: Capture; progress: ProcessingProgress } | null>(null);
  const pendingRef = useRef<typeof pending>(null);
  const [quality, setQuality] = useState<{ pixelRatio: number; maxPixelRatio: number; medianMs: number | null } | null>(null);

  // POIs, the route and the downloaded model are each stored with the scene they were loaded for (lib/version-scope),
  // and only used while that scene is the one on screen: switching versions never mixes one version's model with
  // another's POIs, route or frame.
  const [loadedPois, setLoadedPois] = useState<Scoped<Poi[]> | null>(null);
  const [selectedPoiId, setSelectedPoiId] = useState<string | null>(null);
  const [routeFromId, setRouteFromId] = useState<string>("");
  const [routeToId, setRouteToId] = useState<string>("");
  const [routeProfile, setRouteProfile] = useState<RouteProfile>("STANDARD");
  // Both profiles are requested for the same start and destination, so the step-free alternative (or the reason there
  // is none) is shown next to the standard route.
  const [loadedRoutes, setLoadedRoutes] = useState<Scoped<Record<RouteProfile, RouteOutcome>> | null>(null);
  const [routeBusy, setRouteBusy] = useState(false);

  // Like the POIs and the route, the load state belongs to one scene: right after a switch, the previous scene's "ready"
  // (or error) is never shown for the next one.
  const [loadState, setLoadState] = useState<Scoped<SceneLoad> | null>(null);
  const floorPrefillConsumedRef = useRef(false);
  const canvasRef = useRef<SplatViewerHandle>(null);
  // The scene and POI the camera last moved to, so a selection is focused once, not again on every re-render.
  const focusedRef = useRef<string | null>(null);
  const [blob, setBlob] = useState<Scoped<string> | null>(null);
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
        setVenueError(null);
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
        if (!cancelled) setVenueError(classifyViewerError(e));
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
        versionsRef.current = vs;
        const requested = !runPrefillConsumedRef.current && prefillRunId ? vs.find((v) => v.runId === prefillRunId) : undefined;
        runPrefillConsumedRef.current = true;
        setRunId(requested?.runId ?? initialVersion(vs)?.runId ?? "");
        if (vs.length === 0) {
          setReconstruction(null);
          setReconstructionError(null);
        }
      },
      (e) => {
        if (!cancelled) {
          setVersions([]);
          versionsRef.current = [];
          setReconstruction(null);
          setReconstructionError(classifyViewerError(e));
        }
      },
    );
    return () => {
      cancelled = true;
    };
    // prefillRunId is consumed at most once (runPrefillConsumedRef), deliberately not a dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [venueId, floorId, reloadToken]);

  useEffect(() => {
    if (!venueId || !runId) return;
    let cancelled = false;
    getReconstruction(venueId, runId).then(
      (r) => {
        if (cancelled) return;
        try {
          // Fail closed: never show a model with another version's frame, or a version without the asset it pinned.
          const listed = versionsRef.current.find((v) => v.runId === runId);
          checkSceneScope(r, { venueId, runId, scanVersionId: listed?.scanVersionId ?? null });
        } catch (e) {
          setReconstruction(null);
          setReconstructionError(classifyViewerError(e));
          return;
        }
        setReconstruction(r);
        setReconstructionError(null);
      },
      (e) => {
        if (cancelled) return;
        setReconstruction(null);
        setReconstructionError(classifyViewerError(e));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [venueId, runId, reloadToken]);

  const currentKey = sceneKey(reconstruction);
  const scanVersionId = reconstruction?.scanVersionId ?? null;

  // ---- the POIs of exactly this reconstruction: its version's, or (not a version) the floor's current ones -------------
  useEffect(() => {
    if (!venueId || !reconstruction) return;
    const key = sceneKey(reconstruction)!;
    const versionId = reconstruction.scanVersionId;
    const floor = reconstruction.floorId;
    let cancelled = false;
    listPois(venueId, versionId).then(
      (all) => {
        if (!cancelled) setLoadedPois({ key, value: versionId ? all : all.filter((p) => p.floorId === floor) });
      },
      () => {
        if (!cancelled) setLoadedPois({ key, value: [] }); // POI overlays are a nice-to-have; never block the viewer over them
      },
    );
    return () => {
      cancelled = true;
    };
  }, [venueId, reconstruction, reloadToken]);

  // ---- download the .ksplat once a reconstruction is known -------------------------------------
  useEffect(() => {
    const ksplat = reconstruction?.artifacts.find((a) => a.kind === "KSPLAT");
    if (!ksplat) return; // reconstruction is null: the effects above already reset sceneLoad to idle
    const key = sceneKey(reconstruction)!;
    let cancelled = false;
    let url: string | null = null;
    const progress = (loaded: number, total: number) => {
      if (!cancelled) setLoadState({ key, value: { phase: "downloading", percent: Math.round((loaded / total) * 100) } });
    };
    const run = async () => {
      setLoadState({ key, value: { phase: "downloading", percent: 0 } });
      try {
        // Verified before it is returned (and before it is cached): registered size, SHA-256, the .ksplat contract and
        // the published format. A cached entry is those same verified bytes.
        const blob = await artifactCache.get(ksplat, (onProgress) => fetchArtifact(ksplat, onProgress), progress);
        if (cancelled) return;
        url = URL.createObjectURL(blob);
        viewerResources.objectUrls++;
        setBlob({ key, value: url });
        setLoadState({ key, value: { phase: "preparing", percent: 0 } });
      } catch (e) {
        if (!cancelled) setLoadState({ key, value: { phase: "error", failure: classifyViewerError(e) } });
      }
    };
    run();
    return () => {
      artifactCache.unsubscribe(ksplat, progress);
      // The scene this download was for is no longer the one on screen (another version, floor or venue, a retry, or
      // the page closing): its canvas unmounts with it, so the bytes are released here rather than kept until the next
      // download finishes.
      cancelled = true;
      if (url) {
        URL.revokeObjectURL(url);
        viewerResources.objectUrls--;
      }
      setBlob((b) => (b && b.value === url ? null : b));
    };
  }, [reconstruction, reloadToken]);

  // ---- a newer model on its way: the floor's newest capture still processing (or failed), polled while it runs -------
  // Raw captures are for the venue team (capture API: ADMIN, VENUE_MANAGER, OPERATOR). A public link never asks; a
  // signed-in VIEWER is refused (403) and simply sees nothing here.
  useEffect(() => {
    if (!venueId || !floorId || isPublicLink) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      let next: { floorId: string; capture: Capture; progress: ProcessingProgress } | null = null;
      try {
        const capture = pendingCapture(await listCaptures(venueId), floorId);
        if (capture) {
          let status: ProcessingStatus | null = null;
          try {
            status = await getProcessing(venueId, capture.id);
          } catch {
            status = null; // not started: no run yet
          }
          next = { floorId, capture, progress: processingProgress(capture.status, status?.run ?? null) };
        }
      } catch {
        next = null;
      }
      if (cancelled) return;
      // A run that just finished may have published a new version: list the floor's versions again.
      const prev = pendingRef.current;
      if (prev?.progress.state === "processing" && prev.capture.id === next?.capture.id && next.progress.state === "finished") {
        setReloadToken((t) => t + 1);
      }
      pendingRef.current = next;
      setPending(next);
      if (next && (next.progress.state === "processing" || next.progress.state === "waiting")) timer = setTimeout(poll, PROGRESS_POLL_MS);
    };
    poll();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [venueId, floorId, isPublicLink]);
  const pendingHere = pending && pending.floorId === floorId && pending.progress.state !== "finished" ? pending.progress : null;

  const pois = useMemo(() => forScene(loadedPois, currentKey) ?? [], [loadedPois, currentKey]);
  const blobUrl = forScene(blob, currentKey);
  const sceneLoad: SceneLoad = forScene(loadState, currentKey) ?? { phase: "idle" };
  const canvasKey = currentKey ?? "";
  const routeOutcomes = forScene(loadedRoutes, currentKey);
  const routeFrom = pois.find((p) => p.id === routeFromId) ?? null;
  const routeTo = pois.find((p) => p.id === routeToId) ?? null;
  const selectedPoi = pois.find((p) => p.id === selectedPoiId) ?? null;

  function onSelectSearchResult(result: SearchResult) {
    if (result.floorId !== floorId) setFloorId(result.floorId);
    selectPoi(result.poiId);
  }

  // Real routing (POST /api/v1/navigation/routes): "from" POI stands in for the traveller's current
  // position (a live AR client would send its own tracked position instead). No client-side fallback is
  // drawn when the call fails or no route exists -- see SplatViewerCanvas's route-overlay effect.
  // On a finalized version the route is planned on that version (its pinned navmesh graph and frame), never on the
  // floor's current graph, which may belong to a newer version. Both accessibility profiles are planned, so the
  // step-free alternative is offered whenever the API finds one, and its absence is stated when it does not.
  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      if (!routeFrom || !routeTo || !venueId || !currentKey) {
        setLoadedRoutes(null);
        setRouteBusy(false);
        return;
      }
      setRouteBusy(true);
      const plan = async (profile: RouteProfile): Promise<RouteOutcome> => {
        try {
          const route = await planRoute({
            venueId,
            floorId: routeFrom.floorId ?? floorId,
            start: [routeFrom.x, routeFrom.y, routeFrom.z],
            destinationPoiId: routeTo.id,
            accessibility: profile,
            scanVersionId: scanVersionId ?? undefined,
          });
          return { ok: true, route };
        } catch (e) {
          const code = e instanceof ApiError ? e.code : "";
          return { ok: false, failure: describeRouteFailure(code, message(e), profile), detail: message(e) };
        }
      };
      const [standard, stepFree] = await Promise.all(PROFILES.map(plan));
      if (cancelled) return;
      setLoadedRoutes({ key: currentKey, value: { STANDARD: standard, STEP_FREE: stepFree } });
      setRouteBusy(false);
    };
    run();
    return () => {
      cancelled = true;
    };
  }, [routeFrom, routeTo, venueId, floorId, currentKey, scanVersionId]);

  const routeResponse = useMemo(() => {
    const outcome = routeOutcomes?.[routeProfile];
    return outcome?.ok ? outcome.route : null;
  }, [routeOutcomes, routeProfile]);

  // POIs and routes are canonical venue metres; they are drawn on the splat only through its calibrated frame, and only
  // POIs placed in that same frame (docs/coordinate-frames.md).
  const frame = reconstruction?.coordinateFrame ?? null;
  const canonicalTransform = useMemo(() => (isCanonical(frame) ? toCanonical(frame) : null), [frame]);
  const placedPois = useMemo(
    () => (canonicalTransform && frame ? pois.filter((p) => p.frameStatus === "CURRENT" && p.coordinateFrameId === frame.id) : []),
    [pois, canonicalTransform, frame],
  );
  const placedPoiIds = useMemo(() => new Set(placedPois.map((p) => p.id)), [placedPois]);
  // The route is drawn only where it is in the frame and version this splat is shown in (lib/viewer-route).
  const overlay = useMemo(
    () => (routeResponse && reconstruction
      ? routeOverlay(routeResponse, {
          floorId: reconstruction.floorId,
          frameId: canonicalTransform && frame ? frame.id : null,
          scanVersionId: reconstruction.scanVersionId,
        })
      : null),
    [routeResponse, reconstruction, canonicalTransform, frame],
  );
  const floorName = (id: string) => {
    const f = floors.find((x) => x.id === id);
    return f ? `${f.name} (level ${f.level})` : id;
  };

  // Selecting a POI -- in the list, on the canvas or from search, including one on another floor whose scene is still
  // loading -- moves the camera to it once its scene is ready. A POI not placed on this reconstruction is not focused.
  const sceneReady = sceneLoad.phase === "ready";
  useEffect(() => {
    if (!sceneReady || !selectedPoiId || !currentKey || !placedPoiIds.has(selectedPoiId)) return;
    const key = `${currentKey}:${selectedPoiId}`;
    if (focusedRef.current === key) return;
    if (canvasRef.current?.focusPoi(selectedPoiId)) focusedRef.current = key;
  }, [sceneReady, selectedPoiId, currentKey, placedPoiIds]);
  function selectPoi(id: string | null) {
    focusedRef.current = null; // re-selecting the same POI after moving away focuses it again
    setSelectedPoiId(id);
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
    <main className="flex h-dvh flex-col">
      <header className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b bg-white p-3">
        <h1 className="text-lg font-semibold">Digital twin viewer</h1>
        {isPublicLink && (
          <span className="rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-900">Public viewing link</span>
        )}

        {!isPublicLink && (
          <label className="text-sm">
            Venue{" "}
            <select className="min-h-9 max-w-[60vw] rounded border p-1" value={venueId} onChange={(e) => setVenueId(e.target.value)}>
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
          <select className="min-h-9 max-w-[60vw] rounded border p-1" value={floorId} onChange={(e) => setFloorId(e.target.value)} disabled={!venueId}>
            <option value="">Select…</option>
            {floors.map((f) => (
              <option key={f.id} value={f.id}>{f.name} (level {f.level})</option>
            ))}
          </select>
        </label>

        <label className="text-sm">
          Reconstruction{" "}
          <select className="min-h-9 max-w-[60vw] rounded border p-1" value={runId} onChange={(e) => setRunId(e.target.value)} disabled={versions.length === 0}>
            {versions.length === 0 && <option value="">No reconstructions</option>}
            {versions.map((v) => (
              <option key={v.runId} value={v.runId}>
                {versionLabel(v, formatDate(v.generatedAt), v.current)}
              </option>
            ))}
          </select>
        </label>
      </header>

      <div className="relative flex min-h-0 flex-1 flex-col overflow-hidden md:flex-row">
        {/* The scene keeps most of a phone's screen; the panel scrolls below it rather than covering it. */}
        <div className="relative min-h-[55dvh] flex-1 md:min-h-0" aria-busy={sceneLoad.phase === "downloading" || sceneLoad.phase === "preparing"}>
          {venueError && (
            <div role="alert" className="flex h-full flex-col items-center justify-center gap-2 p-4 text-center text-zinc-600"
              data-testid="viewer-state" data-state={venueError.kind}>
              <p className="text-lg font-medium text-red-700">{venueError.title}</p>
              <p className="max-w-md text-sm">{venueError.message}</p>
            </div>
          )}

          {!venueError && !floorId && (
            <div className="flex h-full items-center justify-center p-4 text-center text-zinc-500">Select a venue and floor to view its reconstruction.</div>
          )}

          {floorId && versions.length === 0 && !reconstructionError && (
            <div className="flex h-full flex-col items-center justify-center gap-2 p-4 text-center text-zinc-600" data-testid="no-reconstruction"
              data-state={pendingHere ? (pendingHere.state === "failed" ? "failed" : "processing") : "unavailable"}>
              <p className="text-lg font-medium">No reconstruction available.</p>
              {!pendingHere && <p className="text-sm">This floor has not produced a viewable reconstruction yet.</p>}
              {pendingHere && <ProcessingNotice progress={pendingHere} />}
            </div>
          )}

          {reconstructionError && (
            <div role="alert" className="flex h-full flex-col items-center justify-center gap-3 p-4 text-center text-zinc-600"
              data-testid="viewer-state" data-state={reconstructionError.kind}>
              <p className="text-lg font-medium text-red-700">{reconstructionError.title}</p>
              <p className="max-w-md text-sm">{reconstructionError.message}</p>
              {reconstructionError.retryable && (
                <button className="min-h-10 rounded border px-3 py-1.5 text-sm" onClick={() => setReloadToken((t) => t + 1)}>
                  Try again
                </button>
              )}
            </div>
          )}

          {reconstruction && blobUrl && sceneLoad.phase !== "error" && (
            <SplatViewerCanvas
              ref={canvasRef}
              key={blobUrl}
              blobUrl={blobUrl}
              toCanonical={canonicalTransform}
              deviceProfile={deviceProfile}
              pois={placedPois}
              selectedPoiId={selectedPoiId}
              onSelectPoi={selectPoi}
              routeWaypoints={overlay?.waypoints ?? null}
              onProgress={(percent) => setLoadState({ key: canvasKey, value: { phase: "preparing", percent } })}
              onLoaded={(splatCount) => setLoadState({ key: canvasKey, value: { phase: "ready", splatCount } })}
              onError={(e) => setLoadState({ key: canvasKey, value: { phase: "error", failure: classifyViewerError(e) } })}
              onQuality={setQuality}
            />
          )}

          {blobUrl && sceneReady && (
            // A compact cluster in a corner: on a phone it stays out of the middle of the scene, with touch-sized targets.
            <div className="pointer-events-none absolute right-2 top-2 z-10 flex flex-col items-end gap-2" data-testid="camera-controls">
              <div role="toolbar" aria-label="Camera"
                className="pointer-events-auto flex overflow-hidden rounded border border-white/30 bg-zinc-900/80 text-sm text-white">
                <button className="min-h-10 min-w-10 px-2.5 hover:bg-white/10 focus-visible:bg-white/20" aria-label="Zoom in"
                  onClick={() => canvasRef.current?.zoom(0.7)}>+</button>
                <button className="min-h-10 min-w-10 border-l border-white/30 px-2.5 hover:bg-white/10 focus-visible:bg-white/20" aria-label="Zoom out"
                  onClick={() => canvasRef.current?.zoom(1 / 0.7)}>−</button>
                <button
                  className="min-h-10 border-l border-white/30 px-2.5 hover:bg-white/10 focus-visible:bg-white/20"
                  onClick={() => {
                    focusedRef.current = null;
                    canvasRef.current?.resetView();
                  }}
                >
                  Reset view
                </button>
              </div>
              <p className="hidden rounded bg-zinc-900/70 px-2 py-1 text-[11px] text-white/80 sm:block pointer-coarse:hidden">
                Drag to orbit · right-drag to pan · scroll to zoom
              </p>
              <p className="hidden rounded bg-zinc-900/70 px-2 py-1 text-[11px] text-white/80 pointer-coarse:block">
                One finger orbits · two fingers pan and pinch to zoom
              </p>
            </div>
          )}

          {(sceneLoad.phase === "downloading" || sceneLoad.phase === "preparing") && (
            <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-3 bg-zinc-950/70 text-white">
              <p className="text-sm" role="status">
                {sceneLoad.phase === "downloading"
                  ? "Downloading reconstruction…"
                  : sceneLoad.percent >= 100 ? "Rendering…" : "Preparing scene…"}
              </p>
              <div className="h-2 w-64 overflow-hidden rounded bg-white/20">
                <div className="h-full bg-white transition-[width]" style={{ width: `${sceneLoad.percent}%` }} />
              </div>
              <p className="text-xs text-white/70">{sceneLoad.percent}%</p>
            </div>
          )}

          {sceneLoad.phase === "error" && (
            <div role="alert" className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-zinc-950/90 p-4 text-white"
              data-testid="viewer-state" data-state={sceneLoad.failure.kind}>
              <p className="text-lg font-medium">{sceneLoad.failure.title}</p>
              <p className="max-w-md text-center text-sm text-white/80">{sceneLoad.failure.message}</p>
              {sceneLoad.failure.retryable && (
                // A verified model is not downloaded again (lib/artifact-cache): a graphics failure only rebuilds the viewer.
                <button className="min-h-10 rounded border border-white/40 px-3 py-1.5 text-sm" onClick={() => setReloadToken((t) => t + 1)}>
                  Retry
                </button>
              )}
            </div>
          )}
        </div>

        {venueId && (
          <aside className="max-h-[45dvh] w-full shrink-0 space-y-4 overflow-y-auto border-t bg-white p-4 text-sm md:max-h-none md:w-80 md:border-l md:border-t-0">
            <SemanticSearchPanel venueId={venueId} floorId={floorId} scanVersionId={scanVersionId} onSelectResult={onSelectSearchResult} />

            {reconstruction && pendingHere && (
              <section className="rounded border border-sky-200 bg-sky-50 p-2 text-xs text-sky-900" data-testid="newer-processing">
                <p className="font-medium">A newer capture of this floor is being processed.</p>
                <ProcessingNotice progress={pendingHere} />
              </section>
            )}

            {reconstruction && (
              <section>
                <h2 className="font-medium">Reconstruction</h2>
                <dl className="mt-1 grid grid-cols-[6rem_1fr] gap-1 text-xs text-zinc-700">
                  <dt>Version</dt>
                  <dd data-testid="scan-version">
                    {reconstruction.versionNumber != null
                      ? `v${reconstruction.versionNumber}${reconstruction.parentVersionId
                          ? ` (re-scan of v${versions.find((v) => v.scanVersionId === reconstruction.parentVersionId)?.versionNumber ?? "?"})`
                          : ""}`
                      : "Not a finalized version"}
                  </dd>
                  <dt>Generated</dt><dd>{formatDate(reconstruction.generatedAt)}</dd>
                  <dt>Run status</dt><dd>{reconstruction.runStatus}</dd>
                  <dt>Quality</dt><dd>{reconstruction.runQuality ?? "—"}</dd>
                  <dt>Coordinates</dt>
                  <dd data-testid="coordinate-frame-status">
                    {isCanonical(frame)
                      ? `Calibrated, metres (${frame.horizontalDatum === "VENUE_CONTROL_POINTS" ? "venue datum" : "floor-local"}, v${frame.version})`
                      : "Not calibrated: POIs and routes are not drawn on this reconstruction"}
                  </dd>
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
                  {sceneReady && quality?.medianMs != null && <>
                    <dt>Render scale</dt>
                    <dd data-testid="render-scale">
                      {Math.round((quality.pixelRatio / quality.maxPixelRatio) * 100)}% · {Math.round(1000 / quality.medianMs)} fps measured
                    </dd>
                  </>}
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
                      onClick={() => selectPoi(p.id === selectedPoiId ? null : p.id)}
                    >
                      {p.label}
                      {reconstruction && !placedPoiIds.has(p.id) && (
                        <span className="ml-1 text-[11px] text-zinc-500">(not shown on this model)</span>
                      )}
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
                {selectedPoi.floorId && <p className="mt-1 text-xs text-zinc-600">Floor: {floorName(selectedPoi.floorId)}</p>}
                {!placedPoiIds.has(selectedPoi.id) && (
                  <p className="mt-1 text-xs text-zinc-600" data-testid="poi-not-placed">
                    {!canonicalTransform
                      ? "This model is not calibrated, so POIs are not shown on it."
                      : selectedPoi.frameStatus === "UNBOUND"
                        ? "Not placed in a calibrated frame, so it is not shown on the model."
                        : "Placed in a different coordinate frame than this model, so it is not shown on it."}
                  </p>
                )}
                <div className="mt-2 flex gap-2">
                  {placedPoiIds.has(selectedPoi.id) && sceneReady && (
                    <button className="rounded border bg-white px-2 py-0.5 text-xs" onClick={() => canvasRef.current?.focusPoi(selectedPoi.id)}>
                      Focus
                    </button>
                  )}
                  <button className="rounded border bg-white px-2 py-0.5 text-xs" onClick={() => setRouteToId(selectedPoi.id)}>
                    Route here
                  </button>
                </div>
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
                {routeBusy && <p className="text-xs text-zinc-500">Finding a route…</p>}
                {routeOutcomes && !routeBusy && (
                  <div className="space-y-2" role="radiogroup" aria-label="Route options">
                    {PROFILES.map((profile) => {
                      const outcome = routeOutcomes[profile];
                      if (!outcome.ok) {
                        return (
                          <div
                            key={profile}
                            role="alert"
                            data-testid={`route-${profile}`}
                            data-failure={outcome.failure.kind}
                            className="rounded border border-red-200 bg-red-50 p-2 text-xs text-red-800"
                          >
                            <p className="font-medium">{PROFILE_LABEL[profile]}</p>
                            <p>No route available: {outcome.failure.message}</p>
                            {outcome.failure.message !== outcome.detail && <p className="mt-0.5 text-red-700/80">{outcome.detail}</p>}
                          </div>
                        );
                      }
                      const active = profile === routeProfile;
                      const summary = routeSummary(outcome.route);
                      return (
                        <button
                          key={profile}
                          role="radio"
                          aria-checked={active}
                          data-testid={`route-${profile}`}
                          onClick={() => setRouteProfile(profile)}
                          className={`block w-full rounded border p-2 text-left text-xs text-zinc-700 ${active ? "border-amber-400 bg-amber-50" : "hover:bg-zinc-50"}`}
                        >
                          <p className="font-medium">{PROFILE_LABEL[profile]}{active ? " · shown" : " · select to show"}</p>
                          {summary.distanceMeters !== null && (
                            <p>
                              {formatDistance(summary.distanceMeters)}
                              {summary.durationSeconds !== null && <> · ~{formatDuration(summary.durationSeconds)} (estimate)</>}
                            </p>
                          )}
                          {outcome.route.floorTransitions.length > 0 && (
                            <p className="mt-1">
                              Via{" "}
                              {outcome.route.floorTransitions
                                .map((t) => `${t.connectorType.toLowerCase()} to ${floorName(t.toFloorId)}`)
                                .join(", ")}
                            </p>
                          )}
                          {outcome.route.accessibilityConstraintsApplied.length > 0 && (
                            <ul className="mt-1 list-disc pl-4">
                              {outcome.route.accessibilityConstraintsApplied.map((c) => <li key={c}>{c}</li>)}
                            </ul>
                          )}
                        </button>
                      );
                    })}
                    {overlay?.mismatch && (
                      <p role="alert" className="text-xs text-red-700" data-testid="route-mismatch">
                        This route was planned on a different {overlay.mismatch === "VERSION_MISMATCH" ? "scan version" : "coordinate frame"}{" "}
                        than the model shown, so it is not drawn on it.
                      </p>
                    )}
                    {routeResponse && !canonicalTransform && (
                      <p className="text-xs text-zinc-600">This model is not calibrated, so the route is not drawn on it.</p>
                    )}
                    {overlay && overlay.otherFloorIds.length > 0 && (
                      <p className="text-xs text-zinc-600" data-testid="route-other-floors">
                        The route continues on {overlay.otherFloorIds.map(floorName).join(", ")}; switch floors to see that part.
                      </p>
                    )}
                  </div>
                )}
              </div>
            </section>
          </aside>
        )}
      </div>
    </main>
  );
}

/** How far a floor's in-progress reconstruction has got (lib/viewer-progress), or why it failed. */
function ProcessingNotice({ progress }: { progress: ProcessingProgress }) {
  if (progress.state === "failed") {
    return (
      <p className="text-sm text-red-700" data-testid="processing-failed">
        Processing failed{progress.currentStage ? ` at ${progress.currentStage}` : ""}{progress.detail ? `: ${progress.detail}` : "."}
      </p>
    );
  }
  if (progress.state === "waiting" || progress.total === 0) {
    return <p className="text-sm" data-testid="processing-progress">A capture of this floor is waiting to be processed.</p>;
  }
  const percent = Math.round((progress.done / progress.total) * 100);
  return (
    <div className="w-full max-w-xs text-sm" data-testid="processing-progress">
      <p>
        Reconstruction in progress{progress.currentStage ? `: ${progress.currentStage}` : ""} ({progress.done} of {progress.total} stages)
      </p>
      <div className="mt-1 h-2 overflow-hidden rounded bg-zinc-200" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}
        aria-label="Reconstruction progress">
        <div className="h-full bg-sky-600" style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}
