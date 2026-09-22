// Thin wrapper around the browser's Web Speech API (SpeechRecognition). This module only ever produces a
// text string from speech; it never interprets, matches or ranks anything itself. The text it produces is
// fed into the exact same search call a typed query would use (lib/search-api.ts's searchVenue, backed by
// CLIP embeddings on the server) -- voice is an input method, never a second search engine.

export function speechRecognitionSupported(): boolean {
  return typeof window !== "undefined" && !!(window.SpeechRecognition || window.webkitSpeechRecognition);
}

export function createSpeechRecognizer(lang = "en-US"): SpeechRecognitionLike | null {
  if (typeof window === "undefined") return null;
  const Ctor = window.SpeechRecognition ?? window.webkitSpeechRecognition;
  if (!Ctor) return null;
  const recognizer = new Ctor();
  recognizer.lang = lang;
  recognizer.continuous = false;
  recognizer.interimResults = false;
  recognizer.maxAlternatives = 1;
  return recognizer;
}
