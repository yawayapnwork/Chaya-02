"use client";

import { useEffect, useRef, useState } from "react";
import { classifyVoiceError, createSpeechRecognizer, extractTranscript, speechRecognitionSupported } from "@/lib/voice-input";
import { type SearchPosition, type SearchResponse, type SearchResult, locationNote, searchVenue } from "@/lib/search-api";
import {
  canNavigate,
  diagnosticNote,
  distanceNote,
  emptyMessage,
  indexStatusNote,
  interpretationNotes,
  matchNote,
  resultBadges,
} from "@/lib/search-view";

interface SemanticSearchPanelProps {
  venueId: string;
  /** The currently viewed floor, used only for the "this floor only" filter's target -- results can span
   * every floor of the venue unless that filter is checked. */
  floorId: string;
  /** The finalized scan version on screen: results are limited to its POIs. Null when the reconstruction is not one. */
  scanVersionId?: string | null;
  /** Where the person searching is (the route's From POI), for "nearest". Null: not known, and never guessed. */
  position?: SearchPosition | null;
  /** The POI selected in the viewer, highlighted in the results. */
  selectedPoiId?: string | null;
  onSelectResult: (result: SearchResult) => void;
  /** "Navigate": plan a route to this result. */
  onNavigate?: (result: SearchResult) => void;
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** Natural-language + voice object search. Voice input (lib/voice-input.ts) only ever fills the text box
 * below; the actual ranking always goes through searchVenue, which is backed by real CLIP embeddings on
 * the server (dev.chaya.api.search), never by matching the spoken words directly. What the server says about how it read
 * the query, how far each result is and how strong the evidence is, is shown as it says it (lib/search-view.ts). */
export default function SemanticSearchPanel({ venueId, floorId, scanVersionId, position, selectedPoiId, onSelectResult,
  onNavigate }: SemanticSearchPanelProps) {
  const [query, setQuery] = useState("");
  const [restrictToFloor, setRestrictToFloor] = useState(false);
  const [accessible, setAccessible] = useState(false);
  const [response, setResponse] = useState<SearchResponse | null>(null);
  const [searchedFrom, setSearchedFrom] = useState<SearchPosition | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [voiceNote, setVoiceNote] = useState<string | null>(null);
  const [listening, setListening] = useState(false);
  const recognizerRef = useRef<ReturnType<typeof createSpeechRecognizer>>(null);

  async function runSearch(text: string) {
    if (!text.trim()) return;
    setBusy(true);
    setError(null);
    setVoiceNote(null);
    try {
      setResponse(await searchVenue(venueId, text, {
        floorId: restrictToFloor ? floorId : undefined,
        accessible: accessible || undefined,
        scanVersionId: scanVersionId ?? undefined,
        position: position ?? undefined,
      }));
      setSearchedFrom(position ?? null);
    } catch (e) {
      setError(message(e));
      setResponse(null);
    } finally {
      setBusy(false);
    }
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    runSearch(query);
  }

  function searchFor(text: string) {
    setQuery(text);
    runSearch(text);
  }

  function cancelVoice() {
    recognizerRef.current?.abort();
    setListening(false);
    setVoiceNote(null);
  }

  function toggleVoice() {
    if (listening) {
      recognizerRef.current?.stop();
      return;
    }
    const recognizer = createSpeechRecognizer();
    if (!recognizer) {
      setVoiceNote(classifyVoiceError("unsupported").userMessage);
      return;
    }
    setVoiceNote(null);
    recognizerRef.current = recognizer;
    recognizer.onresult = (event) => {
      const transcript = extractTranscript(event);
      if (transcript) {
        searchFor(transcript);
      } else {
        setVoiceNote("Could not transcribe speech. Try again or type your search.");
      }
    };
    recognizer.onerror = (event) => {
      const disp = classifyVoiceError(event.error);
      setListening(false);
      if (!disp.isCancellation && disp.userMessage) {
        setVoiceNote(disp.userMessage);
      }
    };
    recognizer.onend = () => setListening(false);
    try {
      setListening(true);
      recognizer.start();
    } catch {
      setListening(false);
      setVoiceNote(classifyVoiceError("failed").userMessage);
    }
  }

  useEffect(() => {
    return () => recognizerRef.current?.abort();
  }, []);

  const notes = response ? interpretationNotes(response, searchedFrom) : [];
  const empty = response ? emptyMessage(response) : null;
  const indexNote = response ? indexStatusNote(response) : null;

  return (
    <section>
      <h2 className="font-medium">Search</h2>
      <form onSubmit={onSubmit} className="mt-1 flex gap-1">
        <input
          className="min-w-0 flex-1 rounded border p-1 text-sm"
          placeholder="nearest staircase, where is the sofa…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search query"
        />
        {speechRecognitionSupported() && (
          <button
            type="button"
            className={`rounded border px-2 text-sm ${listening ? "bg-red-100" : ""}`}
            onClick={toggleVoice}
            aria-pressed={listening}
            aria-label={listening ? "Stop voice search" : "Search by voice"}
            title="Fills the search box from speech; the search itself still runs on the text"
          >
            {listening ? "●" : "🎤"}
          </button>
        )}
        {listening && (
          <button
            type="button"
            className="rounded border px-2 text-xs text-zinc-600"
            onClick={cancelVoice}
            aria-label="Cancel voice search"
          >
            Cancel
          </button>
        )}
        <button type="submit" className="rounded bg-black px-2 text-sm text-white disabled:opacity-50" disabled={busy}>
          Go
        </button>
      </form>
      <div className="mt-1 flex flex-wrap gap-x-3 text-xs text-zinc-600">
        <label className="flex items-center gap-1">
          <input type="checkbox" checked={restrictToFloor} onChange={(e) => setRestrictToFloor(e.target.checked)} disabled={!floorId} />
          This floor only
        </label>
        <label className="flex items-center gap-1">
          <input type="checkbox" checked={accessible} onChange={(e) => setAccessible(e.target.checked)} />
          Accessible only
        </label>
        <span data-testid="search-position">{position ? `From: ${position.label}` : "Position: not set"}</span>
      </div>
      {voiceNote && <p role="status" data-testid="voice-search-note" className="mt-1 text-xs text-amber-700">{voiceNote}</p>}
      {error && <p role="alert" className="mt-1 text-xs text-red-700">{error}</p>}
      {response && (
        <>
          {response.matchType === "lexical_fallback" && (
            <p className="mt-1 text-xs text-amber-700" data-testid="search-lexical-fallback">
              Semantic search is temporarily unavailable; showing text matches instead.
            </p>
          )}
          {indexNote && (
            <p className="mt-1 text-xs text-amber-700" data-testid="search-index-status">
              {indexNote}
            </p>
          )}
          {notes.length > 0 && (
            <ul className="mt-1 space-y-0.5 text-xs text-zinc-600" data-testid="search-interpretation">
              {notes.map((n) => <li key={n}>{n}</li>)}
            </ul>
          )}
          {response.lowConfidence && (
            <p className="mt-1 rounded bg-amber-50 px-2 py-1 text-xs text-amber-800" data-testid="search-low-confidence">
              Possible matches only: these were detected with low confidence or matched by appearance alone.
            </p>
          )}
          <ul className="mt-2 max-h-72 space-y-1 overflow-y-auto" data-testid="search-results">
            {response.results.map((r) => {
              const selected = r.poiId === selectedPoiId;
              const distance = distanceNote(r);
              const location = locationNote(r);
              const diag = diagnosticNote(r, response.matchType);
              return (
                <li key={r.poiId} className={`rounded border ${selected ? "border-amber-400 bg-amber-50" : ""}`}
                  data-testid="search-result" aria-current={selected ? "true" : undefined}>
                  <button className="w-full px-2 py-1 text-left text-sm hover:bg-zinc-50" onClick={() => onSelectResult(r)}>
                    <span className="font-medium">{r.label}</span>
                    <span className="ml-2 text-xs text-zinc-500">{matchNote(r, response.matchType)}</span>
                    {resultBadges(r).map((b) => (
                      <span key={b} className={`ml-1 rounded px-1 text-xs ${b === "low confidence" || b === "matched by appearance" || b === "embedding failed"
                        ? "bg-amber-50 text-amber-800" : "bg-blue-50 text-blue-700"}`}>{b}</span>
                    ))}
                    {distance && <span data-testid="search-distance" className="block text-xs text-zinc-700">{distance}</span>}
                    {location && (
                      <span data-testid="search-location-note"
                        className={`block text-xs ${r.spatialStatus && r.spatialStatus !== "VALID" ? "text-amber-700" : "text-zinc-500"}`}>
                        {location}
                      </span>
                    )}
                    <span data-testid="search-result-diagnostics" className="block text-[11px] text-zinc-400">
                      {diag}
                    </span>
                  </button>
                  {onNavigate && canNavigate(r) && (
                    <div className="px-2 pb-1">
                      <button className="rounded border bg-white px-2 py-0.5 text-xs" onClick={() => onNavigate(r)}
                        aria-label={`Navigate to ${r.label}`}>
                        Navigate
                      </button>
                    </div>
                  )}
                </li>
              );
            })}
            {empty === "No matches." && <li className="text-xs text-zinc-500">No matches.</li>}
          </ul>
          {response.results.length === 0 && (response.accessibilityUnconfirmed ?? []).length > 0 && (
            <div className="mt-2" data-testid="search-accessibility-unconfirmed">
              <p className="text-xs text-zinc-600">{empty}</p>
              <ul className="mt-1 space-y-1">
                {(response.accessibilityUnconfirmed ?? []).map((r) => (
                  <li key={r.poiId}>
                    <button className="w-full rounded border border-dashed px-2 py-1 text-left text-sm text-zinc-600 hover:bg-zinc-50"
                      onClick={() => onSelectResult(r)}>
                      {r.label} <span className="text-xs">(accessibility not recorded)</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {response.results.length === 0 && (response.accessibilityUnconfirmed ?? []).length === 0
            && (response.closestMatches ?? []).length > 0 && (
            <div className="mt-2" data-testid="search-closest">
              <p className="text-xs text-zinc-600">{empty}</p>
              <ul className="mt-1 space-y-1">
                {(response.closestMatches ?? []).map((r) => (
                  <li key={r.poiId}>
                    <button className="w-full rounded border border-dashed px-2 py-1 text-left text-sm text-zinc-600 hover:bg-zinc-50"
                      onClick={() => onSelectResult(r)}>
                      {r.label}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {(response.suggestions ?? []).length > 0 && (
            <p className="mt-2 text-xs text-zinc-600" data-testid="search-suggestions">
              Try:{" "}
              {(response.suggestions ?? []).map((s, i) => (
                <span key={s}>
                  {i > 0 && ", "}
                  <button className="underline" onClick={() => searchFor(s)}>{s}</button>
                </span>
              ))}
            </p>
          )}
        </>
      )}
    </section>
  );
}
