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
//   5. Tracking loss (no viewer pose, session hidden, world anchor no longer tracked): the route is hidden and frozen, the
//      UI says tracking is lost, and only a fresh marker observation followed by a successful relocalization brings it
//      back. Limited tracking (emulated position) while localized holds the last valid overlay for up to 3 s, frozen, and
//      asks the user to stand still; it resumes only if the world anchor is tracked again and barely moved (validated
//      recovery), else it is a loss. Before the first localization, a loss only means "waiting for device tracking".
//   6. Versions (lib/ar-relocalization.ts, lib/ar-navigation.ts): only anchors whose pose was entered against a scan
//      version are registered; each floor's version is pinned at its first localization and sent with every later
//      relocalization (the server refuses another one); a route leg is drawn only if the server routed that floor in the
//      session's version and frame.
//   7. Drift: while localized, a registered marker seen again is checked against the transform. Too far off, the
//      transform is dropped and the session relocalizes from that marker; otherwise it refreshes the transform, which is
//      blended in over a fraction of a second for drawing.
//   8. Floors: every floor's markers are registered for the session. Near the end of this floor's leg (a TRANSITION) the
//      session hands off: the transform is dropped (each floor has its own canonical frame) and the next floor is
//      localized from its own markers.
//   9. Failures (lib/ar-errors.ts): an expired sign-in/viewer link stops relocalization and routing until the user signs in
//      again; a network failure is retried and a route already drawn stays usable; a marker the server will no longer use
//      is dropped for the session; markers that disagree (one moved) ask for one marker at a time or a third.
//
// It cannot be exercised on this development machine (no WebXR). docs/ar-android-validation.md is the device procedure.

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { ApiError, type Floor, type Venue, listFloors, listVenues } from "@/lib/capture-api";
import { type Anchor, fetchTargetImage, listAnchors, relocalize } from "@/lib/ar-api";
import { type Poi, listPois } from "@/lib/poi-api";
import { type RouteResponse, type RouteWaypoint, planRoute } from "@/lib/navigation-api";
import {
  type AnchorSupport,
  type ArProblem,
  type EnvironmentReport,
  PROBLEM_TEXT,
  IOS_NATIVE_TEXT,
  anchorSupport,
  classifySessionError,
  detectArEnvironment,
  isAppleMobile,
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
  routeShown,
} from "@/lib/ar-relocalization";
import { type ClassifiedFailure, classifyArFailure } from "@/lib/ar-errors";
import {
  type RouteProgress,
  advanceProgress,
  anchorCorrectedDeviceToVenue,
  devicePositionInVenue,
  venueToArWorld,
} from "@/lib/ar-route";
import type { Vec3 } from "@/lib/coordinate-frame";
import type { Pose } from "@/lib/ar-anchor-math";
import { ArRouteRenderer } from "@/lib/ar-route-renderer";
import {
  type ArrivalState,
  type Instruction,
  type RoutePosition,
  initialArrival,
  locateOnRoute,
  nextInstruction,
  routeInstructions,
  updateArrival,
} from "@/lib/route-instructions";
import { type ObstacleObservation, obstacleSourceStatus, rerouteDecision, toBlockedRegions } from "@/lib/obstacle-reroute";
import { formatDistance } from "@/lib/viewer-route";
import {
  arRouteRequest,
  arSessionInit,
  blendedTransform,
  driftExceeded,
  floorHandoff,
  markerDrift,
  refreshDue,
  routeLegFor,
} from "@/lib/ar-navigation";

const SUPPORT_TEXT: Record<AnchorSupport, string> = {
  TRACKABLE: "detectable (WebXR image tracking)",
  FIDUCIAL_DETECTION_UNSUPPORTED: "not detectable here: WebXR has no QR/ArUco/AprilTag detection",
  MISSING_PRINTED_SIZE: "not detectable: no printed size registered",
  NOT_CALIBRATED: "not usable: not calibrated",
  UNVERSIONED: "not usable: its pose was never entered against a scan version (re-enter it)",
};

