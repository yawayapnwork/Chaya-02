// Minimal ambient types for the browser Web Speech API (SpeechRecognition). Not part of TypeScript's DOM
// lib and not shipped by any @types package we depend on; only the surface SemanticSearchPanel.tsx
// actually uses is declared here. Support is Chrome/Edge/Safari-prefixed (webkitSpeechRecognition); see
// lib/voice-search.ts for the feature-detection that picks whichever constructor exists.

interface SpeechRecognitionResultLike {
  readonly [index: number]: { readonly transcript: string };
  readonly isFinal: boolean;
}

interface SpeechRecognitionEventLike extends Event {
  readonly results: ArrayLike<SpeechRecognitionResultLike>;
}

interface SpeechRecognitionErrorEventLike extends Event {
  readonly error: string;
}

interface SpeechRecognitionLike extends EventTarget {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  maxAlternatives: number;
  start(): void;
  stop(): void;
  abort(): void;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onerror: ((event: SpeechRecognitionErrorEventLike) => void) | null;
  onend: (() => void) | null;
}

interface Window {
  SpeechRecognition?: new () => SpeechRecognitionLike;
  webkitSpeechRecognition?: new () => SpeechRecognitionLike;
}
