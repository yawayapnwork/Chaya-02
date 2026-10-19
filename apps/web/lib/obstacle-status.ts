// What the AR overlay says about obstacles (docs/navigation.md, "Camera obstacle sensing"). Pure.
//
// Positions are always shown as estimates with their uncertainty ("about 3 m ahead, ±0.4 m"), never as verified; a
// moving person is a heads-up, not a blockage; and when sensing is paused (tracking lost or limited, not localized) the
// overlay says so instead of implying the way is clear.

import type { ObstacleObservation } from "./obstacle-reroute.ts";

export type SensingAvailability =
  | { kind: "UNAVAILABLE"; reason: string }
  | { kind: "LIVE"; detail: string }
  | { kind: "PAUSED"; reason: string };

export type RerouteOutcome =
  | { kind: "NONE" }
  | { kind: "REROUTING" }
  | { kind: "REROUTED"; avoided: number }
  | { kind: "RESTORED" }
  | { kind: "NO_ALTERNATIVE"; stepFree: boolean }
  | { kind: "FAILED"; detail: string };

export interface ObstacleMessage {
  level: "info" | "warning";
  text: string;
  testId: string;
}

function describe(o: ObstacleObservation, aheadMeters: number | null): string {
  const what = o.category === "PERSON" ? "A person" : "Something";
  const where = aheadMeters === null ? "on the route" : `about ${Math.max(0, aheadMeters).toFixed(0)} m ahead`;
  const pm = o.uncertaintyMeters !== undefined ? ` (estimated position, ±${o.uncertaintyMeters.toFixed(1)} m)` : " (estimated position)";
  return `${what} is in the way ${where}${pm}`;
}

/**
 * @param onPath confirmed obstacles touching the route ahead, with their distance along it (null: not measured)
 * @param movingNearby confirmed people moving near the route (not routed around)
 */
export function obstacleMessages(args: {
  sensing: SensingAvailability;
  onPath: readonly { obstacle: ObstacleObservation; aheadMeters: number | null }[];
  movingNearby: number;
  outcome: RerouteOutcome;
}): ObstacleMessage[] {
  const out: ObstacleMessage[] = [];
  if (args.sensing.kind === "UNAVAILABLE") {
    out.push({ level: "info", testId: "ar-obstacles-unavailable",
      text: `Obstacle detection is not available on this device (${args.sensing.reason}): the route does not adapt to people or objects in the way.` });
  } else if (args.sensing.kind === "PAUSED") {
    out.push({ level: "warning", testId: "ar-obstacles-paused",
      text: `Obstacle detection paused (${args.sensing.reason}). Watch for people and objects yourself.` });
  }
  for (const o of args.onPath.slice(0, 2)) {
    out.push({ level: "warning", testId: "ar-obstruction", text: `${describe(o.obstacle, o.aheadMeters)}.` });
  }
  if (args.onPath.length > 2) out.push({ level: "warning", testId: "ar-obstruction", text: `${args.onPath.length - 2} more in the way.` });
  if (args.movingNearby > 0) {
    out.push({ level: "info", testId: "ar-moving-people",
      text: `${args.movingNearby === 1 ? "A person is" : `${args.movingNearby} people are`} moving near the route. The route is not changed for people walking by.` });
  }
  switch (args.outcome.kind) {
    case "REROUTING":
      out.push({ level: "info", testId: "ar-reroute-status", text: "Finding a way around…" });
      break;
    case "REROUTED":
      out.push({ level: "info", testId: "ar-reroute-status",
        text: `Route changed to avoid ${args.outcome.avoided === 1 ? "an obstruction" : `${args.outcome.avoided} obstructions`}.` });
      break;
    case "RESTORED":
      out.push({ level: "info", testId: "ar-reroute-status", text: "The way is clear again: back on the usual route." });
      break;
    case "NO_ALTERNATIVE":
      out.push({ level: "warning", testId: "ar-reroute-status",
        text: args.outcome.stepFree
          ? "No step-free way around the obstruction was found, so the route is unchanged. Wait for it to clear, or ask staff for help."
          : "No way around the obstruction was found, so the route is unchanged. Wait for it to clear or pass with care." });
      break;
    case "FAILED":
      out.push({ level: "warning", testId: "ar-reroute-status",
        text: `The route could not be recalculated (${args.outcome.detail}); the current route is kept.` });
      break;
    case "NONE":
      break;
  }
  return out;
}
