"use client";

import { type Ref, useEffect, useImperativeHandle, useRef } from "react";
import * as THREE from "three";
import * as GaussianSplats3D from "@mkkellogg/gaussian-splats-3d";
import { CSS2DObject, CSS2DRenderer } from "three/examples/jsm/renderers/CSS2DRenderer.js";
import type { Poi } from "@/lib/poi-api";
import type { Similarity } from "@/lib/coordinate-frame";
import { type DeviceProfile } from "@/lib/device-profile";
import { blendView, focusView, initialView, sampleIndices, type Vec3 } from "@/lib/viewer-camera";
import { type FrameQualityState, initialFrameQuality, recordFrame } from "@/lib/frame-quality";
import { GraphicsContextError } from "@/lib/viewer-errors";
import { viewerResources } from "@/lib/viewer-resources";

/** Camera commands the surrounding page can issue once the scene has loaded. */
export interface SplatViewerHandle {
  /** Back to the view fitted to the loaded splats. */
  resetView(): void;
  /** Moves the camera to show this POI. false: it is not drawn on this reconstruction (or nothing has loaded yet). */
  focusPoi(id: string): boolean;
  /** factor < 1 moves closer to the orbit target, > 1 further away. */
  zoom(factor: number): void;
}

interface SplatViewerCanvasProps {
  ref?: Ref<SplatViewerHandle>;
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
  /** A real route's stretches on this floor (POST /api/v1/navigation/routes; lib/viewer-route runsOnFloor), each in
   * order. null/empty draws nothing -- there is no synthetic fallback line when a real route is unavailable. A STOP or
   * DESTINATION waypoint gets an end marker. */
  routeRuns: { x: number; y: number; z: number; kind?: string }[][] | null;
  /** The POIs the route visits: highlighted, with their labels always shown. */
  destinationPoiIds?: string[];
  onProgress: (percent: number) => void;
  /** Called once the splats have actually been drawn (the first frame after the library's first sort), never merely
   * when the file has been parsed. */
  onLoaded: (splatCount: number) => void;
  onError: (error: Error) => void;
  /** The measured render quality, after each measurement window (lib/frame-quality). */
  onQuality?: (quality: { pixelRatio: number; maxPixelRatio: number; medianMs: number | null }) => void;
}

type View = { position: Vec3; target: Vec3 };
type Controls = NonNullable<InstanceType<typeof GaussianSplats3D.Viewer>["controls"]>;

const MARKER_COLOUR = "#2563eb";
const SELECTED_COLOUR = "#f59e0b";
const DESTINATION_COLOUR = "#16a34a";
/** A pointer that moved further than this between down and up was a drag (orbit/pan), not a click. */
const CLICK_SLOP_PX = 5;

function styleMarker(dot: HTMLElement, label: HTMLElement, selected: boolean, hovered: boolean, destination = false) {
  dot.style.background = selected ? SELECTED_COLOUR : destination ? DESTINATION_COLOUR : MARKER_COLOUR;
  dot.style.width = dot.style.height = selected || destination ? "18px" : "14px";
  dot.dataset.destination = destination ? "true" : "false";
  label.style.display = selected || hovered || destination ? "block" : "none";
  label.style.fontWeight = selected || destination ? "600" : "400";
}

/** The Three.js / GaussianSplats3D render surface. Everything about camera controls, the splat scene and
 * the POI/route overlay lives here; the surrounding page only owns app-level state (which reconstruction,
 * which POI is selected). Mounted fresh per reconstruction (keyed by blobUrl in the parent) so there is
 * never a stale viewer instance to reconcile, and everything a scene allocated is released when it unmounts. */
