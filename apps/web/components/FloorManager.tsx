"use client";

import { useState } from "react";
import {
  ApiError,
  type Floor,
  type FloorConsistencyReport,
  clearFloorElevation,
  createFloor,
  deleteFloor,
  getFloorConsistency,
  setFloorElevation,
  updateFloor,
} from "@/lib/capture-api";
import { floorLabel } from "@/lib/floor-transitions";

/**
 * Floors of a venue, for venue managers (docs/navigation.md, "Floors"): create, rename and re-level floors, record a
 * floor's elevation from a survey or a measurement on site (never estimated), and check the venue's floors and floor
 * connections against each other. The server enforces who may change what; its refusals are shown as they are.
 */
export default function FloorManager({ venueId, floors, onChanged }: { venueId: string; floors: Floor[]; onChanged: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const [report, setReport] = useState<FloorConsistencyReport | null>(null);
  const [newLevel, setNewLevel] = useState("");
  const [newName, setNewName] = useState("");

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : String(e));
    }
  };

  return (
    <details className="rounded border p-3 text-sm" data-testid="floor-manager">
      <summary className="cursor-pointer font-medium">Manage floors</summary>
      {error && <p role="alert" className="mt-2 whitespace-pre-line text-red-700">{error}</p>}
      <ul className="mt-2 space-y-3">
        {floors.map((f) => <FloorRow key={f.id} venueId={venueId} floor={f} run={run} />)}
      </ul>
      <form className="mt-3 flex flex-wrap items-end gap-2" onSubmit={(e) => {
        e.preventDefault();
        void run(() => createFloor(venueId, { level: Number(newLevel), name: newName })).then(() => { setNewLevel(""); setNewName(""); });
      }}>
        <label>Level <input className="ml-1 w-16 rounded border p-1" type="number" required value={newLevel} onChange={(e) => setNewLevel(e.target.value)} /></label>
        <label>Name <input className="ml-1 rounded border p-1" required value={newName} onChange={(e) => setNewName(e.target.value)} /></label>
        <button className="rounded bg-black px-3 py-1 text-white">Add floor</button>
      </form>
      <div className="mt-3">
        <button className="underline" onClick={() => void getFloorConsistency(venueId).then(setReport, (e) => setError(String(e)))}>
          Check floors and connections
        </button>
        {report && (
          <div className="mt-2 space-y-1" data-testid="floor-consistency">
            <p className={report.consistent ? "text-green-800" : "text-red-800"}>
              {report.consistent ? "No contradictions found." : "Contradictions found: correct the records below."}
            </p>
            <ul className="list-disc pl-5">
              {report.pairs.map((p) => (
                <li key={p.connectionId}>
                  {floorName(floors, p.fromFloorId)} ↔ {floorName(floors, p.toFloorId)}: {p.alignment.replaceAll("_", " ").toLowerCase()} — {p.detail}
                </li>
              ))}
              {report.findings.filter((x) => x.severity !== "OK").map((x, i) => (
                <li key={i} className={x.severity === "ERROR" ? "text-red-800" : "text-amber-800"}>{x.code}: {x.detail}</li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </details>
  );
}

function floorName(floors: Floor[], id: string): string {
  return floors.find((f) => f.id === id)?.name ?? id;
}

function FloorRow({ venueId, floor, run }: { venueId: string; floor: Floor; run: (a: () => Promise<unknown>) => Promise<void> }) {
  const [name, setName] = useState(floor.name);
  const [level, setLevel] = useState(String(floor.level));
  const [elevation, setElevation] = useState(floor.elevation ? String(floor.elevation.elevationM) : "");
  const [source, setSource] = useState<string>(floor.elevation?.source ?? "MEASURED_ON_SITE");
  const [reference, setReference] = useState(floor.elevation?.reference ?? "");
  return (
    <li className="space-y-1 rounded border p-2" data-testid="floor-row">
      <p className="font-medium">{floorLabel(floor)}</p>
      <div className="flex flex-wrap items-end gap-2">
        <label>Name <input className="ml-1 rounded border p-1" value={name} onChange={(e) => setName(e.target.value)} /></label>
        <label>Level <input className="ml-1 w-16 rounded border p-1" type="number" value={level} onChange={(e) => setLevel(e.target.value)} /></label>
        <button className="underline" onClick={() => void run(() => updateFloor(venueId, floor.id, { level: Number(level), name }))}>Save</button>
        <button className="underline text-red-700" onClick={() => {
          if (window.confirm(`Delete ${floor.name}? Only a floor nothing relies on can be deleted.`)) void run(() => deleteFloor(venueId, floor.id));
        }}>Delete</button>
      </div>
      <div className="flex flex-wrap items-end gap-2">
        <label>Elevation (m) <input className="ml-1 w-24 rounded border p-1" type="number" step="0.01" value={elevation}
          onChange={(e) => setElevation(e.target.value)} /></label>
        <label>Source{" "}
          <select className="rounded border p-1" value={source} onChange={(e) => setSource(e.target.value)}>
            <option value="MEASURED_ON_SITE">Measured on site</option>
            <option value="SURVEYED">From a survey</option>
          </select>
        </label>
        <label>Reference <input className="ml-1 rounded border p-1" placeholder="e.g. ground floor finished level" value={reference}
          onChange={(e) => setReference(e.target.value)} /></label>
        <button className="underline" disabled={elevation === ""}
          onClick={() => void run(() => setFloorElevation(venueId, floor.id, { elevationM: Number(elevation), source, reference }))}>
          Record elevation
        </button>
        {floor.elevation && (
          <button className="underline" onClick={() => void run(() => clearFloorElevation(venueId, floor.id))}>Withdraw elevation</button>
        )}
      </div>
      <p className="text-xs text-zinc-600">
        Record only a height somebody measured or took from a survey. Chaya does not measure how floors relate to each other.
      </p>
    </li>
  );
}
