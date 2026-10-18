/**
 * Keeps everything the viewer draws tied to the one reconstruction it was loaded for (docs/rescan.md, "VERSIONING").
 *
 * The viewer loads a scene in several independent requests -- the reconstruction, its .ksplat, its POIs, a route -- and
 * the user can switch versions while any of them is in flight. Each result is stored with the key of the scene it was
 * requested for, and is only ever used while that is still the scene on screen. So version N's model is never drawn with
 * version N+1's frame, POIs or route, even for the moment between a switch and the next response.
 */

/** The identity of what the viewer shows: a FINALIZED scan version, or a reconstruction that is not one. */
export interface SceneIdentity {
  runId: string;
  scanVersionId?: string | null;
}

export function sceneKey(scene: SceneIdentity | null | undefined): string | null {
  if (!scene) return null;
  return scene.scanVersionId ? `version:${scene.scanVersionId}` : `run:${scene.runId}`;
}

/** A value loaded for one scene. */
export interface Scoped<T> {
  key: string;
  value: T;
}

/** The value, if it was loaded for the scene currently shown; otherwise null (never another scene's). */
export function forScene<T>(loaded: Scoped<T> | null, currentKey: string | null): T | null {
  return loaded && currentKey !== null && loaded.key === currentKey ? loaded.value : null;
}

/** How a reconstruction is named in the version picker. `current`: the version the floor publishes. A room version (one
 * room of the floor, docs/multi-room.md) is named by its room; a stitched floor says so. */
export function versionLabel(v: { versionNumber?: number | null; generatedAt: string; kind?: string; roomName?: string | null },
                             formattedDate: string, current: boolean): string {
  const name = v.versionNumber != null ? `v${v.versionNumber} · ${formattedDate}` : `${formattedDate} (not a finalized version)`;
  const described = v.kind === "ROOM" ? `Room ${v.roomName ?? "?"} · ${name}` : v.kind === "STITCHED" ? `${name} · stitched rooms` : name;
  return current ? `${described} (current)` : described;
}

/** Which listed version the viewer opens on: the one the floor publishes (its current scan version), never merely the
 * newest. When the floor publishes none, the newest listed version of the whole floor (every listed one is a finalized
 * version); a single room's version only when there is nothing else. */
export function initialVersion<T extends { current?: boolean; kind?: string }>(versions: T[]): T | null {
  return versions.find((v) => v.current) ?? versions.find((v) => v.kind !== "ROOM") ?? versions[0] ?? null;
}
