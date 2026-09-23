// WebXR capability detection for the Android AR client. Every branch reads a real, present browser API --
// see docs/ar.md "Android: WebXR". When the API is missing or the browser reports the session type
// unsupported, callers get an explicit unsupported-device result; nothing here ever falls back to a
// simulated or fake AR view.

export type WebXrSupport =
  | { supported: true }
  | { supported: false; reason: "NO_NAVIGATOR_XR" | "SESSION_NOT_SUPPORTED" | "CHECK_FAILED"; detail: string };

interface XrSystemLike {
  isSessionSupported(mode: string): Promise<boolean>;
}

/** Real navigator.xr by default; a caller (tests) may inject a stand-in navigator without needing to
 * overwrite the read-only global `navigator` binding Node itself now defines. */
export async function detectWebXrSupport(
  nav: { xr?: XrSystemLike } | undefined = (globalThis as { navigator?: { xr?: XrSystemLike } }).navigator,
): Promise<WebXrSupport> {
  const xr = nav?.xr;
  if (!xr) {
    return {
      supported: false,
      reason: "NO_NAVIGATOR_XR",
      detail: "This browser does not expose navigator.xr. WebXR AR requires an AR-capable Chrome on Android.",
    };
  }
  try {
    const ok = await xr.isSessionSupported("immersive-ar");
    if (!ok) {
      return {
        supported: false,
        reason: "SESSION_NOT_SUPPORTED",
        detail: "navigator.xr is present but this device/browser reports immersive-ar as unsupported.",
      };
    }
    return { supported: true };
  } catch (e) {
    return {
      supported: false,
      reason: "CHECK_FAILED",
      detail: e instanceof Error ? e.message : String(e),
    };
  }
}
