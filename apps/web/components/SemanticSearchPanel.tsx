"use client";

import { useEffect, useRef, useState } from "react";
import { createSpeechRecognizer, speechRecognitionSupported } from "@/lib/voice-input";
import { type SearchResponse, type SearchResult, searchVenue } from "@/lib/search-api";

interface SemanticSearchPanelProps {
  venueId: string;
  /** The currently viewed floor, used only for the "this floor only" filter's target -- results can span
   * every floor of the venue unless that filter is checked. */
  floorId: string;
  onSelectResult: (result: SearchResult) => void;
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** Natural-language + voice object search. Voice input (lib/voice-input.ts) only ever fills the text box
 * below; the actual ranking always goes through searchVenue, which is backed by real CLIP embeddings on
 * the server (dev.chaya.api.search), never by matching the spoken words directly. */
export default function SemanticSearchPanel({ venueId, floorId, onSelectResult }: SemanticSearchPanelProps) {
  const [query, setQuery] = useState("");
  const [restrictToFloor, setRestrictToFloor] = useState(false);
  const [accessible, setAccessible] = useState(false);
  const [response, setResponse] = useState<SearchResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [listening, setListening] = useState(false);
  const recognizerRef = useRef<ReturnType<typeof createSpeechRecognizer>>(null);

  async function runSearch(text: string) {
    if (!text.trim()) return;
    setBusy(true);
    setError(null);
    try {
      setResponse(await searchVenue(venueId, text, { floorId: restrictToFloor ? floorId : undefined, accessible: accessible || undefined }));
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

  function toggleVoice() {
    if (listening) {
      recognizerRef.current?.stop();
      return;
    }
    const recognizer = createSpeechRecognizer();
    if (!recognizer) {
      setError("Voice search is not supported in this browser.");
      return;
    }
    recognizerRef.current = recognizer;
    recognizer.onresult = (event) => {
      const transcript = event.results[0]?.[0]?.transcript;
      if (transcript) {
        setQuery(transcript);
        runSearch(transcript);
      }
    };
    recognizer.onerror = (event) => {
      setError(`Voice input error: ${event.error}`);
      setListening(false);
    };
    recognizer.onend = () => setListening(false);
    setListening(true);
    recognizer.start();
  }

  useEffect(() => {
    const recognizer = recognizerRef.current;
    return () => recognizer?.abort();
  }, []);

  return (
    <section>
      <h2 className="font-medium">Search</h2>
      <form onSubmit={onSubmit} className="mt-1 flex gap-1">
        <input
          className="min-w-0 flex-1 rounded border p-1 text-sm"
          placeholder="couch, exit sign, reception desk…"
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
        <button type="submit" className="rounded bg-black px-2 text-sm text-white disabled:opacity-50" disabled={busy}>
          Go
        </button>
      </form>
      <div className="mt-1 flex gap-3 text-xs text-zinc-600">
        <label className="flex items-center gap-1">
          <input type="checkbox" checked={restrictToFloor} onChange={(e) => setRestrictToFloor(e.target.checked)} disabled={!floorId} />
          This floor only
        </label>
        <label className="flex items-center gap-1">
          <input type="checkbox" checked={accessible} onChange={(e) => setAccessible(e.target.checked)} />
          Accessible only
        </label>
      </div>
      {error && <p role="alert" className="mt-1 text-xs text-red-700">{error}</p>}
      {response && (
        <>
          {response.matchType === "lexical_fallback" && (
            <p className="mt-1 text-xs text-amber-700">Semantic search is temporarily unavailable; showing text matches instead.</p>
          )}
          <ul className="mt-2 max-h-64 space-y-1 overflow-y-auto" data-testid="search-results">
            {response.results.map((r) => (
              <li key={r.poiId}>
                <button className="w-full rounded border px-2 py-1 text-left text-sm hover:bg-zinc-50" onClick={() => onSelectResult(r)}>
                  <span className="font-medium">{r.label}</span>
                  <span className="ml-2 text-xs text-zinc-500">{Math.round(r.similarity * 100)}% match</span>
                  {r.source === "AUTO_DETECTED" && (
                    <span className="ml-2 rounded bg-blue-50 px-1 text-xs text-blue-700">detected</span>
                  )}
                </button>
              </li>
            ))}
            {response.results.length === 0 && (response.closestMatches ?? []).length === 0 && (
              <li className="text-xs text-zinc-500">No matches.</li>
            )}
          </ul>
          {response.results.length === 0 && (response.closestMatches ?? []).length > 0 && (
            <div className="mt-2" data-testid="search-closest">
              <p className="text-xs text-zinc-600">Nothing matching &ldquo;{response.query}&rdquo; here. Closest:</p>
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
        </>
      )}
    </section>
  );
}
