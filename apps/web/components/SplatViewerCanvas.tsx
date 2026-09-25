"use client";

import { useEffect, useRef } from "react";
import * as THREE from "three";
import * as GaussianSplats3D from "@mkkellogg/gaussian-splats-3d";
import { CSS2DObject, CSS2DRenderer } from "three/examples/jsm/renderers/CSS2DRenderer.js";
import type { Poi } from "@/lib/poi-api";
import type { Similarity } from "@/lib/coordinate-frame";
import { type DeviceProfile } from "@/lib/device-profile";

interface SplatViewerCanvasProps {
  /** An object URL for the already-downloaded .ksplat bytes (see lib/reconstruction-api.fetchArtifact). */
  blobUrl: string;
  /** The reconstruction's calibrated reconstruction -> canonical transform, or null when it has none. With it the
   * splat is placed in canonical metres (+Z up) and POIs/routes (canonical) are drawn on it; without it the splat is
   * shown in its own arbitrary frame and nothing canonical is overlaid. */
  toCanonical: Similarity | null;
  deviceProfile: DeviceProfile;
  pois: Poi[];
  selectedPoiId: string | null;
  onSelectPoi: (id: string | null) => void;
  /** A real route's waypoints (POST /api/v1/navigation/routes), already filtered to this floor, in order.
   * null/empty draws nothing -- there is no synthetic fallback line when a real route is unavailable. */
  routeWaypoints: { x: number; y: number; z: number }[] | null;
  onProgress: (percent: number) => void;
  onLoaded: (splatCount: number) => void;
  onError: (message: string) => void;
}

/** The Three.js / GaussianSplats3D render surface. Everything about camera controls, the splat scene and
 * the POI/route overlay lives here; the surrounding page only owns app-level state (which reconstruction,
 * which POI is selected). Mounted fresh per reconstruction (keyed by blobUrl in the parent) so there is
 * never a stale viewer instance to reconcile. */
