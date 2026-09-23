"use client";

// Android AR client: WebXR inside this Next.js app (see docs/ar.md "Android: WebXR"). Renders an explicit
// unsupported-device state when navigator.xr isn't there or immersive-ar isn't supported -- this file
// never falls back to a fake/simulated AR view. The actual immersive session (real WebXR hit-test against
// the platform's own plane/marker detection) only runs on a WebXR-capable device; it cannot be exercised
// or screenshotted from this development machine, which is exactly the unsupported-device branch below.

import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { ApiError, type Floor, type Venue, listFloors, listVenues } from "@/lib/capture-api";
import { type Anchor, type AnchorObservation, listAnchors, relocalize } from "@/lib/ar-api";
import { detectWebXrSupport, type WebXrSupport } from "@/lib/webxr-support";
import { initialArSessionState, reduceArSession, type ArSessionState } from "@/lib/ar-relocalization";
import type { Pose } from "@/lib/ar-anchor-math";

// Minimal ambient shapes for the WebXR APIs this file touches. There is no shipped "dom.xr" lib target in
// this project's tsconfig (see tsconfig.json), and pulling in a third-party WebXR type package for three
// call sites isn't worth the dependency -- these are deliberately narrow, not a general WebXR typing.
interface XrHitTestResultLike {
  getPose(referenceSpace: unknown): { transform: { position: { x: number; y: number; z: number }; orientation: { x: number; y: number; z: number; w: number } } } | null;
}
interface XrFrameLike {
  getHitTestResults(source: unknown): XrHitTestResultLike[];
}
interface XrSessionLike {
  requestReferenceSpace(type: string): Promise<unknown>;
  requestHitTestSource?(options: { space: unknown }): Promise<unknown>;
  requestAnimationFrame(callback: (time: number, frame: XrFrameLike) => void): number;
  addEventListener(type: string, listener: () => void): void;
  end(): Promise<void>;
}
interface XrSystemLike {
  requestSession(mode: string, options?: Record<string, unknown>): Promise<XrSessionLike>;
}

function message(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}

function poseFromXr(position: { x: number; y: number; z: number }, orientation: { x: number; y: number; z: number; w: number }): Pose {
  return { x: position.x, y: position.y, z: position.z, qx: orientation.x, qy: orientation.y, qz: orientation.z, qw: orientation.w };
}

