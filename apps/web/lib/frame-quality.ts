// Render scale driven by measured frame times. Pure, so it is unit-tested.
//
// lib/device-profile.ts picks load-time settings from what the browser reports about the device (cores, memory, mobile
// UA). Those are only a prior: the cost of a splat scene depends on the scene and the GPU, which nothing reports. So
// while a scene is on screen the viewer measures how long its frames actually take and adjusts the one setting that can
// change without reloading the scene: the renderer's pixel ratio (render resolution). Slow frames lower it, sustained
// fast frames raise it again, never above the device's own pixel ratio. Hysteresis and a cooldown keep it from
// oscillating, and a window with a long gap (a hidden tab, a debugger pause) is discarded rather than measured.

export interface FrameQualityOptions {
  /** Frames per measurement window. */
  windowFrames: number;
  /** Median frame time above which the scale steps down (ms). 40 ms = 25 fps. */
  slowMs: number;
  /** Median frame time below which the scale may step up (ms). 22 ms ~ 45 fps. */
  fastMs: number;
  /** A frame longer than this is a stall (hidden tab, pause), not rendering cost; its window is discarded. */
  stallMs: number;
  minScale: number;
  step: number;
}

export const DEFAULT_FRAME_QUALITY: FrameQualityOptions = {
  windowFrames: 60,
  slowMs: 40,
  fastMs: 22,
  stallMs: 500,
  minScale: 0.5,
  step: 0.15,
};

export interface FrameQualityState {
  /** Pixel ratio in use, relative to CSS pixels. */
  pixelRatio: number;
  /** The most the device supports (window.devicePixelRatio, or 1 when the device profile ignores it). */
  maxPixelRatio: number;
  /** Median frame time of the last complete window (ms), null until one was measured. */
  medianMs: number | null;
  frames: number[];
}

export function initialFrameQuality(maxPixelRatio: number): FrameQualityState {
  const max = Number.isFinite(maxPixelRatio) && maxPixelRatio > 0 ? maxPixelRatio : 1;
  return { pixelRatio: max, maxPixelRatio: max, medianMs: null, frames: [] };
}

function median(values: number[]): number {
  const s = [...values].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

/** Records one frame's duration; at the end of a window, returns the state with a possibly changed pixel ratio. */
export function recordFrame(state: FrameQualityState, frameMs: number, o: FrameQualityOptions = DEFAULT_FRAME_QUALITY): FrameQualityState {
  if (!Number.isFinite(frameMs) || frameMs <= 0) return state;
  if (frameMs > o.stallMs) return { ...state, frames: [] };
  const frames = [...state.frames, frameMs];
  if (frames.length < o.windowFrames) return { ...state, frames };
  const med = median(frames);
  const floor = Math.min(state.maxPixelRatio, o.minScale);
  let pixelRatio = state.pixelRatio;
  if (med > o.slowMs) pixelRatio = Math.max(floor, state.pixelRatio * (1 - o.step));
  else if (med < o.fastMs) pixelRatio = Math.min(state.maxPixelRatio, state.pixelRatio * (1 + o.step));
  pixelRatio = Math.round(pixelRatio * 100) / 100;
  return { ...state, pixelRatio, medianMs: med, frames: [] };
}
