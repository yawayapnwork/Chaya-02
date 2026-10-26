import { strict as assert } from "node:assert";
import { test } from "node:test";
import { classifyVoiceError, extractTranscript, speechRecognitionSupported } from "./voice-input.ts";

test("classifyVoiceError distinguishes cancellation, denied permissions, hardware/network failures and unsupported browsers", () => {
  const cancelled = classifyVoiceError("aborted");
  assert.equal(cancelled.kind, "CANCELLED");
  assert.equal(cancelled.isCancellation, true);
  assert.equal(cancelled.userMessage, null);

  const denied = classifyVoiceError("not-allowed");
  assert.equal(denied.kind, "PERMISSION_DENIED");
  assert.equal(denied.isCancellation, false);
  assert.match(denied.userMessage ?? "", /Microphone access was denied/);

  const serviceDenied = classifyVoiceError("service-not-allowed");
  assert.equal(serviceDenied.kind, "PERMISSION_DENIED");

  const noSpeech = classifyVoiceError("no-speech");
  assert.equal(noSpeech.kind, "NO_SPEECH");
  assert.match(noSpeech.userMessage ?? "", /No speech was detected/);

  const noMic = classifyVoiceError("audio-capture");
  assert.equal(noMic.kind, "AUDIO_CAPTURE");
  assert.match(noMic.userMessage ?? "", /No microphone was found/);

  const network = classifyVoiceError("network");
  assert.equal(network.kind, "NETWORK");
  assert.match(network.userMessage ?? "", /network error/);

  const unsupported = classifyVoiceError("unsupported");
  assert.equal(unsupported.kind, "UNSUPPORTED");
  assert.match(unsupported.userMessage ?? "", /not supported in this browser/);

  const unknown = classifyVoiceError("bad-grammar");
  assert.equal(unknown.kind, "FAILED");
  assert.equal(unknown.isCancellation, false);
  assert.match(unknown.userMessage ?? "", /continue searching by typing/);
});

test("extractTranscript returns trimmed speech text or null when transcription is empty", () => {
  assert.equal(extractTranscript({ results: [[{ transcript: "  nearest elevator  " }]] }), "nearest elevator");
  assert.equal(extractTranscript({ results: [[{ transcript: "   " }]] }), null);
  assert.equal(extractTranscript({ results: [] }), null);
  assert.equal(extractTranscript(null), null);
});

test("speechRecognitionSupported is false in a non-browser node environment", () => {
  assert.equal(speechRecognitionSupported(), false);
});
