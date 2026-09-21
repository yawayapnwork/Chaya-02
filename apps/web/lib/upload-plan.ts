// Pure upload rules shared by the UI and its tests. They mirror the server's allowlist and limits
// for early feedback only: the server is authoritative and re-checks everything.

export type MediaKind = "VIDEO" | "IMAGE" | "METADATA";

export const ALLOWED_TYPES: Record<MediaKind, readonly string[]> = {
  VIDEO: ["video/mp4", "video/quicktime", "video/webm", "video/x-matroska"],
  IMAGE: ["image/jpeg", "image/png", "image/heic"],
  METADATA: ["application/json"],
};

export const MAX_BYTES: Record<MediaKind, number> = {
  VIDEO: 4 * 1024 ** 3,
  IMAGE: 50 * 1024 ** 2,
  METADATA: 1024 ** 2,
};

export function normalizeType(type: string): string {
  return type.split(";")[0].trim().toLowerCase();
}

export function kindForType(type: string): MediaKind | null {
  const t = normalizeType(type);
  for (const kind of Object.keys(ALLOWED_TYPES) as MediaKind[]) {
    if (ALLOWED_TYPES[kind].includes(t)) return kind;
  }
  return null;
}

/** Returns a human-readable problem, or null if the file may be sent. */
export function checkFileLocally(file: { name: string; type: string; size: number }): string | null {
  const kind = kindForType(file.type);
  if (!kind) {
    const shown = file.type === "" ? "unknown type" : file.type;
    return `${file.name}: ${shown} is not supported. Accepted: video (mp4, mov, webm, mkv), images (jpeg, png, heic) and JSON metadata.`;
  }
  if (file.size <= 0) return `${file.name}: the file is empty.`;
  if (file.size > MAX_BYTES[kind]) {
    return `${file.name}: ${formatBytes(file.size)} exceeds the ${formatBytes(MAX_BYTES[kind])} limit for ${kind.toLowerCase()} files.`;
  }
  return null;
}

export interface PartRange {
  partNumber: number; // 1-based
  start: number;
  end: number; // exclusive
}

/** Splits a file into the exact parts the server expects (all full-size except the last). */
export function planParts(size: number, partSize: number): PartRange[] {
  const parts: PartRange[] = [];
  for (let start = 0, n = 1; start < size; start += partSize, n++) {
    parts.push({ partNumber: n, start, end: Math.min(size, start + partSize) });
  }
  return parts;
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  const units = ["KiB", "MiB", "GiB", "TiB"];
  let v = n;
  let i = -1;
  do {
    v /= 1024;
    i++;
  } while (v >= 1024 && i < units.length - 1);
  return `${v.toFixed(v < 10 ? 1 : 0)} ${units[i]}`;
}

/** Maps server rejection/error codes to messages an operator can act on. */
export function explainCode(code: string | null | undefined, fallback: string): string {
  switch (code) {
    case "CHECKSUM_MISMATCH":
      return "The file arrived damaged (checksum mismatch). Upload it again.";
    case "CONTENT_TYPE_MISMATCH":
      return "The file content does not match its type. It was rejected.";
    case "UNSUPPORTED_CONTENT":
      return "The file content is not an accepted media format. It was rejected.";
    case "MALWARE_DETECTED":
      return "A malware signature was found. The file was rejected and deleted.";
    case "SCANNER_UNAVAILABLE":
      return "The malware scanner is unavailable, so the file is held in quarantine. Retry validation later.";
    case "STORAGE_UNAVAILABLE":
      return "Storage was unavailable during validation. Retry validation later.";
    case "INVALID_METADATA":
      return "The metadata file is not a valid JSON object.";
    case "SIZE_MISMATCH":
      return "The stored size differs from the file size. Upload it again.";
    default:
      return fallback;
  }
}