/** WebXR image tracking cost grows with the number of registered images. The current floor's come first. */
const MAX_TRACKED_IMAGES = 32;

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
  /** Every floor's anchors: a route may cross floors, and WebXR fixes the tracked images when the session starts. */
  const [venueAnchors, setVenueAnchors] = useState<Anchor[]>([]);
  const [pois, setPois] = useState<Poi[]>([]);
  const [destinationId, setDestinationId] = useState("");
  const [stepFree, setStepFree] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** The last failed request's meaning (lib/ar-errors.ts), shown in the overlay. */
  const [failure, setFailure] = useState<ClassifiedFailure | null>(null);
  const [online, setOnline] = useState(true);

  const [env, setEnv] = useState<EnvironmentReport | null>(null);
  const [sessionProblem, setSessionProblem] = useState<{ problem: ArProblem; detail: string } | null>(null);
  const [loaded, setLoaded] = useState<LoadedMarkers | null>(null);
  const [loadingMarkers, setLoadingMarkers] = useState(false);
  const [inSession, setInSession] = useState(false);

  // Live session state lives in refs (updated every frame); the UI copy is refreshed on transitions and a few times a second.
  const stateRef = useRef<ArSessionState>(initialArSessionState());
  const [ui, setUi] = useState<{
    state: ArSessionState; progress: RouteProgress | null; route: RouteResponse | null;
    next: Instruction | null; remainingMeters: number | null; arrived: boolean; obstacles: "LIVE" | "UNAVAILABLE";
  }>({
    state: initialArSessionState(), progress: null, route: null, next: null, remainingMeters: null, arrived: false,
    obstacles: "UNAVAILABLE",
  });
  const routeRef = useRef<{ route: RouteResponse } | null>(null);
  /** The leg drawn now: this floor's part of the route, checked against the localization's version and frame. */
  const legRef = useRef<{ floorId: string; points: Vec3[]; waypoints: RouteWaypoint[] } | null>(null);
  /** After a refresh, the transform drawn just before it, blended into the new one (lib/ar-navigation.ts). */
  const blendRef = useRef<{ from: Pose; startedAtMs: number } | null>(null);
  const lastDrawnRef = useRef<Pose | null>(null);
  const progressRef = useRef<RouteProgress | null>(null);
  /** Where the device is on the whole route, the turn-by-turn instructions, and arrival (lib/route-instructions.ts). */
  const positionRef = useRef<RoutePosition | null>(null);
  const instructionsRef = useRef<Instruction[]>([]);
  const arrivalRef = useRef<ArrivalState | null>(null);
  /** Obstacles the device observed (lib/obstacle-reroute.ts). null: this client has no obstacle source -- WebXR gives no
   * obstacle detection here -- so nothing is reported and the UI says the route does not adapt to obstacles. */
  const obstacleSourceRef = useRef<ObstacleObservation[] | null>(null);
  const routedAroundRef = useRef<ObstacleObservation[]>([]);
  const lastRerouteAtRef = useRef<number | null>(null);
  const markersRef = useRef<TrackedMarker[]>([]);
  const rendererRef = useRef<ArRouteRenderer | null>(null);
  const sessionRef = useRef<XRSession | null>(null);
  /** The WebXR world anchor created where the localizing marker was observed, and its pose then (lib/ar-route.ts). */
  const worldAnchorRef = useRef<{ anchor: XRAnchor; poseAtSolve: Pose; createdAtMs: number; seenTracked: boolean } | null>(null);
  const onFrameRef = useRef<(time: number, frame: XRFrame) => void>(() => {});
  const fetchRouteRef = useRef<(viewerPose: Pose, deviceToVenue: Pose, obstacles?: ObstacleObservation[]) => Promise<void>>(async () => {});
  const lastFailureAtRef = useRef(0);
  /** Requests are not retried on their own after this (an expired sign-in or viewer link). */
  const blockedRef = useRef<ClassifiedFailure | null>(null);
  /** Markers the server will no longer use (deleted, not calibrated, stale): not sent again this session. */
  const unavailableRef = useRef<Set<string>>(new Set());
  /** The world anchor's pose when a LIMITED spell began, to validate the recovery. */
  const limitedAnchorRef = useRef<Pose | null>(null);
  const lastViewerRef = useRef<Pose | null>(null);
  const stepFreeRef = useRef(false);
  useEffect(() => { stepFreeRef.current = stepFree; }, [stepFree]);
  const lastUiAtRef = useRef(0);
  const overlayRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => { detectArEnvironment().then(setEnv); }, []);
  const [appleMobile, setAppleMobile] = useState(false);
  useEffect(() => {
    Promise.resolve().then(() => setAppleMobile(isAppleMobile(navigator.userAgent, navigator.maxTouchPoints ?? 0)));
  }, []);
  // Network loss is shown as a state; when the connection returns, a route that could not be fetched is fetched again.
  useEffect(() => {
    const update = () => {
      setOnline(navigator.onLine);
      const localization = stateRef.current.localization;
      if (navigator.onLine && !routeRef.current && localization && lastViewerRef.current && canAdvanceRoute(stateRef.current)) {
        void fetchRouteRef.current(lastViewerRef.current, localization.deviceToVenue);
      }
    };
    update();
    window.addEventListener("online", update);
    window.addEventListener("offline", update);
    return () => {
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, []);
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
  useEffect(() => {
    if (!venueId || floors.length === 0) return;
    let cancelled = false;
    Promise.all(floors.map((f) => listAnchors(venueId, f.id)))
      .then((lists) => { if (!cancelled) setVenueAnchors(lists.flat()); })
      .catch((e) => !cancelled && setError(message(e)));
    return () => { cancelled = true; };
  }, [venueId, floors]);
  useEffect(() => () => { sessionRef.current?.end().catch(() => {}); }, []);

  const trackable = useMemo(() => trackableAnchors(anchors), [anchors]);
  /** What the session registers: this floor's trackable anchors first, then every other floor's. */
  const registered = useMemo(() => {
    const others = trackableAnchors(venueAnchors).filter((a) => a.floorId !== floorId);
    return [...trackable, ...others].slice(0, MAX_TRACKED_IMAGES);
  }, [trackable, venueAnchors, floorId]);
  // Any floor: a route to another floor crosses a registered floor connection. Only POIs of the published version.
  const destinations = useMemo(() => pois.filter((p) => p.frameStatus === "CURRENT" && p.current), [pois]);

  // Target images are loaded before the session: requestSession must run inside the tap's user activation.
  useEffect(() => {
    if (!venueId || !floorId || trackable.length === 0 || env?.problem) return;
    let cancelled = false;
    Promise.resolve().then(() => { if (!cancelled) { setLoaded(null); setLoadingMarkers(true); } });
    Promise.all(registered.map(async (a, index) => ({
      image: await createImageBitmap(await fetchTargetImage(venueId, a.floorId, a.id)),
      marker: {
        index, anchorId: a.id, markerIdentifier: a.markerIdentifier, widthInMeters: a.markerSizeMeters as number,
        floorId: a.floorId, scanVersionId: a.scanVersionId ?? undefined, digitalPose: a.digitalPose,
      },
    })))
      .then((items) => {
        if (!cancelled) setLoaded({ images: items.map((i) => ({ image: i.image, widthInMeters: i.marker.widthInMeters })), markers: items.map((i) => i.marker) });
      })
      .catch((e) => !cancelled && setError(message(e)))
      .finally(() => !cancelled && setLoadingMarkers(false));
    return () => { cancelled = true; };
  }, [venueId, floorId, trackable, registered, env]);

  const publish = useCallback((force: boolean) => {
    const now = performance.now();
    if (!force && now - lastUiAtRef.current < 250) return;
    lastUiAtRef.current = now;
    setUi({
      state: stateRef.current, progress: progressRef.current, route: routeRef.current?.route ?? null,
      next: nextInstruction(instructionsRef.current, positionRef.current),
      remainingMeters: positionRef.current?.remainingMeters ?? null,
      arrived: arrivalRef.current?.arrived ?? false,
      obstacles: obstacleSourceStatus(obstacleSourceRef.current),
    });
  }, []);

  const dispatch = useCallback((event: ArEvent) => {
    const next = reduceArSession(stateRef.current, event);
    if (next !== stateRef.current) {
      stateRef.current = next;
      publish(true);
    }
  }, [publish]);

  /** This floor's leg of the route, if the server routed the floor in the localization's version and frame. */
  const updateLeg = useCallback(() => {
    const localization = stateRef.current.localization;
    const route = routeRef.current?.route;
    legRef.current = null;
    if (!route || !localization) return;
    const check = routeLegFor(route, localization);
    if (!check.ok) {
      setError(`Route not drawn: ${check.problem}: ${check.detail}`);
      return;
    }
    legRef.current = { floorId: localization.floorId, points: check.points, waypoints: check.waypoints };
    progressRef.current = null;
  }, []);

  /** Fetches the route once, from where the device is, through the same contract the viewer uses (lib/ar-navigation.ts
   * arRouteRequest: step-free when asked; a same-floor destination on the localization's scan version). A failure is a
   * routing error, not a localization one: the localization stays, and a network failure is retried when back online. */
  const fetchRoute = useCallback(async (viewerPose: Pose, deviceToVenue: Pose, obstacles: ObstacleObservation[] = []) => {
    const localization = stateRef.current.localization;
    const destination = pois.find((p) => p.id === destinationId);
    if (!localization || !destination || blockedRef.current) return;
    try {
      const route = await planRoute({
        ...arRouteRequest({
          venueId, localization, start: devicePositionInVenue(viewerPose, deviceToVenue),
          destination: { id: destination.id, floorId: destination.floorId ?? null }, stepFree: stepFreeRef.current,
        }),
        ...(obstacles.length > 0 ? { blockedRegions: toBlockedRegions(obstacles) } : {}),
      });
      routeRef.current = { route };
      routedAroundRef.current = obstacles;
      instructionsRef.current = routeInstructions(route, {
        floorName: (id) => floors.find((f) => f.id === id)?.name ?? id,
        stopName: () => destination.label,
      });
      arrivalRef.current = initialArrival(route);
      positionRef.current = null;
      setFailure(null);
      updateLeg();
      publish(true);
    } catch (e) {
      const f = classifyArFailure(e, navigator.onLine);
      if (f.failure === "SESSION_EXPIRED") blockedRef.current = f;
      setFailure(f);
      setError(`Route: ${f.failure}: ${message(e)}`);
    }
  }, [venueId, destinationId, pois, floors, publish, updateLeg]);

  /** Sends real observations to the server, in the floor's pinned scan version, and waits for the world anchor created
   * at the first one's pose. The answer is applied only if that request is still the pending one; otherwise the new
   * anchor is released. */
  const relocalizeFrom = useCallback(async (
    requestId: number, observations: MarkerObservation[], viewerPose: Pose, anchor: Promise<XRAnchor>, anchorPose: Pose,
  ) => {
    let created: XRAnchor | null = null;
    let deviceToVenue: Pose;
    const sessionFloor = stateRef.current.floorId ?? floorId;
    const previous = stateRef.current.localization;
    try {
      const [result, worldAnchor] = await Promise.all([
        relocalize(venueId, sessionFloor, observations.map((o) => ({ anchorId: o.anchorId, observedPose: o.observedPose })),
          stateRef.current.pinnedVersions[sessionFloor] ?? null),
        anchor.then((a) => (created = a)),
      ]);
      deviceToVenue = result.deviceToVenueTransform;
      if (result.rejectedAnchorIds?.length) {
        const names = observations.filter((o) => result.rejectedAnchorIds!.includes(o.anchorId)).map((o) => o.markerIdentifier);
        setError(`Marker(s) ${names.join(", ")} set aside: inconsistent with the other markers in view (moved, misidentified or ` +
          "mis-registered). Ask an operator to check them.");
      }
      const localization = {
        deviceToVenue, anchorIds: observations.map((o) => o.anchorId).filter((id) => !(result.rejectedAnchorIds ?? []).includes(id)),
        residualMeters: result.residualMeters,
        coordinateFrameId: result.coordinateFrameId, scanVersionId: result.scanVersionId, floorId: sessionFloor,
        solvedAtMs: performance.now(),
      };
      dispatch({ type: "RELOCALIZATION_SUCCEEDED", requestId, localization });
      if (stateRef.current.localization !== localization) {
        worldAnchor.delete(); // superseded or refused (tracking lost meanwhile, another floor, another version)
        return;
      }
      // A refresh on the same floor is blended in for drawing; a first or new-floor localization is not.
      blendRef.current = previous && previous.floorId === sessionFloor && lastDrawnRef.current
        ? { from: lastDrawnRef.current, startedAtMs: performance.now() } : null;
      worldAnchorRef.current?.anchor.delete();
      worldAnchorRef.current = { anchor: worldAnchor, poseAtSolve: anchorPose, createdAtMs: performance.now(), seenTracked: false };
    } catch (e) {
      (created as XRAnchor | null)?.delete();
      lastFailureAtRef.current = performance.now();
      const f = classifyArFailure(e, navigator.onLine);
      setFailure(f);
      if (!f.retryable && f.failure === "SESSION_EXPIRED") blockedRef.current = f;
      if (f.failure === "ANCHOR_UNAVAILABLE") {
        // The server named the anchor, or there was only one in the request: drop it for this session.
        const ids = f.anchorId ? [f.anchorId] : observations.length === 1 ? [observations[0].anchorId] : [];
        ids.forEach((id) => unavailableRef.current.add(id));
        markersRef.current = markersRef.current.filter((m) => !unavailableRef.current.has(m.anchorId));
      }
      dispatch({ type: "RELOCALIZATION_FAILED", requestId, error: `${f.failure}: ${message(e)}` });
      return;
    }
    setFailure(null);
    if (routeRef.current || !destinationId) {
      if (legRef.current?.floorId !== sessionFloor) updateLeg(); // a new floor after a handoff
      return;
    }
    // The route is fetched once, from where the device was when it was first localized. A failure here is a routing
    // error, not a localization one: the localization and its world anchor stay.
    await fetchRoute(viewerPose, deviceToVenue);
  }, [venueId, floorId, destinationId, dispatch, updateLeg, fetchRoute]);

  const onFrame = useCallback((time: number, frame: XRFrame) => {
    const renderer = rendererRef.current;
    const ref = renderer?.referenceSpace();
    if (!renderer || !ref) return;
    const viewer = frame.getViewerPose(ref);
    const signal = frameTrackingSignal(viewer ? { emulatedPosition: viewer.emulatedPosition } : null, frame.session.visibilityState);
    const frameMs = performance.now();
    const anchorPoseNow = (): Pose | null => {
      const wa = worldAnchorRef.current;
      if (!wa || !frame.trackedAnchors?.has(wa.anchor)) return null;
      const p = frame.getPose(wa.anchor.anchorSpace, ref);
      return p ? poseFromXrTransform(p.transform) : null;
    };
    if (signal.kind === "LIMITED" && (stateRef.current.state === "LOCALIZED" || stateRef.current.state === "LIMITED")) {
      // Hold the last valid overlay where it is (world-locked, not moved, not advanced) and ask the user to stand still.
      if (stateRef.current.state === "LOCALIZED") limitedAnchorRef.current = anchorPoseNow();
      dispatch({ type: "TRACKING_LIMITED", reason: signal.reason ?? "limited", atMs: frameMs });
      dispatch({ type: "TICK", atMs: frameMs });
      renderer.setRouteVisible(routeShown(stateRef.current) && legRef.current !== null);
      publish(false);
      return;
    }
    if (!signal.ok || !viewer) {
      dispatch({ type: "TRACKING_LOST", reason: signal.reason ?? "tracking lost" });
      renderer.setRouteVisible(false);
      return;
    }
    if (stateRef.current.state === "LIMITED") {
      // Validated recovery: the world anchor must be tracked again and barely moved while position was not tracked.
      const before = limitedAnchorRef.current;
      const after = anchorPoseNow();
      const jump = before && after ? Math.hypot(after.x - before.x, after.y - before.y, after.z - before.z) : null;
      dispatch({ type: "TRACKING_RESTORED", anchorJumpMeters: jump });
      limitedAnchorRef.current = null;
      if ((stateRef.current as ArSessionState).state !== "LOCALIZED") {
        renderer.setRouteVisible(false);
        return;
      }
    } else {
      dispatch({ type: "TRACKING_RESTORED" });
    }
    const viewerPose = poseFromXrTransform(viewer.transform);
    lastViewerRef.current = viewerPose;

    const results = (frame as ImageTrackingFrame).getImageTrackingResults?.() ?? [];
    const observed = observeImages(results, markersRef.current, (space) => {
      const pose = frame.getPose(space as XRSpace, ref);
      return pose ? poseFromXrTransform(pose.transform) : null;
    }, time);
    const sessionFloor = stateRef.current.floorId;
    const eligible = observed.filter((o) => o.floorId === sessionFloor && !unavailableRef.current.has(o.anchorId)
      && relocalizationEligibility(o).eligible);

    // The current transform (world-anchor corrected), if localized and the anchor is tracked.
    const s = stateRef.current;
    const worldAnchor = worldAnchorRef.current;
    let corrected: Pose | null = null;
    if (canAdvanceRoute(s) && s.localization && worldAnchor) {
      const anchorPose = frame.trackedAnchors?.has(worldAnchor.anchor) ? frame.getPose(worldAnchor.anchor.anchorSpace, ref) : null;
      if (!anchorPose) {
        // A new anchor may take a frame or two to appear in trackedAnchors. Until it is first seen the route stays hidden
        // and frozen. After that, or once the grace period is over, its absence is a tracking loss.
        if (worldAnchor.seenTracked || performance.now() - worldAnchor.createdAtMs >= WORLD_ANCHOR_GRACE_MS) {
          dispatch({ type: "TRACKING_LOST", reason: "the platform no longer tracks the world anchor the route is attached to" });
        }
        renderer.setRouteVisible(false);
        return;
      }
      worldAnchor.seenTracked = true;
      corrected = anchorCorrectedDeviceToVenue(s.localization.deviceToVenue, worldAnchor.poseAtSolve, poseFromXrTransform(anchorPose.transform));
    }

    const state = stateRef.current.state;
    const canObserve = performance.now() - lastFailureAtRef.current > RETRY_AFTER_FAILURE_MS && blockedRef.current === null;
    if (eligible.length > 0 && canObserve) {
      if (state === "LOCALIZED" && corrected) {
        // A registered marker seen again: is the transform still right?
        const worst = eligible.map((o) => {
          const digital = markersRef.current.find((m) => m.anchorId === o.anchorId)?.digitalPose;
          return digital ? markerDrift(corrected as Pose, o.observedPose, digital) : null;
        }).find((d) => d !== null && driftExceeded(d));
        if (worst) {
          dispatch({ type: "DRIFT_EXCEEDED", reason: `a registered marker is ${worst.translationMeters.toFixed(2)} m / ` +
            `${worst.rotationDegrees.toFixed(1)}° from where the transform puts it (drift)` });
          renderer.setRouteVisible(false);
          return;
        }
      }
      const due = state !== "LOCALIZED" || refreshDue(stateRef.current.localization, performance.now(), stateRef.current.pending !== null);
      if (due && (state === "SEARCHING" || state === "TRACKING_LOST" || state === "FLOOR_TRANSITION" || state === "LOCALIZED")) {
        const requestId = stateRef.current.nextRequestId;
        dispatch({ type: "MARKERS_OBSERVED", observations: eligible });
        const pending = stateRef.current.pending;
        if (pending && pending.requestId === requestId) { // this frame's observations opened a request
          // The world anchor must be created from this frame, at the pose the marker was measured at.
          const at = eligible[0].observedPose;
          const anchor = frame.createAnchor
            ? frame.createAnchor(new XRRigidTransform({ x: at.x, y: at.y, z: at.z }, { x: at.qx, y: at.qy, z: at.qz, w: at.qw }), ref)
            : Promise.reject(new Error(PROBLEM_TEXT.ANCHORS_UNSUPPORTED));
          void relocalizeFrom(pending.requestId, pending.observations, viewerPose, anchor, at);
        }
      }
    }

    const now = stateRef.current;
    const leg = legRef.current;
    if (!canAdvanceRoute(now) || !corrected || !leg || leg.floorId !== now.floorId) {
      renderer.setRouteVisible(false); // not localized on this floor: nothing drawn, progress frozen
      if (arrivalRef.current && routeRef.current) {
        // An arrival dwell never spans a tracking gap.
        arrivalRef.current = updateArrival(arrivalRef.current, routeRef.current.route, null, null, null, performance.now());
      }
      return;
    }
    const device = devicePositionInVenue(viewerPose, corrected);
    progressRef.current = advanceProgress(progressRef.current, true, leg.points, device);
    if (routeRef.current && arrivalRef.current) {
      const route = routeRef.current.route;
      positionRef.current = locateOnRoute(route, leg.floorId, device, positionRef.current);
      arrivalRef.current = updateArrival(arrivalRef.current, route, now.floorId, device, positionRef.current, performance.now());
      if (arrivalRef.current.arrived) publish(true);
      // Reroute only for a real observation from the device that touches the path still ahead (lib/obstacle-reroute.ts).
      const ahead = leg.points.slice(Math.max(0, (progressRef.current?.nextIndex ?? 1) - 1));
      const decision = rerouteDecision({
        floorId: leg.floorId, remainingPath: [device, ...ahead], routedAround: routedAroundRef.current,
        observed: obstacleSourceRef.current, nowMs: Date.now(), lastRerouteMs: lastRerouteAtRef.current,
      });
      if (decision.reroute && navigator.onLine) {
        lastRerouteAtRef.current = Date.now();
        void fetchRouteRef.current(viewerPose, corrected, decision.obstacles);
      }
    }
    const handoff = routeRef.current ? floorHandoff(routeRef.current.route, leg.floorId, progressRef.current?.remainingMeters ?? null) : null;
    if (handoff) {
      dispatch({ type: "FLOOR_HANDOFF", toFloorId: handoff.toFloorId });
      worldAnchorRef.current?.anchor.delete();
      worldAnchorRef.current = null;
      legRef.current = null;
      blendRef.current = null;
      lastDrawnRef.current = null;
      progressRef.current = null;
      renderer.setRouteVisible(false);
      return;
    }
    const drawn = blendedTransform(blendRef.current, corrected, performance.now());
    lastDrawnRef.current = drawn;
    renderer.setRoute(leg.points, progressRef.current?.nextIndex ?? 0);
    renderer.setVenueToWorld(venueToArWorld(drawn));
    renderer.setRouteVisible(true);
    publish(false);
  }, [dispatch, publish, relocalizeFrom]);
  useEffect(() => { onFrameRef.current = onFrame; }, [onFrame]);
  useEffect(() => { fetchRouteRef.current = fetchRoute; }, [fetchRoute]);

  async function startSession() {
    setError(null);
    setSessionProblem(null);
    const xr = navigator.xr;
    if (!xr || !loaded) return;
    let session: XRSession;
    try {
      session = await xr.requestSession("immersive-ar", arSessionInit(loaded.images, overlayRef.current) as XRSessionInit);
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
    legRef.current = null;
    blendRef.current = null;
    lastDrawnRef.current = null;
    progressRef.current = null;
    positionRef.current = null;
    arrivalRef.current = null;
    instructionsRef.current = [];
    routedAroundRef.current = [];
    lastRerouteAtRef.current = null;
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
    dispatch({ type: "SESSION_STARTED", floorId });
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
          {appleMobile && <p className="text-sm font-medium" data-testid="ar-ios-native">{IOS_NATIVE_TEXT}</p>}
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

          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={stepFree} onChange={(e) => setStepFree(e.target.checked)} disabled={inSession} />
            Step-free route (elevators and ramps with measured, passing clearances only)
          </label>
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
            Localized on floor {floors.find((f) => f.id === s.floorId)?.name ?? s.floorId} (scan version{" "}
            {s.localization.scanVersionId.slice(0, 8)}) from {s.localization.anchorIds.length} marker(s)
            {s.localization.residualMeters === null ? " (residual unknown: one marker)" : `, residual ${s.localization.residualMeters.toFixed(2)} m`}.
            {ui.progress && ` ${ui.progress.remainingMeters.toFixed(1)} m to go, ${ui.progress.offRouteMeters.toFixed(1)} m off route.`}
            {!ui.route && " Fetching route…"}
          </p>
        )}
        {s.state === "LIMITED" && (
          <p data-testid="ar-limited">
            Stop and hold still: tracking is limited ({s.limitedReason}). The route is frozen where it was and is not advancing. It
            resumes on its own only if tracking returns within 3 s without the map shifting; otherwise point at a marker.
          </p>
        )}
        {!online && <p className="text-amber-300" data-testid="ar-offline">NETWORK_UNAVAILABLE: offline. A route already shown stays usable while tracking holds.</p>}
        {failure && (
          <p className="text-amber-300" data-testid="ar-failure">
            {failure.failure}: {failure.action}
          </p>
        )}
        {ui.route && !ui.arrived && ui.next && (
          <p className="font-medium" data-testid="ar-next-instruction">
            {ui.next.text}
            {ui.remainingMeters !== null && ` · ${formatDistance(ui.remainingMeters)} to go`}
          </p>
        )}
        {ui.arrived && (
          <p className="font-medium text-emerald-300" data-testid="ar-arrived">
            You have arrived at {pois.find((p) => p.id === destinationId)?.label ?? "your destination"}.
          </p>
        )}
        {ui.route?.accessibilityProfile === "STEP_FREE" && <p>Step-free route: {ui.route.accessibilityConstraintsApplied.join("; ")}.</p>}
        {ui.route && ui.obstacles === "UNAVAILABLE" && (
          <p className="text-xs text-white/70" data-testid="ar-obstacles-unavailable">
            Obstacle detection is not available on this device: the route does not adapt to people or objects in the way.
          </p>
        )}
        {s.state === "TRACKING_LOST" && (
          <p>
            Stop. Tracking lost ({s.lostReason}). Route position frozen
            {ui.progress ? ` at ${ui.progress.remainingMeters.toFixed(1)} m to go` : ""}. Point the camera at a registered
            marker to relocalize.
          </p>
        )}
        {s.lastError && <p className="text-amber-300">Last relocalization failed: {s.lastError}</p>}
        {s.state === "FLOOR_TRANSITION" && (
          <p>
            Take the {ui.route?.floorTransitions.find((t) => t.toFloorId === s.floorId)?.connectorType.toLowerCase() ?? "connection"} to
            {" "}{floors.find((f) => f.id === s.floorId)?.name ?? s.floorId}, then point the camera at a registered marker there.
          </p>
        )}
        {ui.route && s.state === "LOCALIZED" && ui.route.floorTransitions.some((t) => t.fromFloorId === s.floorId) && (
          <p>The route continues on another floor via {ui.route.floorTransitions.find((t) => t.fromFloorId === s.floorId)?.connectorType}.</p>
        )}
        {inSession && ui.route && !ui.arrived && (
          <button
            className="mr-2 border px-3 py-1 rounded"
            onClick={() => {
              const localization = stateRef.current.localization;
              if (localization && lastViewerRef.current) void fetchRouteRef.current(lastViewerRef.current, localization.deviceToVenue);
            }}
          >
            Recalculate route
          </button>
        )}
        {inSession && <button className="border px-3 py-1 rounded" onClick={() => sessionRef.current?.end()}>End AR</button>}
      </div>
    </div>
  );
}