export default function SplatViewerCanvas({
  blobUrl,
  toCanonical,
  deviceProfile,
  pois,
  selectedPoiId,
  onSelectPoi,
  routeWaypoints,
  onProgress,
  onLoaded,
  onError,
}: SplatViewerCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const overlaySceneRef = useRef<THREE.Scene | null>(null);
  const threeSceneRef = useRef<THREE.Scene | null>(null);
  const routeLineRef = useRef<THREE.Line | null>(null);
  const markerElsRef = useRef<Map<string, HTMLDivElement>>(new Map());
  const selectedPoiIdRef = useRef(selectedPoiId);
  useEffect(() => {
    selectedPoiIdRef.current = selectedPoiId;
  }, [selectedPoiId]);

  // ---- one Viewer per blobUrl -------------------------------------------------------------------
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    let disposed = false;
    let frameHandle = 0;
    let viewer: InstanceType<typeof GaussianSplats3D.Viewer> | null = null;
    let renderer: THREE.WebGLRenderer | null = null;
    let cssRenderer: CSS2DRenderer | null = null;
    const threeScene = new THREE.Scene();
    threeSceneRef.current = threeScene;
    const markerEls = markerElsRef.current;

    function resize() {
      if (!container || !renderer || !cssRenderer) return;
      const { clientWidth: w, clientHeight: h } = container;
      if (w === 0 || h === 0) return;
      renderer.setSize(w, h);
      cssRenderer.setSize(w, h);
      const cam = camera;
      cam.aspect = w / h;
      cam.updateProjectionMatrix();
    }

    let camera: THREE.PerspectiveCamera;
    try {
      camera = new THREE.PerspectiveCamera(60, container.clientWidth / Math.max(1, container.clientHeight), 0.05, 1000);
      if (toCanonical) {
        // Canonical frame: metres, +Z up. Start at eye height a few metres from the floor origin.
        camera.up.set(0, 0, 1);
        camera.position.set(0, -4, 1.7);
        camera.lookAt(0, 0, 1);
      } else {
        // Uncalibrated: the reconstruction frame has no known up. This is a viewing default only (COLMAP's cameras look
        // along +Z with image-down +Y); nothing measured or canonical is derived from it or drawn on it.
        camera.position.set(0, -2, 4);
        camera.up.set(0, -1, -0.6);
      }

      renderer = new THREE.WebGLRenderer({ antialias: false });
      renderer.setPixelRatio(deviceProfile.ignoreDevicePixelRatio ? 1 : window.devicePixelRatio);
      container.appendChild(renderer.domElement);

      cssRenderer = new CSS2DRenderer();
      cssRenderer.domElement.style.position = "absolute";
      cssRenderer.domElement.style.top = "0";
      cssRenderer.domElement.style.left = "0";
      cssRenderer.domElement.style.pointerEvents = "none";
      container.appendChild(cssRenderer.domElement);

      const overlayScene = new THREE.Scene();
      overlaySceneRef.current = overlayScene;

      resize();

      viewer = new GaussianSplats3D.Viewer({
        selfDrivenMode: false,
        renderer,
        camera,
        threeScene,
        useBuiltInControls: true,
        ignoreDevicePixelRatio: deviceProfile.ignoreDevicePixelRatio,
        gpuAcceleratedSort: deviceProfile.gpuAcceleratedSort,
        sharedMemoryForWorkers: deviceProfile.sharedMemoryForWorkers,
        halfPrecisionCovariancesOnGPU: deviceProfile.halfPrecisionCovariancesOnGPU,
        sphericalHarmonicsDegree: deviceProfile.sphericalHarmonicsDegree,
        logLevel: GaussianSplats3D.LogLevel.None,
      });
    } catch (e) {
      onError(e instanceof Error ? e.message : "This device could not create a WebGL context.");
      return;
    }

    const ro = new ResizeObserver(resize);
    ro.observe(container);

    viewer
      .addSplatScene(blobUrl, {
        format: GaussianSplats3D.SceneFormat.KSplat,
        showLoadingUI: false,
        // Places the reconstruction-frame splat in canonical metres: X_c = s R X_r + t (lib/coordinate-frame.ts).
        ...(toCanonical ? {
          position: toCanonical.translation,
          rotation: [toCanonical.rotation.x, toCanonical.rotation.y, toCanonical.rotation.z, toCanonical.rotation.w],
          scale: [toCanonical.scale, toCanonical.scale, toCanonical.scale],
        } : {}),
        splatAlphaRemovalThreshold: deviceProfile.splatAlphaRemovalThreshold,
        onProgress: (percent: number) => {
          if (!disposed) onProgress(Math.round(percent));
        },
      })
      .then(() => {
        if (disposed || !viewer) return;
        onLoaded(viewer.getSplatMesh().getSplatCount()); // 0.4.7: the count lives on the SplatMesh, not the Viewer
        const loop = () => {
          if (disposed || !viewer || !cssRenderer) return;
          viewer.update();
          viewer.render();
          cssRenderer.render(overlaySceneRef.current ?? new THREE.Scene(), camera);
          frameHandle = requestAnimationFrame(loop);
        };
        loop();
      })
      .catch((e: unknown) => {
        if (!disposed) onError(e instanceof Error ? e.message : "The reconstruction could not be loaded.");
      });

    return () => {
      disposed = true;
      cancelAnimationFrame(frameHandle);
      ro.disconnect();
      markerEls.clear();
      overlaySceneRef.current = null;
      threeSceneRef.current = null;
      routeLineRef.current = null;
      viewer?.dispose();
      if (renderer) container.removeChild(renderer.domElement);
      if (cssRenderer) container.removeChild(cssRenderer.domElement);
      renderer?.dispose();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- deliberately re-runs only when the scene itself changes
  }, [blobUrl]);

  // ---- POI markers: rebuilt when the POI list changes, restyled when the selection changes ----------
  useEffect(() => {
    const scene = overlaySceneRef.current;
    if (!scene) return;
    markerElsRef.current.forEach((el) => el.remove());
    markerElsRef.current.clear();
    scene.clear();
    if (!toCanonical) return; // POIs are canonical; they have no place on an uncalibrated splat
    for (const poi of pois) {
      const el = document.createElement("div");
      el.className = "chaya-poi-marker";
      el.style.pointerEvents = "auto";
      el.style.cursor = "pointer";
      el.style.width = "14px";
      el.style.height = "14px";
      el.style.borderRadius = "9999px";
      el.style.border = "2px solid white";
      el.style.boxShadow = "0 0 0 1px rgba(0,0,0,0.4)";
      el.style.background = poi.id === selectedPoiIdRef.current ? "#f59e0b" : "#2563eb";
      el.title = poi.label;
      el.addEventListener("click", (e) => {
        e.stopPropagation();
        onSelectPoi(poi.id === selectedPoiIdRef.current ? null : poi.id);
      });
      const marker = new CSS2DObject(el);
      marker.position.set(poi.x, poi.y, poi.z);
      scene.add(marker);
      markerElsRef.current.set(poi.id, el);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pois, blobUrl]);

  // Restyle on selection change without rebuilding the markers.
  useEffect(() => {
    markerElsRef.current.forEach((el, id) => {
      el.style.background = id === selectedPoiId ? "#f59e0b" : "#2563eb";
      el.style.width = id === selectedPoiId ? "18px" : "14px";
      el.style.height = id === selectedPoiId ? "18px" : "14px";
    });
  }, [selectedPoiId]);

  // Navigation route overlay: the real waypoint sequence from POST /api/v1/navigation/routes (real
  // Dijkstra over the baked navmesh graph -- see docs/navigation.md). Nothing is drawn when a route
  // could not be found; ViewerWorkspace never fabricates a straight-line substitute.
  useEffect(() => {
    const scene = threeSceneRef.current;
    if (!scene) return;
    if (routeLineRef.current) {
      scene.remove(routeLineRef.current);
      routeLineRef.current.geometry.dispose();
      (routeLineRef.current.material as THREE.Material).dispose();
      routeLineRef.current = null;
    }
    if (!toCanonical || !routeWaypoints || routeWaypoints.length < 2) return;
    const geometry = new THREE.BufferGeometry().setFromPoints(routeWaypoints.map((w) => new THREE.Vector3(w.x, w.y, w.z)));
    const material = new THREE.LineDashedMaterial({ color: 0xf59e0b, dashSize: 0.15, gapSize: 0.1, linewidth: 2 });
    const line = new THREE.Line(geometry, material);
    line.computeLineDistances();
    scene.add(line);
    routeLineRef.current = line;
  }, [routeWaypoints, blobUrl, toCanonical]);

  return (
    <div
      ref={containerRef}
      className="relative h-full w-full overflow-hidden bg-zinc-950"
      onClick={() => onSelectPoi(null)}
      data-testid="splat-canvas"
    />
  );
}
