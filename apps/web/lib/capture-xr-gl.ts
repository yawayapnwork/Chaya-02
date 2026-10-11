"use client";

// Device-only WebGL plumbing for the capture AR session (lib/capture-xr-session.ts): the base layer an immersive session
// needs before it delivers frames, and, when the browser granted the "camera-access" feature, a small downsampled copy of
// the camera image for the frame-quality checks (lib/capture-quality.ts). Nothing is rendered: each frame only clears.
//
// NOT DEVICE-VALIDATED: this file can only run inside a real immersive-ar session on an ARCore device. It is type-checked
// and built, but no automated test can execute it (docs/capture-hud.md section 9).

import type { Frame } from "./capture-quality";
import type { BaseLayer, FrameLike, SessionLike } from "./capture-xr-session";

export const CAMERA_SAMPLE_WIDTH = 160;
export const CAMERA_SAMPLE_HEIGHT = 120;

interface CameraView { camera?: { width: number; height: number } }
interface GlBinding { getCameraImage(camera: unknown): WebGLTexture | null }

export async function createCaptureBaseLayer(session: SessionLike): Promise<BaseLayer | null> {
  const canvas = document.createElement("canvas");
  const gl = (canvas.getContext("webgl2", { xrCompatible: true, alpha: true }) ??
    canvas.getContext("webgl", { xrCompatible: true, alpha: true })) as WebGL2RenderingContext | WebGLRenderingContext | null;
  if (!gl) return null;
  try {
    await (gl as WebGLRenderingContext & { makeXRCompatible?: () => Promise<void> }).makeXRCompatible?.();
  } catch {
    return null;
  }
  const XRWebGLLayerCtor = (globalThis as unknown as { XRWebGLLayer?: new (s: unknown, g: unknown) => { framebuffer: WebGLFramebuffer | null } }).XRWebGLLayer;
  if (!XRWebGLLayerCtor) return null;
  const layer = new XRWebGLLayerCtor(session, gl);

  const gl2 = typeof WebGL2RenderingContext !== "undefined" && gl instanceof WebGL2RenderingContext ? gl : null;
  const cameraAccess = (session.enabledFeatures ?? []).includes("camera-access");
  const BindingCtor = (globalThis as unknown as { XRWebGLBinding?: new (s: unknown, g: unknown) => GlBinding }).XRWebGLBinding;
  const binding = cameraAccess && gl2 && BindingCtor ? new BindingCtor(session, gl2) : null;

  // Read path: camera texture -> FBO -> blit (linear) into a small renderbuffer -> readPixels.
  let srcFbo: WebGLFramebuffer | null = null;
  let dstFbo: WebGLFramebuffer | null = null;
  let dstRb: WebGLRenderbuffer | null = null;
  if (gl2 && binding) {
    srcFbo = gl2.createFramebuffer();
    dstFbo = gl2.createFramebuffer();
    dstRb = gl2.createRenderbuffer();
    gl2.bindRenderbuffer(gl2.RENDERBUFFER, dstRb);
    gl2.renderbufferStorage(gl2.RENDERBUFFER, gl2.RGBA8, CAMERA_SAMPLE_WIDTH, CAMERA_SAMPLE_HEIGHT);
    gl2.bindFramebuffer(gl2.FRAMEBUFFER, dstFbo);
    gl2.framebufferRenderbuffer(gl2.FRAMEBUFFER, gl2.COLOR_ATTACHMENT0, gl2.RENDERBUFFER, dstRb);
    gl2.bindFramebuffer(gl2.FRAMEBUFFER, null);
  }
  const pixels = new Uint8Array(CAMERA_SAMPLE_WIDTH * CAMERA_SAMPLE_HEIGHT * 4);

  return {
    layer,
    beginFrame() {
      gl.bindFramebuffer(gl.FRAMEBUFFER, layer.framebuffer);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    },
    readCamera: gl2 && binding ? (_frame: FrameLike, view: unknown): Frame | null => {
      const cam = (view as CameraView).camera;
      if (!cam || !srcFbo || !dstFbo) return null;
      const tex = binding.getCameraImage(cam);
      if (!tex) return null;
      const prev = gl2.getParameter(gl2.FRAMEBUFFER_BINDING) as WebGLFramebuffer | null;
      gl2.bindFramebuffer(gl2.READ_FRAMEBUFFER, srcFbo);
      gl2.framebufferTexture2D(gl2.READ_FRAMEBUFFER, gl2.COLOR_ATTACHMENT0, gl2.TEXTURE_2D, tex, 0);
      gl2.bindFramebuffer(gl2.DRAW_FRAMEBUFFER, dstFbo);
      gl2.blitFramebuffer(0, 0, cam.width, cam.height, 0, 0, CAMERA_SAMPLE_WIDTH, CAMERA_SAMPLE_HEIGHT, gl2.COLOR_BUFFER_BIT, gl2.LINEAR);
      gl2.bindFramebuffer(gl2.READ_FRAMEBUFFER, dstFbo);
      gl2.readPixels(0, 0, CAMERA_SAMPLE_WIDTH, CAMERA_SAMPLE_HEIGHT, gl2.RGBA, gl2.UNSIGNED_BYTE, pixels);
      gl2.bindFramebuffer(gl2.FRAMEBUFFER, prev);
      // GL rows run bottom-up; the quality metrics do not care about orientation, but keep the image upright anyway.
      const data = new Uint8ClampedArray(pixels.length);
      const row = CAMERA_SAMPLE_WIDTH * 4;
      for (let y = 0; y < CAMERA_SAMPLE_HEIGHT; y++) {
        data.set(pixels.subarray((CAMERA_SAMPLE_HEIGHT - 1 - y) * row, (CAMERA_SAMPLE_HEIGHT - y) * row), y * row);
      }
      return { width: CAMERA_SAMPLE_WIDTH, height: CAMERA_SAMPLE_HEIGHT, data };
    } : undefined,
    dispose() {
      if (gl2) {
        if (srcFbo) gl2.deleteFramebuffer(srcFbo);
        if (dstFbo) gl2.deleteFramebuffer(dstFbo);
        if (dstRb) gl2.deleteRenderbuffer(dstRb);
      }
    },
  };
}

/** XRRay in viewer space with a horizontal direction, or undefined when the browser has no XRRay. */
export function makeViewerRay(dirX: number, dirZ: number): unknown {
  const XRRayCtor = (globalThis as unknown as { XRRay?: new (origin: DOMPointInit, direction: DOMPointInit) => unknown }).XRRay;
  return XRRayCtor ? new XRRayCtor({ x: 0, y: 0, z: 0, w: 1 }, { x: dirX, y: 0, z: dirZ, w: 0 }) : undefined;
}
