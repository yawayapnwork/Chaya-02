"use client";

// Android AR client: WebXR inside this Next.js app (docs/ar.md, "Android: WebXR").
//
//   1. Capabilities (lib/webxr-support.ts): WebXR, immersive-ar, image tracking, anchors and camera permission are each
//      checked and each shown as its own named state. Nothing falls back to a simulated view; on desktop Chrome the page says AR
//      is unavailable and stops there.
//   2. Markers: only calibrated IMAGE_TARGET anchors with a printed size can be detected. Their server-generated target
//      images (the images the operator printed) are handed to WebXR image tracking, one per anchor, so a tracked image's
//      index identifies exactly one registered anchor. QR/ArUco/AprilTag anchors are listed as undetectable here.
//   3. Localization: a real image-tracking observation (tracked, not emulated, width matching the printed size) goes to
//      POST .../anchors/relocalize, which solves the AR world -> canonical venue transform. A WebXR world anchor is created
//      at that marker's observed pose in the same frame; it identifies nothing, it only carries the platform's later map
//      corrections into the transform (lib/ar-route.ts anchorCorrectedDeviceToVenue).
//   4. Route: fetched from POST /navigation/routes with the device's canonical position as the start, placed in the AR
//      world frame with the anchor-corrected transform, and drawn by lib/ar-route-renderer.ts. Progress along it
//      updates from the platform's viewer pose every frame, only while localized.
//   5. Tracking loss (no viewer pose, emulated position, session hidden, world anchor no longer tracked): the route is
//      hidden and frozen, the UI says tracking is lost, and only a fresh marker observation followed by a successful
//      relocalization brings it back. Before the first localization, a loss only means "waiting for device tracking".
//
// It cannot be exercised on this development machine (no WebXR). docs/ar-android-validation.md is the device procedure.

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { ApiError, type Floor, type Venue, listFloors, listVenues } from "@/lib/capture-api";
import { type Anchor, fetchTargetImage, listAnchors, relocalize } from "@/lib/ar-api";
import { type Poi, listPois } from "@/lib/poi-api";
import { type RouteResponse, planRoute } from "@/lib/navigation-api";
import {
  type AnchorSupport,
  type ArProblem,
  type EnvironmentReport,
  PROBLEM_TEXT,
  anchorSupport,
  classifySessionError,
  detectArEnvironment,
  trackableAnchors,
} from "@/lib/webxr-support";
import {
  type MarkerObservation,
  type TrackedMarker,
  type XrImageTrackingResultLike,
  type XrTrackedImageInit,
  observeImages,
  poseFromXrTransform,
  relocalizationEligibility,
} from "@/lib/ar-marker-tracking";
import {
  type ArSessionState,
  type ArEvent,
  canAdvanceRoute,
  frameTrackingSignal,
  initialArSessionState,
  reduceArSession,
} from "@/lib/ar-relocalization";
import {
  type RouteProgress,
  advanceProgress,
  anchorCorrectedDeviceToVenue,
  devicePositionInVenue,
  routeLegOnFloor,
  venueToArWorld,
} from "@/lib/ar-route";
import type { Vec3 } from "@/lib/coordinate-frame";
import type { Pose } from "@/lib/ar-anchor-math";
import { ArRouteRenderer } from "@/lib/ar-route-renderer";

const SUPPORT_TEXT: Record<AnchorSupport, string> = {
  TRACKABLE: "detectable (WebXR image tracking)",
  FIDUCIAL_DETECTION_UNSUPPORTED: "not detectable here: WebXR has no QR/ArUco/AprilTag detection",
  MISSING_PRINTED_SIZE: "not detectable: no printed size registered",
  NOT_CALIBRATED: "not usable: not calibrated",
};

const RETRY_AFTER_FAILURE_MS = 1500;
/** How long a newly created world anchor may be missing from XRFrame.trackedAnchors before that counts as a loss. */
const WORLD_ANCHOR_GRACE_MS = 1000;

function message(e: unknown): string {
  if (e instanceof ApiError) return `${e.code}: ${e.message}`;
  if (e instanceof Error) return e.message;
  return String(e);
}

interface LoadedMarkers {
  images: XrTrackedImageInit[];
  markers: TrackedMarker[];
}

type ImageTrackingSession = XRSession & { getTrackedImageScores?: () => Promise<string[]> };
type ImageTrackingFrame = XRFrame & { getImageTrackingResults?: () => XrImageTrackingResultLike[] };