export default function ArWorkspace() {
  const searchParams = useSearchParams();
  const prefillVenueId = searchParams.get("venue");
  const prefillFloorId = searchParams.get("floor");

  const [venues, setVenues] = useState<Venue[]>([]);
  const [venueId, setVenueId] = useState(prefillVenueId ?? "");
  const [floors, setFloors] = useState<Floor[]>([]);
  const [floorId, setFloorId] = useState(prefillFloorId ?? "");
  const [anchors, setAnchors] = useState<Anchor[]>([]);
  const [error, setError] = useState<string | null>(null);

  const [xrSupport, setXrSupport] = useState<WebXrSupport | null>(null);
  const [session, setSession] = useState<ArSessionState>(initialArSessionState());
  const [relocalizationBusy, setRelocalizationBusy] = useState(false);
  const xrSessionRef = useRef<XrSessionLike | null>(null);

  useEffect(() => {
    detectWebXrSupport().then(setXrSupport);
  }, []);

  useEffect(() => {
    listVenues().then(setVenues).catch((e) => setError(message(e)));
  }, []);

  useEffect(() => {
    if (!venueId) return;
    listFloors(venueId).then(setFloors).catch((e) => setError(message(e)));
  }, [venueId]);

  useEffect(() => {
    if (!venueId || !floorId) return;
    listAnchors(venueId, floorId).then(setAnchors).catch((e) => setError(message(e)));
  }, [venueId, floorId]);

  useEffect(() => {
    return () => {
      xrSessionRef.current?.end().catch(() => {});
    };
  }, []);

  const calibratedAnchors = useMemo(() => anchors.filter((a) => a.calibrationStatus === "CALIBRATED"), [anchors]);

  async function startSession() {
    const xr = (globalThis as { navigator?: { xr?: XrSystemLike } }).navigator?.xr;
    if (!xr) return;
    setSession(reduceArSession(session, { type: "START_DETECTING" }));
    try {
      const xrSession = await xr.requestSession("immersive-ar", { requiredFeatures: ["hit-test"] });
      xrSessionRef.current = xrSession;
      xrSession.addEventListener("end", () => {
        xrSessionRef.current = null;
        setSession((s) => reduceArSession(s, { type: "TRACKING_LOST" }));
      });
      const viewerSpace = await xrSession.requestReferenceSpace("viewer");
      const hitTestSource = await xrSession.requestHitTestSource?.({ space: viewerSpace });
      const localSpace = await xrSession.requestReferenceSpace("local");

      const onFrame = (_time: number, frame: XrFrameLike) => {
        if (hitTestSource) {
          const results = frame.getHitTestResults(hitTestSource);
          if (results.length > 0) {
            const pose = results[0].getPose(localSpace);
            if (pose) {
              setSession((s) => reduceArSession(s, { type: "ANCHOR_DETECTED", pose: poseFromXr(pose.transform.position, pose.transform.orientation) }));
            }
          }
        }
        xrSessionRef.current?.requestAnimationFrame(onFrame);
      };
      xrSession.requestAnimationFrame(onFrame);
    } catch (e) {
      setError(message(e));
      setSession((s) => reduceArSession(s, { type: "TRACKING_LOST" }));
    }
  }

  async function attemptRelocalization() {
    if (!venueId || !floorId || calibratedAnchors.length === 0 || !session.lastKnownPose) return;
    setSession((s) => reduceArSession(s, { type: "BEGIN_RELOCALIZING" }));
    setRelocalizationBusy(true);
    try {
      const observations: AnchorObservation[] = [
        { anchorId: calibratedAnchors[0].id, observedPose: session.lastKnownPose as Pose },
      ];
      const result = await relocalize(venueId, floorId, observations);
      setSession((s) => reduceArSession(s, { type: "RELOCALIZED", pose: result.deviceToVenueTransform }));
    } catch (e) {
      setError(message(e));
      setSession((s) => reduceArSession(s, { type: "TRACKING_LOST" }));
    } finally {
      setRelocalizationBusy(false);
    }
  }

  if (xrSupport === null) {
    return <p className="p-8">Checking WebXR support…</p>;
  }

  if (!xrSupport.supported) {
    return (
      <div className="p-8 max-w-xl space-y-3">
        <h1 className="text-xl font-semibold">AR is not available on this device</h1>
        <p className="text-sm text-neutral-600">{xrSupport.detail}</p>
        <p className="text-sm text-neutral-600">
          Open this page on an AR-capable Android device with Chrome to try the WebXR experience.
        </p>
      </div>
    );
  }

  return (
    <div className="p-8 space-y-4 max-w-xl">
      <h1 className="text-xl font-semibold">AR navigation</h1>
      {error && <p className="text-sm text-red-600">{error}</p>}

      <div className="flex gap-3">
        <select className="border p-2" value={venueId} onChange={(e) => { setVenueId(e.target.value); setFloorId(""); setFloors([]); setAnchors([]); }}>
          <option value="">Select venue…</option>
          {venues.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
        </select>
        <select className="border p-2" value={floorId} onChange={(e) => { setFloorId(e.target.value); setAnchors([]); }} disabled={!venueId}>
          <option value="">Select floor…</option>
          {floors.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
        </select>
      </div>

      <p className="text-sm text-neutral-600">{anchors.length} anchor(s) registered, {calibratedAnchors.length} calibrated.</p>

      <button
        className="border px-4 py-2 rounded disabled:opacity-50"
        disabled={!venueId || !floorId}
        onClick={startSession}
      >
        Start AR session
      </button>

      <div className="border p-4 rounded space-y-2">
        <p className="font-medium">Session state: {session.state}</p>
        {session.state === "TRACKING_LOST" && (
          <div className="space-y-2">
            <p className="text-amber-700">Tracking lost. Your route is preserved, but your position on it is frozen until you re-localize.</p>
            <button className="border px-3 py-1 rounded disabled:opacity-50" disabled={relocalizationBusy || calibratedAnchors.length === 0} onClick={attemptRelocalization}>
              Point the camera at a known anchor to re-localize
            </button>
          </div>
        )}
        {session.state === "RELOCALIZING" && <p>Re-localizing…</p>}
        {session.state === "LOCALIZED" && <p className="text-green-700">Localized.</p>}
      </div>
    </div>
  );
}
