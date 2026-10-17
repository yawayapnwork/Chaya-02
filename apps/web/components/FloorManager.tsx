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

  // One action at a time (a double click must not create a floor twice); true when the server accepted it, so a form is
  // cleared only then and keeps what was typed when it is refused.
  const [busy, setBusy] = useState(false);
  const run = async (action: () => Promise<unknown>): Promise<boolean> => {
    if (busy) return false;
    setError(null);
    setBusy(true);
    try {
      await action();
      onChanged();
      return true;
    } catch (e) {
      setError(describe(e));
      return false;
    } finally {
      setBusy(false);
    }
  };

  return (
    <details className="rounded border p-3 text-sm" data-testid="floor-manager">
      <summary className="cursor-pointer py-2 font-medium">Manage floors</summary>
      {error && <p role="alert" className="mt-2 whitespace-pre-line text-red-700">{error}</p>}
      <ul className="mt-2 space-y-3">
        {floors.map((f) => <FloorRow key={f.id} venueId={venueId} floor={f} run={run} busy={busy} />)}
      </ul>
      <form className="mt-3 flex flex-wrap items-end gap-2" onSubmit={(e) => {
        e.preventDefault();
        void run(() => createFloor(venueId, { level: Number(newLevel), name: newName.trim() })).then((ok) => { if (ok) { setNewLevel(""); setNewName(""); } });
      }}>
        <label>Level <input className="ml-1 w-16 rounded border p-1" type="number" step={1} required value={newLevel} onChange={(e) => setNewLevel(e.target.value)} /></label>
        <label>Name <input className="ml-1 rounded border p-1" required pattern=".*\S.*" title="A name, not only spaces" value={newName} onChange={(e) => setNewName(e.target.value)} /></label>
        <button type="submit" className="min-h-9 rounded bg-black px-3 py-1 text-white disabled:opacity-50" disabled={busy}>Add floor</button>
      </form>
      <div className="mt-3">
        <button type="button" className="min-h-9 underline disabled:opacity-50" disabled={busy}
          onClick={() => { setError(null); void getFloorConsistency(venueId).then(setReport, (e) => setError(describe(e))); }}>
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

function describe(e: unknown): string {
  if (e instanceof ApiError) return `${e.code}: ${e.message}`;
  return e instanceof Error ? e.message : String(e);
}

function floorName(floors: Floor[], id: string): string {
  return floors.find((f) => f.id === id)?.name ?? id;
}

function FloorRow({ venueId, floor, run, busy }: {
  venueId: string; floor: Floor; run: (a: () => Promise<unknown>) => Promise<boolean>; busy: boolean;
}) {
  const [name, setName] = useState(floor.name);
  const [level, setLevel] = useState(String(floor.level));
  const [elevation, setElevation] = useState(floor.elevation ? String(floor.elevation.elevationM) : "");
  const [source, setSource] = useState<string>(floor.elevation?.source ?? "MEASURED_ON_SITE");
  const [reference, setReference] = useState(floor.elevation?.reference ?? "");
  // Number("") is 0: an emptied Level field must not move the floor to level 0.
  const levelValid = /^-?\d+$/.test(level.trim());
  return (
    <li className="space-y-1 rounded border p-2" data-testid="floor-row">
      <p className="font-medium">{floorLabel(floor)}</p>
      <div className="flex flex-wrap items-end gap-2">
        <label>Name <input className="ml-1 rounded border p-1" value={name} onChange={(e) => setName(e.target.value)} /></label>
        <label>Level <input className="ml-1 w-16 rounded border p-1" type="number" value={level} onChange={(e) => setLevel(e.target.value)} /></label>
        <button type="button" className="min-h-9 underline disabled:opacity-50" disabled={busy || !levelValid || !name.trim()}
          onClick={() => void run(() => updateFloor(venueId, floor.id, { level: Number(level), name: name.trim() }))}>Save</button>
        <button type="button" className="min-h-9 text-red-700 underline disabled:opacity-50" disabled={busy} onClick={() => {
          if (window.confirm(`Delete ${floor.name}? Only a floor nothing relies on can be deleted.`)) void run(() => deleteFloor(venueId, floor.id));
        }}>Delete</button>
      </div>
      {(!levelValid || !name.trim()) && (
        <p className="text-xs text-red-700">{!levelValid ? "Level must be a whole number." : "Name must not be empty."}</p>
      )}
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
        <button type="button" className="min-h-9 underline disabled:opacity-50" disabled={busy || elevation === "" || !Number.isFinite(Number(elevation))}
          onClick={() => void run(() => setFloorElevation(venueId, floor.id, { elevationM: Number(elevation), source, reference }))}>
          Record elevation
        </button>
        {floor.elevation && (
          <button type="button" className="min-h-9 underline disabled:opacity-50" disabled={busy} onClick={() => void run(() => clearFloorElevation(venueId, floor.id))}>Withdraw elevation</button>
        )}
      </div>
      <p className="text-xs text-zinc-600">
        Record only a height somebody measured or took from a survey. Chaya does not measure how floors relate to each other.
      </p>
    </li>
  );
}
