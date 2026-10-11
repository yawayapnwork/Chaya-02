// What a failed AR request means for the session (docs/ar.md, "Failure states"). Pure: it only reads the error's shape, so
// it is unit tested without the network or the auth stack. Each state is named, shown, and decides whether the session
// keeps retrying on its own.
//
//   SESSION_EXPIRED            401, or no valid sign-in / viewer token. Retrying cannot help: the user must sign in again or
//                              reopen the viewer link. Relocalization and routing stop until then.
//   NETWORK_UNAVAILABLE        the request never got an HTTP answer (offline, DNS, TLS, a dropped connection). Retried; a
//                              route already drawn stays usable while the device tracks.
//   ANCHOR_UNAVAILABLE         the server will not use a registered marker any more (deleted, no longer calibrated, its pose
//                              in another frame or version). That marker is dropped for this session; others still work.
//   ANCHORS_INCONSISTENT       the markers seen together disagree (one was moved or misidentified): observe a third.
//   VERSION_CHANGED            the floor was republished: restart AR to load the new version.
//   REFUSED                    any other refusal from the server, with its code.

export type ArFailure =
  | "SESSION_EXPIRED"
  | "NETWORK_UNAVAILABLE"
  | "ANCHOR_UNAVAILABLE"
  | "ANCHORS_INCONSISTENT"
  | "VERSION_CHANGED"
  | "REFUSED";

export interface ClassifiedFailure {
  failure: ArFailure;
  code: string;
  detail: string;
  /** Whether the session should keep trying on its own (after its usual retry interval). */
  retryable: boolean;
  /** For ANCHOR_UNAVAILABLE: the anchor the server named, when it did. */
  anchorId: string | null;
  action: string;
}

const ANCHOR_CODES = new Set(["ANCHOR_NOT_CALIBRATED", "ANCHOR_FRAME_STALE", "ANCHOR_UNVERSIONED", "ANCHOR_VERSION_MISMATCH"]);
const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

export function classifyArFailure(error: unknown, online: boolean = true): ClassifiedFailure {
  const e = error as { name?: string; status?: number; code?: string; message?: string } | null;
  const message = e?.message ?? String(error);
  const status = typeof e?.status === "number" ? e.status : null;
  const code = typeof e?.code === "string" ? e.code : null;
  const anchorId = message.match(UUID)?.[0] ?? null;
  const make = (failure: ArFailure, retryable: boolean, action: string, id: string | null = null): ClassifiedFailure =>
    ({ failure, code: code ?? (status ? `HTTP_${status}` : e?.name ?? "ERROR"), detail: message, retryable, anchorId: id, action });

  if (status === 401 || e?.name === "NotSignedInError" || (e?.constructor as { name?: string } | undefined)?.name === "NotSignedInError") {
    return make("SESSION_EXPIRED", false, "Your sign-in or viewer link has expired. Sign in again (or reopen the viewer link), then restart AR.");
  }
  if (status === null && (!online || e?.name === "TypeError" || e?.name === "AbortError" || /network|fetch|offline/i.test(message))) {
    return make("NETWORK_UNAVAILABLE", true, "No connection to the server. A route already shown stays usable while tracking holds; " +
      "localizing again and routing resume when the connection returns.");
  }
  if (code !== null && ANCHOR_CODES.has(code)) {
    return make("ANCHOR_UNAVAILABLE", false, "This marker can no longer be used (an operator must re-enter or recalibrate it). " +
      "Point the camera at another registered marker.", anchorId);
  }
  if (status === 404 && /anchor/i.test(message)) {
    return make("ANCHOR_UNAVAILABLE", false, "This marker no longer exists. Point the camera at another registered marker.", anchorId);
  }
  if (code === "RELOCALIZATION_INCONSISTENT") {
    return make("ANCHORS_INCONSISTENT", true, "The markers in view disagree: one was moved or is the wrong print. Point the camera at " +
      "one marker at a time, or include a third marker.");
  }
  if (code === "VERSION_MISMATCH") {
    return make("VERSION_CHANGED", false, "This floor was republished. End AR and start again to load the new version.");
  }
  return make("REFUSED", status === null || status >= 500, `The server refused the request${code ? ` (${code})` : ""}.`);
}
