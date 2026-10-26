// Thin wrapper around the browser's Web Speech API (SpeechRecognition). This module only ever produces a
// text string from speech; it never interprets, matches or ranks anything itself. The text it produces is
// fed into the exact same search call a typed query would use (lib/search-api.ts's searchVenue, backed by
// CLIP embeddings on the server) -- voice is an input method, never a second search engine.

export type VoiceErrorKind =
  | "UNSUPPORTED"
  | "PERMISSION_DENIED"
  | "CANCELLED"
  | "NO_SPEECH"
  | "AUDIO_CAPTURE"
  | "NETWORK"
  | "FAILED";

export interface VoiceErrorDisposition {
  kind: VoiceErrorKind;
  /** User-facing message, or null when the event was an intentional cancellation. */
  userMessage: string | null;
  isCancellation: boolean;
}

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

/**
 * Classifies a Web Speech API error code (or start failure) so the search panel can distinguish
 * intentional cancellation ("aborted") from denied microphone permissions, missing hardware,
 * network transcription failures, and unsupported browsers without disrupting typed search.
 */
export function classifyVoiceError(error: string | undefined): VoiceErrorDisposition {
  const code = (error ?? "").trim().toLowerCase();
  if (code === "aborted") {
    return { kind: "CANCELLED", userMessage: null, isCancellation: true };
  }
  if (code === "not-allowed" || code === "service-not-allowed") {
    return {
      kind: "PERMISSION_DENIED",
      userMessage: "Microphone access was denied. You can continue searching by typing.",
      isCancellation: false,
    };
  }
  if (code === "no-speech") {
    return {
      kind: "NO_SPEECH",
      userMessage: "No speech was detected. Try speaking again or type your search.",
      isCancellation: false,
    };
  }
  if (code === "audio-capture") {
    return {
      kind: "AUDIO_CAPTURE",
      userMessage: "No microphone was found. You can continue searching by typing.",
      isCancellation: false,
    };
  }
  if (code === "network") {
    return {
      kind: "NETWORK",
      userMessage: "Voice transcription failed due to a network error. Type your search instead.",
      isCancellation: false,
    };
  }
  if (code === "unsupported") {
    return {
      kind: "UNSUPPORTED",
      userMessage: "Voice search is not supported in this browser. You can search by typing.",
      isCancellation: false,
    };
  }
  return {
    kind: "FAILED",
    userMessage: "Voice transcription failed. You can continue searching by typing.",
    isCancellation: false,
  };
}

/** Extracts a non-empty trimmed transcript from a SpeechRecognition result event, or null if transcription produced no text. */
export function extractTranscript(
  event: { readonly results?: ArrayLike<{ readonly [index: number]: { readonly transcript?: string } }> } | null | undefined,
): string | null {
  const raw = event?.results?.[0]?.[0]?.transcript;
  if (typeof raw !== "string") return null;
  const trimmed = raw.trim();
  return trimmed.length > 0 ? trimmed : null;
}
