// Which floor the AR device is on, from the registered markers it sees (docs/ar.md, "Floor handoff"). Pure.
//
// Every marker is registered on one floor. After a floor change (stairs, lift) the session must not draw the next floor's
// route until it is sure where the device is:
//   CONFIRMED   only the expected floor's markers were seen, in at least `minSightings` of the recent frames that saw any
//               marker. Right after a floor change that is MIN_FLOOR_SIGHTINGS (the device just moved between floors);
//               at the start of a session, on the floor the user chose, one sighting.
//   UNEXPECTED  only another floor's markers were seen, in at least MIN_FLOOR_SIGHTINGS frames: the user is on that floor
//               (got off the lift too early, took the wrong stairs). The route overlay is not switched; the user is asked,
//               and on confirmation the session localizes on that floor and the route is planned again from there.
//   AMBIGUOUS   markers of two or more floors were seen within the window (a marker visible across an atrium or a
//               stairwell): nothing is decided until the camera sees one floor's markers alone.
//   PENDING     not enough evidence yet.

export const FLOOR_WINDOW = 4;
export const MIN_FLOOR_SIGHTINGS = 2;

export type FloorCheck =
  | { status: "CONFIRMED" }
  | { status: "PENDING" }
  | { status: "AMBIGUOUS"; floorIds: string[] }
  | { status: "UNEXPECTED"; detectedFloorId: string };

/** The floors seen in each of the last FLOOR_WINDOW frames that saw any registered marker. */
export function pushFloorWindow(window: readonly string[][], batchFloors: readonly string[]): string[][] {
  const batch = [...new Set(batchFloors)].sort();
  if (batch.length === 0) return [...window];
  return [...window, batch].slice(-FLOOR_WINDOW);
}

export function identifyFloor(window: readonly string[][], expectedFloorId: string, minSightings: number = MIN_FLOOR_SIGHTINGS): FloorCheck {
  const floors = [...new Set(window.flat())].sort();
  if (floors.length === 0) return { status: "PENDING" };
  if (floors.length > 1) return { status: "AMBIGUOUS", floorIds: floors };
  const only = floors[0];
  const sightings = window.filter((b) => b.includes(only)).length;
  if (only === expectedFloorId) return sightings >= minSightings ? { status: "CONFIRMED" } : { status: "PENDING" };
  return sightings >= MIN_FLOOR_SIGHTINGS ? { status: "UNEXPECTED", detectedFloorId: only } : { status: "PENDING" };
}