export default function ArWorkspace() {
  const searchParams = useSearchParams();
  const [venues, setVenues] = useState<Venue[]>([]);
  const [venueId, setVenueId] = useState(searchParams.get("venue") ?? "");
  const [floors, setFloors] = useState<Floor[]>([]);
  const [floorId, setFloorId] = useState(searchParams.get("floor") ?? "");
  const [anchors, setAnchors] = useState<Anchor[]>([]);
  const [pois, setPois] = useState<Poi[]>([]);
  const [destinationId, setDestinationId] = useState("");
  const [error, setError] = useState<string | null>(null);

  const [env, setEnv] = useState<EnvironmentReport | null>(null);
  const [sessionProblem, setSessionProblem] = useState<{ problem: ArProblem; detail: string } | null>(null);
  const [loaded, setLoaded] = useState<LoadedMarkers | null>(null);
  const [loadingMarkers, setLoadingMarkers] = useState(false);
  const [inSession, setInSession] = useState(false);

  // Live session state lives in refs (updated every frame); the UI copy is refreshed on transitions and a few times a second.
  const stateRef = useRef<ArSessionState>(initialArSessionState());
  const [ui, setUi] = useState<{ state: ArSessionState; progress: RouteProgress | null; route: RouteResponse | null }>({
    state: initialArSessionState(), progress: null, route: null,
  });
  const routeRef = useRef<{ route: RouteResponse; leg: Vec3[] } | null>(null);
  const progressRef = useRef<RouteProgress | null>(null);
  const markersRef = useRef<TrackedMarker[]>([]);
  const rendererRef = useRef<ArRouteRenderer | null>(null);
  const sessionRef = useRef<XRSession | null>(null);
  /** The WebXR world anchor created where the localizing marker was observed, and its pose then (lib/ar-route.ts). */
  const worldAnchorRef = useRef<{ anchor: XRAnchor; poseAtSolve: Pose; createdAtMs: number; seenTracked: boolean } | null>(null);
  const onFrameRef = useRef<(time: number, frame: XRFrame) => void>(() => {});
  const lastFailureAtRef = useRef(0);
  const lastUiAtRef = useRef(0);
  const overlayRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => { detectArEnvironment().then(setEnv); }, []);
  useEffect(() => { listVenues().then(setVenues).catch((e) => setError(message(e))); }, []);
  useEffect(() => {
    if (!venueId) return;
    listFloors(venueId).then(setFloors).catch((e) => setError(message(e)));
    listPois(venueId).then(setPois).catch((e) => setError(message(e)));
  }, [venueId]);
  useEffect(() => {
    if (!venueId || !floorId) return;
    listAnchors(venueId, floorId).then(setAnchors).catch((e) => setError(message(e)));
  }, [venueId, floorId]);
  useEffect(() => () => { sessionRef.current?.end().catch(() => {}); }, []);

  const trackable = useMemo(() => trackableAnchors(anchors), [anchors]);
  const destinations = useMemo(() => pois.filter((p) => p.floorId === floorId && p.frameStatus === "CURRENT"), [pois, floorId]);

  // Target images are loaded before the session: requestSession must run inside the tap's user activation.
  useEffect(() => {
    if (!venueId || !floorId || trackable.length === 0 || env?.problem) return;
    let cancelled = false;
    Promise.resolve().then(() => { if (!cancelled) { setLoaded(null); setLoadingMarkers(true); } });
    Promise.all(trackable.map(async (a, index) => ({
      image: await createImageBitmap(await fetchTargetImage(venueId, floorId, a.id)),
      marker: { index, anchorId: a.id, markerIdentifier: a.markerIdentifier, widthInMeters: a.markerSizeMeters as number },
    })))
      .then((items) => {
        if (!cancelled) setLoaded({ images: items.map((i) => ({ image: i.image, widthInMeters: i.marker.widthInMeters })), markers: items.map((i) => i.marker) });
      })
      .catch((e) => !cancelled && setError(message(e)))
      .finally(() => !cancelled && setLoadingMarkers(false));
    return () => { cancelled = true; };
  }, [venueId, floorId, trackable, env]);

  const publish = useCallback((force: boolean) => {
    const now = performance.now();
    if (!force && now - lastUiAtRef.current < 250) return;
    lastUiAtRef.current = now;
    setUi({ state: stateRef.current, progress: progressRef.current, route: routeRef.current?.route ?? null });
  }, []);

  const dispatch = useCallback((event: ArEvent) => {
    const next = reduceArSession(stateRef.current, event);
    if (next !== stateRef.current) {
      stateRef.current = next;
      publish(true);
    }
  }, [publish]);

  /** Sends real observations to the server and waits for the world anchor created at the first one's pose. The answer is
   * applied only if that request is still the pending one; otherwise the new anchor is released. */
  const relocalizeFrom = useCallback(async (
    requestId: number, observations: MarkerObservation[], viewerPose: Pose, anchor: Promise<XRAnchor>, anchorPose: Pose,
  ) => {
    let created: XRAnchor | null = null;
    let deviceToVenue: Pose;
    try {
      const [result, worldAnchor] = await Promise.all([
        relocalize(venueId, floorId, observations.map((o) => ({ anchorId: o.anchorId, observedPose: o.observedPose }))),
        anchor.then((a) => (created = a)),
      ]);
      deviceToVenue = result.deviceToVenueTransform;
      const localization = {
        deviceToVenue, anchorIds: observations.map((o) => o.anchorId),
        residualMeters: result.residualMeters, coordinateFrameId: result.coordinateFrameId, solvedAtMs: performance.now(),
      };
      dispatch({ type: "RELOCALIZATION_SUCCEEDED", requestId, localization });
      if (stateRef.current.localization !== localization) {
        worldAnchor.delete(); // superseded: tracking was lost while the request was in flight
        return;
      }
      worldAnchorRef.current?.anchor.delete();
      worldAnchorRef.current = { anchor: worldAnchor, poseAtSolve: anchorPose, createdAtMs: performance.now(), seenTracked: false };
    } catch (e) {
      (created as XRAnchor | null)?.delete();
      lastFailureAtRef.current = performance.now();
      dispatch({ type: "RELOCALIZATION_FAILED", requestId, error: message(e) });
      return;
    }
    // The route is fetched once, from where the device was when it was first localized. A failure here is a routing
    // error, not a localization one: the localization and its world anchor stay.
    if (routeRef.current || !destinationId) return;
    try {
      const route = await planRoute({ venueId, floorId, start: devicePositionInVenue(viewerPose, deviceToVenue), destinationPoiId: destinationId });
      routeRef.current = { route, leg: routeLegOnFloor(route, floorId).points };
      publish(true);
    } catch (e) {
      setError(`Route: ${message(e)}`);
    }
  }, [venueId, floorId, destinationId, dispatch, publish]);

  const onFrame = useCallback((time: number, frame: XRFrame) => {
    const renderer = rendererRef.current;
    const ref = renderer?.referenceSpace();
    if (!renderer || !ref) return;
    const viewer = frame.getViewerPose(ref);
    const signal = frameTrackingSignal(viewer ? { emulatedPosition: viewer.emulatedPosition } : null, frame.session.visibilityState);
    if (!signal.ok || !viewer) {
      dispatch({ type: "TRACKING_LOST", reason: signal.reason ?? "tracking lost" });
      renderer.setRouteVisible(false);
      return;
    }
    dispatch({ type: "TRACKING_RESTORED" });
    const viewerPose = poseFromXrTransform(viewer.transform);

    const state = stateRef.current.state;
    if ((state === "SEARCHING" || state === "TRACKING_LOST") && performance.now() - lastFailureAtRef.current > RETRY_AFTER_FAILURE_MS) {
      const results = (frame as ImageTrackingFrame).getImageTrackingResults?.() ?? [];
      const observed = observeImages(results, markersRef.current, (space) => {
        const pose = frame.getPose(space as XRSpace, ref);
        return pose ? poseFromXrTransform(pose.transform) : null;
      }, time);
      const eligible = observed.filter((o) => relocalizationEligibility(o).eligible);
      if (eligible.length > 0) {
        dispatch({ type: "MARKERS_OBSERVED", observations: eligible });
        const pending = stateRef.current.pending;
        if (pending && stateRef.current.state === "RELOCALIZING") {
          // The world anchor must be created from this frame, at the pose the marker was measured at.
          const at = eligible[0].observedPose;
          const anchor = frame.createAnchor
            ? frame.createAnchor(new XRRigidTransform({ x: at.x, y: at.y, z: at.z }, { x: at.qx, y: at.qy, z: at.qz, w: at.qw }), ref)
            : Promise.reject(new Error(PROBLEM_TEXT.ANCHORS_UNSUPPORTED));
          void relocalizeFrom(pending.requestId, eligible, viewerPose, anchor, at);
        }
      }
    }

    const s = stateRef.current;
    const leg = routeRef.current?.leg ?? [];
    const worldAnchor = worldAnchorRef.current;
    if (!canAdvanceRoute(s) || !s.localization || !worldAnchor) {
      renderer.setRouteVisible(false); // not localized: nothing drawn, progress frozen
      return;
    }
    const anchorPose = frame.trackedAnchors?.has(worldAnchor.anchor) ? frame.getPose(worldAnchor.anchor.anchorSpace, ref) : null;
    if (!anchorPose) {
      // A new anchor may take a frame or two to appear in trackedAnchors. Until it is first seen the route stays hidden
      // and frozen. After that, or once the grace period is over, its absence is a tracking loss.
      if (!worldAnchor.seenTracked && performance.now() - worldAnchor.createdAtMs < WORLD_ANCHOR_GRACE_MS) {
        renderer.setRouteVisible(false);
        return;
      }
      dispatch({ type: "TRACKING_LOST", reason: "the platform no longer tracks the world anchor the route is attached to" });
      renderer.setRouteVisible(false);
      return;
    }
    worldAnchor.seenTracked = true;
    const deviceToVenue = anchorCorrectedDeviceToVenue(
      s.localization.deviceToVenue, worldAnchor.poseAtSolve, poseFromXrTransform(anchorPose.transform),
    );
    if (leg.length > 0) {
      progressRef.current = advanceProgress(progressRef.current, true, leg, devicePositionInVenue(viewerPose, deviceToVenue));
      renderer.setRoute(leg, progressRef.current?.nextIndex ?? 0);
      renderer.setVenueToWorld(venueToArWorld(deviceToVenue));
      renderer.setRouteVisible(true);
      publish(false);
    }
  }, [dispatch, publish, relocalizeFrom]);
  useEffect(() => { onFrameRef.current = onFrame; }, [onFrame]);

  async function startSession() {
    setError(null);
    setSessionProblem(null);
    const xr = navigator.xr;
    if (!xr || !loaded) return;
    let session: XRSession;
    try {
      session = await xr.requestSession("immersive-ar", {
        requiredFeatures: ["image-tracking", "anchors"],
        optionalFeatures: ["dom-overlay"],
        ...(overlayRef.current ? { domOverlay: { root: overlayRef.current } } : {}),
        trackedImages: loaded.images,
      } as XRSessionInit);
    } catch (e) {
      setSessionProblem(classifySessionError(e));
      return;
    }
    sessionRef.current = session;
    const scoring = (session as ImageTrackingSession).getTrackedImageScores;
    if (!scoring) {
      // granted "image-tracking" without its API: nothing is assumed trackable
      setSessionProblem({ problem: "IMAGE_TRACKING_UNSUPPORTED", detail: PROBLEM_TEXT.IMAGE_TRACKING_UNSUPPORTED });
      await session.end();
      return;
    }
    const scores = await scoring.call(session);
    markersRef.current = loaded.markers.filter((m) => scores[m.index] === "trackable");
    if (markersRef.current.length === 0) {
      setSessionProblem({ problem: "NO_TRACKABLE_MARKERS", detail: PROBLEM_TEXT.NO_TRACKABLE_MARKERS });
      await session.end();
      return;
    }
    const renderer = new ArRouteRenderer();
    rendererRef.current = renderer;
    stateRef.current = initialArSessionState();
    routeRef.current = null;
    progressRef.current = null;
    worldAnchorRef.current = null;
    session.addEventListener("visibilitychange", () => {
      if (session.visibilityState !== "visible") dispatch({ type: "TRACKING_LOST", reason: `the AR session is ${session.visibilityState}` });
    });
    session.addEventListener("end", () => {
      dispatch({ type: "SESSION_ENDED" });
      renderer.dispose();
      rendererRef.current = null;
      sessionRef.current = null;
      worldAnchorRef.current = null; // the platform deletes a session's anchors when it ends
      setInSession(false);
    });
    await renderer.attach(session);
    dispatch({ type: "SESSION_STARTED" });
    setInSession(true);
    renderer.start((time, frame) => onFrameRef.current(time, frame));
  }

  if (env === null) return <p className="p-8">Checking AR capabilities…</p>;

  const blocking = env.problem ?? null;
  const s = ui.state;
  return (
    <div className="p-6 space-y-4 max-w-2xl">
      <h1 className="text-xl font-semibold">AR navigation (Android, WebXR)</h1>
      {blocking && (
        <div className="border border-amber-500 rounded p-4 space-y-2" data-testid="ar-unavailable">
          <p className="font-medium">AR unavailable: {blocking}</p>
          <p className="text-sm">{env.detail}</p>
        </div>
      )}
      {sessionProblem && (
        <div className="border border-red-500 rounded p-4" data-testid="ar-session-problem">
          <p className="font-medium">{sessionProblem.problem}</p>
          <p className="text-sm">{sessionProblem.detail}</p>
        </div>
      )}
      {error && <p className="text-sm text-red-600">{error}</p>}

      {!blocking && (
        <>
          <div className="flex flex-wrap gap-3">
            <select className="border p-2" value={venueId} onChange={(e) => { setVenueId(e.target.value); setFloorId(""); setAnchors([]); setDestinationId(""); }}>
              <option value="">Select venue…</option>
              {venues.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
            </select>
            <select className="border p-2" value={floorId} onChange={(e) => { setFloorId(e.target.value); setAnchors([]); setDestinationId(""); }} disabled={!venueId}>
              <option value="">Select floor…</option>
              {floors.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
            </select>
            <select className="border p-2" value={destinationId} onChange={(e) => setDestinationId(e.target.value)} disabled={!floorId}>
              <option value="">Select destination…</option>
              {destinations.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
            </select>
          </div>

          {floorId && (
            <div className="text-sm space-y-1">
              <p className="font-medium">Registered markers on this floor</p>
              {anchors.length === 0 && <p>None.</p>}
              <ul className="list-disc pl-5">
                {anchors.map((a) => <li key={a.id}>{a.markerType} “{a.markerIdentifier}”: {SUPPORT_TEXT[anchorSupport(a)]}</li>)}
              </ul>
              {anchors.length > 0 && trackable.length === 0 && (
                <p className="text-amber-700" data-testid="no-detectable-anchors">NO_DETECTABLE_ANCHORS: {PROBLEM_TEXT.NO_DETECTABLE_ANCHORS}</p>
              )}
              {loadingMarkers && <p>Loading marker images…</p>}
            </div>
          )}

          <button
            className="border px-4 py-2 rounded disabled:opacity-50"
            disabled={!loaded || !destinationId || inSession}
            onClick={startSession}
          >
            Start AR navigation
          </button>
        </>
      )}

      {/* The in-session status, shown over the camera view through the WebXR DOM overlay when the browser provides it. */}
      <div ref={overlayRef} className={inSession ? "fixed inset-x-0 top-0 p-3 bg-black/60 text-white space-y-1" : "hidden"}>
        <p className="font-medium" data-testid="ar-state">{s.state}</p>
        {s.state === "SEARCHING" && !s.deviceTracking && <p>Waiting for device tracking ({s.lostReason ?? "starting"}). Move the phone slowly.</p>}
        {s.state === "SEARCHING" && s.deviceTracking && <p>Point the camera at a registered marker.</p>}
        {s.state === "RELOCALIZING" && <p>Marker seen ({s.pending?.observations.map((o) => o.markerIdentifier).join(", ")}): localizing…</p>}
        {s.state === "LOCALIZED" && s.localization && (
          <p>
            Localized from {s.localization.anchorIds.length} marker(s)
            {s.localization.residualMeters === null ? " (residual unknown: one marker)" : `, residual ${s.localization.residualMeters.toFixed(2)} m`}.
            {ui.progress && ` ${ui.progress.remainingMeters.toFixed(1)} m to go, ${ui.progress.offRouteMeters.toFixed(1)} m off route.`}
            {!ui.route && " Fetching route…"}
          </p>
        )}
        {s.state === "TRACKING_LOST" && (
          <p>
            Tracking lost ({s.lostReason}). Route position frozen
            {ui.progress ? ` at ${ui.progress.remainingMeters.toFixed(1)} m to go` : ""}. Point the camera at a registered
            marker to relocalize.
          </p>
        )}
        {s.lastError && <p className="text-amber-300">Last relocalization failed: {s.lastError}</p>}
        {ui.route && ui.route.floorTransitions.length > 0 && <p>The route continues on another floor via {ui.route.floorTransitions[0].connectorType}.</p>}
        {inSession && <button className="border px-3 py-1 rounded" onClick={() => sessionRef.current?.end()}>End AR</button>}
      </div>
    </div>
  );
}
