"use client";

// The AR render layer: draws the route in the immersive-ar session with three.js (docs/ar.md, "Android: WebXR").
//
// three's WebXRManager binds the session: setSession() makes the WebGL context XR-compatible, creates the XRWebGLLayer,
// calls session.updateRenderState({ baseLayer }), and requests the "local" reference space. Without a base layer an
// immersive session delivers no animation frames, and that was missing before (review AR-1). The scene's units and axes
// are that reference space (the AR world frame: metres, +Y up). The camera is driven every frame by the platform's own
// viewer pose, so the route stays fixed in the room as the device moves.
//
// What is drawn is only the real route, and only while localized: a polyline through the route's waypoints on this
// floor, a sphere per waypoint, and the next one enlarged and highlighted. Nothing is drawn before the first localization, and
// the route is hidden while tracking is lost.
//
// The route geometry is built once, in canonical venue coordinates (+Z up). The group holding it is placed every frame
// with the canonical -> AR world pose (lib/ar-route.ts venueToArWorld, world-anchor corrected), which also turns +Z up
// into the scene's +Y up.

import * as THREE from "three";
import type { Pose } from "./ar-anchor-math";
import type { Vec3 } from "./coordinate-frame";

const ROUTE_COLOR = 0x1e88e5;
const DONE_COLOR = 0x9e9e9e;
const NEXT_COLOR = 0xffb300;

export class ArRouteRenderer {
  readonly renderer: THREE.WebGLRenderer;
  private readonly scene = new THREE.Scene();
  private readonly camera = new THREE.PerspectiveCamera();
  private readonly route = new THREE.Group();
  private pointsKey = "";

  constructor() {
    this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    this.renderer.setPixelRatio(window.devicePixelRatio);
    this.renderer.xr.enabled = true;
    this.renderer.xr.setReferenceSpaceType("local");
    this.scene.add(new THREE.HemisphereLight(0xffffff, 0x444444, 2));
    this.route.visible = false;
    this.scene.add(this.route);
  }

  /** Binds the immersive session (base layer + reference space). */
  async attach(session: XRSession): Promise<void> {
    await this.renderer.xr.setSession(session);
  }

  /** The AR world frame every pose must be read in: the same "local" space the scene is rendered in. */
  referenceSpace(): XRReferenceSpace | null {
    return this.renderer.xr.getReferenceSpace();
  }

  /** Runs every XR frame: `onFrame` first (tracking, observations, route update), then the render. */
  start(onFrame: (time: number, frame: XRFrame) => void): void {
    this.renderer.setAnimationLoop((time, frame) => {
      if (frame) onFrame(time, frame);
      this.renderer.render(this.scene, this.camera);
    });
  }

  /** The route in canonical venue coordinates, with `nextIndex` the next vertex ahead of the device. Geometry is rebuilt
   * only when the route changes. The highlight follows `nextIndex` every call. */
  setRoute(points: readonly Vec3[] | null, nextIndex: number): void {
    const key = points ? points.map((p) => p.map((c) => c.toFixed(3)).join(",")).join(";") : "";
    if (key !== this.pointsKey) {
      this.pointsKey = key;
      this.clearRoute();
      if (points && points.length > 0) this.buildRoute(points);
    }
    this.route.children.forEach((child) => {
      const index = child.userData.waypointIndex as number | undefined;
      if (index === undefined) return;
      const material = (child as THREE.Mesh).material as THREE.MeshStandardMaterial;
      material.color.setHex(index < nextIndex ? DONE_COLOR : index === nextIndex ? NEXT_COLOR : ROUTE_COLOR);
      child.scale.setScalar(index === nextIndex ? 1.8 : 1);
    });
  }

  /** Where the canonical venue frame is in the AR world (the scene) this frame. */
  setVenueToWorld(pose: Pose): void {
    this.route.position.set(pose.x, pose.y, pose.z);
    this.route.quaternion.set(pose.qx, pose.qy, pose.qz, pose.qw);
  }

  setRouteVisible(visible: boolean): void {
    this.route.visible = visible;
  }

  dispose(): void {
    this.renderer.setAnimationLoop(null);
    this.clearRoute();
    this.renderer.dispose();
  }

  private buildRoute(points: readonly Vec3[]): void {
    const vectors = points.map((p) => new THREE.Vector3(p[0], p[1], p[2]));
    if (vectors.length >= 2) {
      // A tube rather than a GL line: line width is 1 px on most mobile GPUs, invisible at walking distance.
      const path = new THREE.CurvePath<THREE.Vector3>();
      for (let i = 0; i + 1 < vectors.length; i++) path.add(new THREE.LineCurve3(vectors[i], vectors[i + 1]));
      const tube = new THREE.TubeGeometry(path, Math.max(8, vectors.length * 16), 0.03, 8, false);
      this.route.add(new THREE.Mesh(tube, new THREE.MeshStandardMaterial({ color: ROUTE_COLOR, transparent: true, opacity: 0.85 })));
    }
    vectors.forEach((v, i) => {
      const marker = new THREE.Mesh(new THREE.SphereGeometry(0.08, 16, 12), new THREE.MeshStandardMaterial({ color: ROUTE_COLOR }));
      marker.position.copy(v);
      marker.userData.waypointIndex = i;
      this.route.add(marker);
    });
  }

  private clearRoute(): void {
    for (const child of [...this.route.children]) {
      this.route.remove(child);
      const mesh = child as THREE.Mesh;
      mesh.geometry?.dispose();
      (mesh.material as THREE.Material | undefined)?.dispose();
    }
  }
}
