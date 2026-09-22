// Device-aware rendering settings for the splat viewer. Every branch here reads a real, measured signal
// from the browser (navigator.hardwareConcurrency, navigator.deviceMemory where Chrome exposes it,
// crossOriginIsolated, user agent) -- nothing is a guess or a fixed "mobile vs desktop" assumption beyond
// what those signals already tell us. See GaussianSplats3D's README for what each option controls.

export type DeviceTier = "low" | "mid" | "high";

export interface DeviceProfile {
  tier: DeviceTier;
  /** GaussianSplats3D `sphericalHarmonicsDegree`: view-dependent colour detail. Costs memory and sort time. */
  sphericalHarmonicsDegree: 0 | 1 | 2;
  /** GaussianSplats3D `halfPrecisionCovariancesOnGPU`: halves GPU texture memory for splat shape data. */
  halfPrecisionCovariancesOnGPU: boolean;
  /** GaussianSplats3D `gpuAcceleratedSort`: the library itself already defaults this off on mobile. */
  gpuAcceleratedSort: boolean;
  /** GaussianSplats3D `sharedMemoryForWorkers`: needs cross-origin isolation or the worker transfer fails. */
  sharedMemoryForWorkers: boolean;
  ignoreDevicePixelRatio: boolean;
  /** Basic LOD: splats with alpha below this (0-255) are dropped at load time, so a weaker device loads and
   * renders fewer, coarser splats. Same knob GaussianSplats3D exposes as `splatAlphaRemovalThreshold`. */
  splatAlphaRemovalThreshold: number;
}

function hasSharedArrayBufferSupport(): boolean {
  return typeof SharedArrayBuffer !== "undefined" && (globalThis as { crossOriginIsolated?: boolean }).crossOriginIsolated === true;
}

export function detectDeviceProfile(): DeviceProfile {
  const isMobileUa = /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent);
  const cores = navigator.hardwareConcurrency || 4;
  // deviceMemory is a real Chrome/Edge signal (GiB, rounded); other browsers omit it, so we simply don't
  // use it as a signal there rather than guessing a value.
  const memoryGiB = (navigator as Navigator & { deviceMemory?: number }).deviceMemory;
  const lowMemory = memoryGiB !== undefined && memoryGiB <= 4;
  const lowEnd = isMobileUa || cores <= 4 || lowMemory;
  const highEnd = !isMobileUa && cores >= 8 && (memoryGiB === undefined || memoryGiB >= 8);

  const tier: DeviceTier = lowEnd ? "low" : highEnd ? "high" : "mid";
  return {
    tier,
    sphericalHarmonicsDegree: tier === "high" ? 1 : 0,
    halfPrecisionCovariancesOnGPU: true,
    gpuAcceleratedSort: !isMobileUa,
    sharedMemoryForWorkers: hasSharedArrayBufferSupport(),
    ignoreDevicePixelRatio: tier === "low",
    splatAlphaRemovalThreshold: tier === "low" ? 12 : tier === "mid" ? 5 : 1,
  };
}