export default function SplatViewerCanvas({
  ref,
  blobUrl,
  toCanonical,
  deviceProfile,
  pois,
  selectedPoiId,
  onSelectPoi,
  routeRuns,
  destinationPoiIds,
  onProgress,
  onLoaded,
  onError,
  onQuality,
}: SplatViewerCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const overlaySceneRef = useRef<THREE.Scene | null>(null);
  // The route has its own scene, drawn after the splats: in the viewer's threeScene it is drawn first and the splats
  // (blended, without depth writes) are composited over it, so a route along the floor vanished under the floor's splats.
  const routeSceneRef = useRef<THREE.Scene | null>(null);
  const routeMeshRef = useRef<THREE.Group | null>(null);
  const destinationIdsRef = useRef<string[]>(destinationPoiIds ?? []);
  const markerElsRef = useRef<Map<string, { dot: HTMLDivElement; label: HTMLDivElement }>>(new Map());
  const selectedPoiIdRef = useRef(selectedPoiId);
  const poisRef = useRef(pois);
  // Set once the scene has loaded: the camera, its controls, the fitted view and the transition the render loop plays.
  const cameraRef = useRef<{ camera: THREE.PerspectiveCamera; controls: Controls; home: View; animate: (to: View) => void } | null>(null);
  const pointerDownRef = useRef<{ x: number; y: number } | null>(null);
  useEffect(() => {
    selectedPoiIdRef.current = selectedPoiId;
  }, [selectedPoiId]);
  useEffect(() => {
    poisRef.current = pois;
  }, [pois]);

  useImperativeHandle(ref, () => ({
    resetView() {
      const c = cameraRef.current;
      if (c) c.animate(c.home);
    },
    focusPoi(id) {
      const c = cameraRef.current;
      const poi = toCanonical ? poisRef.current.find((p) => p.id === id) : undefined;
      if (!c || !poi) return false;
      const from = c.camera.position;
      const t = c.controls.target;
      c.animate(focusView([from.x, from.y, from.z], [t.x, t.y, t.z], [poi.x, poi.y, poi.z]));
      return true;
    },
    zoom(factor) {
      const c = cameraRef.current;
      if (!c) return;
      const t = c.controls.target;
      const p = c.camera.position;
      const target: Vec3 = [t.x, t.y, t.z];
      const position: Vec3 = [t.x + (p.x - t.x) * factor, t.y + (p.y - t.y) * factor, t.z + (p.z - t.z) * factor];
      c.animate({ position, target });
    },
  }), [toCanonical]);

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
    const routeScene = new THREE.Scene();
    routeSceneRef.current = routeScene;
    const markerEls = markerElsRef.current;
    let transition: { from: View; to: View; start: number; ms: number } | null = null;
    const cancelTransition = () => {
      transition = null;
    };
    // Render scale from measured frame times, starting from what the device profile allows (lib/frame-quality).
    let quality: FrameQualityState = initialFrameQuality(deviceProfile.ignoreDevicePixelRatio ? 1 : window.devicePixelRatio);
    // The browser may take the GPU context away (driver reset, too many contexts, GPU process crash). Everything on the
    // GPU is gone then: stop drawing and say so; the page offers to rebuild the viewer from the bytes it already has.
    function onContextLost(e: Event) {
      e.preventDefault();
      if (disposed) return;
      cancelAnimationFrame(frameHandle);
      frameHandle = 0;
      lost = true;
      onError(new GraphicsContextError("The graphics context was lost (for example after a GPU reset). The model can be shown again."));
    }
    let lost = false;

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
      viewerResources.renderers++;
      renderer.setPixelRatio(quality.pixelRatio);
      container.appendChild(renderer.domElement);
      renderer.domElement.addEventListener("webglcontextlost", onContextLost);
      // A user gesture takes over from a camera transition instead of fighting it.
      renderer.domElement.addEventListener("pointerdown", cancelTransition);
      renderer.domElement.addEventListener("wheel", cancelTransition, { passive: true });

      cssRenderer = new CSS2DRenderer();
      cssRenderer.domElement.style.position = "absolute";
      cssRenderer.domElement.style.top = "0";
      cssRenderer.domElement.style.left = "0";
      cssRenderer.domElement.style.pointerEvents = "none";
      container.appendChild(cssRenderer.domElement);

      const overlayScene = new THREE.Scene();
      overlaySceneRef.current = overlayScene;

      resize();

      if (typeof window !== "undefined" && !window.WebGLRenderingContext) {
        throw new GraphicsContextError(
          "WebGL is not supported or hardware acceleration is disabled in this browser. Please enable hardware acceleration in your browser settings (chrome://settings/system) or use an updated browser.",
          true,
        );
      }

      // Without antialias and with failIfMajorPerformanceCaveat unset, the only way this fails is no WebGL at all.
      if (!renderer.getContext()) {
        throw new GraphicsContextError("This device could not create a WebGL context.", true);
      }

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
        // The default reveal fades the scene in from its centre over many frames, so the frame reported as loaded showed
        // almost nothing. The whole verified model is shown at once instead.
        sceneRevealMode: GaussianSplats3D.SceneRevealMode.Instant,
      });
      viewerResources.viewers++;
      // The library binds debug shortcuts (point-cloud mode, splat scale, focal length, camera roll, an info panel) and
      // its orbit controls' pan keys to the whole window, so typing in the search box changed the scene. Neither belongs
      // in this product: the debug keys are dropped, and the controls listen only while the scene has keyboard focus,
      // with the arrow keys (Shift+arrows orbit).
      if (viewer.keyDownListener) window.removeEventListener("keydown", viewer.keyDownListener);
      const keyControls = viewer.controls;
      if (keyControls) {
        keyControls.stopListenToKeyEvents();
        keyControls.keys = { LEFT: "ArrowLeft", UP: "ArrowUp", RIGHT: "ArrowRight", BOTTOM: "ArrowDown" };
        keyControls.listenToKeyEvents(container);
      }
    } catch (e) {
      onError(e instanceof GraphicsContextError ? e : new GraphicsContextError(
        `This device could not create a WebGL context${e instanceof Error ? ` (${e.message})` : ""}.`, true));
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
        const mesh = viewer.getSplatMesh();
        const count = mesh.getSplatCount();
        if (count === 0) {
          onError(new Error("The reconstruction asset loaded but contains no splats."));
          return;
        }
        // Frame what was actually loaded (lib/viewer-camera.ts): centres are read after the scene transform, so in a
        // canonical frame they are venue metres wherever the datum puts them.
        const c = new THREE.Vector3();
        const view = initialView(
          sampleIndices(count).map((i) => {
            mesh.getSplatCenter(i, c, true);
            return [c.x, c.y, c.z] as Vec3;
          }),
          !!toCanonical,
        );
        const controls = viewer.controls;
        if (view) {
          camera.up.set(...view.up);
          camera.position.set(...view.position);
          camera.far = Math.max(1000, view.extent * 50);
          camera.updateProjectionMatrix();
          camera.lookAt(...view.target);
          if (controls) {
            controls.target.set(...view.target);
            // Zooming stops short of the target and well before the scene shrinks to a dot.
            controls.minDistance = Math.max(0.05, view.extent * 0.01);
            controls.maxDistance = Math.max(10, view.extent * 8);
            controls.update();
          }
        }
        if (controls) {
          const home: View = view
            ? { position: view.position, target: view.target }
            : { position: camera.position.toArray() as Vec3, target: controls.target.toArray() as Vec3 };
          cameraRef.current = {
            camera,
            controls,
            home,
            animate: (to) => {
              const p = camera.position;
              const t = controls.target;
              transition = { from: { position: [p.x, p.y, p.z], target: [t.x, t.y, t.z] }, to, start: performance.now(), ms: 650 };
            },
          };
        }
        let reported = false;
        let lastFrame = 0;
        const loop = (now: number) => {
          if (disposed || lost || !viewer || !cssRenderer || !renderer) return;
          if (transition && controls) {
            const k = (performance.now() - transition.start) / transition.ms;
            const v = blendView(transition.from, transition.to, k);
            camera.position.set(...v.position);
            controls.target.set(...v.target);
            if (k >= 1) transition = null;
          }
          viewer.update(); // updates the (damped) orbit controls too
          viewer.render();
          if (routeScene.children.length > 0 && renderer) {
            const autoClear = renderer.autoClear;
            renderer.autoClear = false;
            renderer.render(routeScene, camera);
            renderer.autoClear = autoClear;
          }
          if (overlaySceneRef.current) cssRenderer.render(overlaySceneRef.current, camera);
          // viewer.render() draws nothing until the first sort has finished (splatRenderReady). Only a frame in which
          // the splats were actually drawn counts as loaded. 0.4.7: the count lives on the SplatMesh, not the Viewer.
          if (!reported && viewer.splatRenderReady) {
            reported = true;
            onLoaded(count);
          }
          if (reported && lastFrame > 0) {
            const next = recordFrame(quality, now - lastFrame);
            if (next.medianMs !== quality.medianMs || next.pixelRatio !== quality.pixelRatio) {
              if (next.pixelRatio !== quality.pixelRatio) {
                renderer.setPixelRatio(next.pixelRatio);
                resize();
              }
              onQuality?.({ pixelRatio: next.pixelRatio, maxPixelRatio: next.maxPixelRatio, medianMs: next.medianMs });
            }
            quality = next;
          }
          lastFrame = now;
          frameHandle = requestAnimationFrame(loop);
        };
        frameHandle = requestAnimationFrame(loop);
      })
      .catch((e: unknown) => {
        if (!disposed) onError(e instanceof Error ? e : new Error("The reconstruction could not be loaded."));
      });

    return () => {
      disposed = true;
      transition = null;
      cancelAnimationFrame(frameHandle);
      ro.disconnect();
      cameraRef.current = null;
      markerEls.clear();
      overlaySceneRef.current?.clear();
      overlaySceneRef.current = null;
      routeSceneRef.current = null;
      if (routeMeshRef.current) {
        disposeGroup(routeMeshRef.current);
        routeMeshRef.current = null;
      }
      if (renderer) {
        renderer.domElement.removeEventListener("pointerdown", cancelTransition);
        renderer.domElement.removeEventListener("wheel", cancelTransition);
        renderer.domElement.removeEventListener("webglcontextlost", onContextLost);
        container.removeChild(renderer.domElement);
      }
      if (cssRenderer) container.removeChild(cssRenderer.domElement);
      // The viewer does not own an external renderer. It first waits for an in-flight load or sort, then releases the
      // splat mesh's GPU buffers and textures, its sort worker and its controls; only then is the renderer released and
      // its WebGL context given back, so switching venues/floors/versions repeatedly never runs out of contexts.
      const r = renderer;
      const v = viewer;
      Promise.resolve(v?.dispose())
        .catch(() => {})
        .finally(() => {
          if (v) viewerResources.viewers--;
          if (r) {
            r.dispose();
            r.forceContextLoss();
            viewerResources.renderers--;
          }
        });
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- deliberately re-runs only when the scene itself changes
  }, [blobUrl]);

  // ---- POI markers: rebuilt when the POI list changes, restyled when the selection changes ----------
  useEffect(() => {
    const scene = overlaySceneRef.current;
    if (!scene) return;
    markerElsRef.current.clear();
    scene.clear(); // CSS2DObject removes its element from the DOM when it leaves the scene
    if (!toCanonical) return; // POIs are canonical; they have no place on an uncalibrated splat
    for (const poi of pois) {
      // The wrapper is what CSS2DRenderer centres on the POI; the dot is its only in-flow child, so the dot sits exactly
      // on the POI and the label beside it never shifts it.
      const wrapper = document.createElement("div");
      wrapper.style.position = "relative";
      const dot = document.createElement("div");
      dot.className = "chaya-poi-marker";
      dot.style.pointerEvents = "auto";
      dot.style.cursor = "pointer";
      dot.style.borderRadius = "9999px";
      dot.style.border = "2px solid white";
      dot.style.boxShadow = "0 0 0 1px rgba(0,0,0,0.4)";
      dot.title = poi.label;
      dot.setAttribute("role", "button");
      dot.setAttribute("aria-label", poi.label);
      const label = document.createElement("div");
      label.className = "chaya-poi-label";
      label.textContent = poi.label;
      Object.assign(label.style, {
        position: "absolute", left: "100%", top: "50%", transform: "translate(6px, -50%)", whiteSpace: "nowrap",
        padding: "1px 6px", borderRadius: "4px", background: "rgba(24,24,27,0.85)", color: "white", fontSize: "12px",
        pointerEvents: "none",
      });
      wrapper.append(dot, label);
      const selected = () => poi.id === selectedPoiIdRef.current;
      const destination = () => destinationIdsRef.current.includes(poi.id);
      styleMarker(dot, label, selected(), false, destination());
      dot.addEventListener("mouseenter", () => styleMarker(dot, label, selected(), true, destination()));
      dot.addEventListener("mouseleave", () => styleMarker(dot, label, selected(), false, destination()));
      dot.addEventListener("pointerdown", (e) => e.stopPropagation()); // not the start of an orbit or a background click
      dot.addEventListener("click", (e) => {
        e.stopPropagation();
        onSelectPoi(selected() ? null : poi.id);
      });
      const marker = new CSS2DObject(wrapper);
      marker.position.set(poi.x, poi.y, poi.z);
      scene.add(marker);
      markerElsRef.current.set(poi.id, { dot, label });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pois, blobUrl]);

  // Restyle on selection or destination change without rebuilding the markers.
  const destinationKey = (destinationPoiIds ?? []).join(",");
  useEffect(() => {
    destinationIdsRef.current = destinationKey ? destinationKey.split(",") : [];
    markerElsRef.current.forEach(({ dot, label }, id) =>
      styleMarker(dot, label, id === selectedPoiId, false, destinationIdsRef.current.includes(id)));
  }, [selectedPoiId, destinationKey]);

  // Navigation route overlay: the real waypoint sequence from POST /api/v1/navigation/routes (real
  // Dijkstra over the baked navmesh graph -- see docs/navigation.md). Nothing is drawn when a route
  // could not be found; ViewerWorkspace never fabricates a straight-line substitute.
  useEffect(() => {
    const scene = routeSceneRef.current;
    if (!scene) return;
    if (routeMeshRef.current) {
      scene.remove(routeMeshRef.current);
      disposeGroup(routeMeshRef.current);
      routeMeshRef.current = null;
    }
    const runs = (routeRuns ?? []).filter((run) => run.length >= 2);
    if (!toCanonical || runs.length === 0) return;
    // A tube through each stretch's waypoints exactly (straight segments, no smoothing), a few centimetres thick so it
    // reads at any zoom (WebGL lines are one pixel wide), and a sphere where the route stops or ends. Drawn over the
    // splats so the floor never hides it.
    const group = new THREE.Group();
    group.name = "chaya-route";
    for (const run of runs) {
      const path = new THREE.CurvePath<THREE.Vector3>();
      for (let i = 1; i < run.length; i++) {
        const a = run[i - 1];
        const b = run[i];
        path.add(new THREE.LineCurve3(new THREE.Vector3(a.x, a.y, a.z), new THREE.Vector3(b.x, b.y, b.z)));
      }
      const geometry = new THREE.TubeGeometry(path, Math.max(2, (run.length - 1) * 8), 0.04, 8, false);
      const material = new THREE.MeshBasicMaterial({ color: 0xf59e0b, depthTest: false, transparent: true, opacity: 0.9 });
      group.add(new THREE.Mesh(geometry, material));
      for (const w of run) {
        if (w.kind !== "STOP" && w.kind !== "DESTINATION") continue;
        const end = new THREE.Mesh(new THREE.SphereGeometry(0.15, 16, 12),
          new THREE.MeshBasicMaterial({ color: 0x16a34a, depthTest: false, transparent: true, opacity: 0.95 }));
        end.name = `chaya-route-${w.kind.toLowerCase()}`;
        end.position.set(w.x, w.y, w.z + 0.15);
        group.add(end);
      }
    }
    scene.add(group);
    routeMeshRef.current = group;
  }, [routeRuns, blobUrl, toCanonical]);

  return (
    <div
      ref={containerRef}
      // A click on the background clears the selection; the end of an orbit or pan drag does not.
      onPointerDown={(e) => {
        pointerDownRef.current = { x: e.clientX, y: e.clientY };
      }}
      onClick={(e) => {
        const down = pointerDownRef.current;
        pointerDownRef.current = null;
        if (down && Math.hypot(e.clientX - down.x, e.clientY - down.y) > CLICK_SLOP_PX) return;
        onSelectPoi(null);
      }}
      // Keyboard: arrows pan, Shift+arrows orbit (the orbit controls), + / - zoom, 0 or Home resets, Escape deselects.
      tabIndex={0}
      role="application"
      aria-label="3D reconstruction. Arrow keys pan, Shift and arrow keys orbit, plus and minus zoom, 0 resets the view."
      onKeyDown={(e) => {
        const c = cameraRef.current;
        if (!c) return;
        const zoomBy = (factor: number) => {
          const t = c.controls.target;
          const p = c.camera.position;
          c.animate({
            position: [t.x + (p.x - t.x) * factor, t.y + (p.y - t.y) * factor, t.z + (p.z - t.z) * factor],
            target: [t.x, t.y, t.z],
          });
        };
        if (e.key === "+" || e.key === "=") zoomBy(0.8);
        else if (e.key === "-" || e.key === "_") zoomBy(1.25);
        else if (e.key === "0" || e.key === "Home") c.animate(c.home);
        else if (e.key === "Escape") onSelectPoi(null);
        else return;
        e.preventDefault();
      }}
      className="relative h-full w-full overflow-hidden bg-zinc-950 outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-amber-400"
      data-testid="splat-canvas"
    />
  );
}

function disposeGroup(group: THREE.Group) {
  group.traverse((o) => {
    if (o instanceof THREE.Mesh) {
      o.geometry.dispose();
      (o.material as THREE.Material).dispose();
    }
  });
}
