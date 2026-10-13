// What kind of failure the viewer is showing, so each is presented as what it is (and retried only when a retry can
// help). Pure: it reads the error's own type and fields, never its message text, so node --test runs it.
//
//   unauthorized  the session or viewing link may not see this venue/floor/model (HTTP 401/403, a refused link)
//   unavailable   there is nothing (yet) to show: no published version, or the model/asset is not found (HTTP 404)
//   corrupt       the downloaded bytes are not the registered artifact (size or SHA-256 differ, truncated download)
//   incompatible  the bytes are intact but not a viewer asset this viewer can render (.ksplat contract, recorded format,
//                 or a scene that does not match what was requested)
//   graphics      the device's WebGL context could not be created or was lost
//   failed        anything else: network errors, server errors, a loader failure

import { ArtifactIntegrityError, KsplatInvalidError } from "./artifact-integrity.ts";

export type ViewerFailureKind = "unauthorized" | "unavailable" | "corrupt" | "incompatible" | "graphics" | "failed";

export interface ViewerFailure {
  kind: ViewerFailureKind;
  title: string;
  message: string;
  /** Whether trying again can succeed without anything changing on the server (or in the session). */
  retryable: boolean;
}

const TITLES: Record<ViewerFailureKind, string> = {
  unauthorized: "Not authorized to view this reconstruction",
  unavailable: "Reconstruction unavailable",
  corrupt: "The reconstruction download is corrupt",
  incompatible: "The reconstruction cannot be displayed",
  graphics: "Graphics are unavailable",
  failed: "Could not load the reconstruction",
};

/** A WebGL context that could not be created, or was lost while the model was on screen. */
export class GraphicsContextError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "GraphicsContextError";
  }
}

function make(kind: ViewerFailureKind, message: string, retryable: boolean): ViewerFailure {
  return { kind, title: TITLES[kind], message, retryable };
}

export function classifyViewerError(e: unknown): ViewerFailure {
  const message = e instanceof Error ? e.message : String(e);
  if (e instanceof GraphicsContextError) return make("graphics", message, true);
  if (e instanceof KsplatInvalidError) {
    // A truncated file is what an interrupted transfer looks like; anything else is the file itself.
    return e.reason === "TRUNCATED" ? make("corrupt", message, true) : make("incompatible", message, false);
  }
  if (e instanceof ArtifactIntegrityError) {
    // Bytes that differ from the registration may be a faulty transfer, so a new download may succeed. A missing
    // registration, an unexpected format or a scene out of scope will not change by downloading again.
    const corrupt = e.code === "SIZE_MISMATCH" || e.code === "CHECKSUM_MISMATCH";
    return corrupt ? make("corrupt", message, true) : make("incompatible", message, false);
  }
  const status = typeof e === "object" && e !== null && "status" in e ? Number((e as { status: unknown }).status) : NaN;
  const code = typeof e === "object" && e !== null && "code" in e ? String((e as { code: unknown }).code) : "";
  if (status === 401 || status === 403 || code === "LINK_INVALID") {
    return make("unauthorized", message, false);
  }
  if (status === 404 || status === 410) return make("unavailable", message, false);
  if (Number.isFinite(status) && status >= 400 && status < 500 && status !== 408 && status !== 429) {
    return make("failed", message, false);
  }
  // Network errors, timeouts, 5xx, 408/429, and loader errors: worth another try.
  return make("failed", message, true);
}
