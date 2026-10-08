// The .ksplat contract (packages/contracts/viewer/ksplat-contract.json): what the pinned @mkkellogg/gaussian-splats-3d
// 0.4.7 reads (SplatBuffer.parseHeader / parseSectionHeaders, KSplatLoader.checkVersion), narrowed to the one layout the
// worker writes and ksplat-compat.test.ts proves. The library checks only the version bytes: a truncated file makes
// it read past the end (a RangeError thrown from inside its timer, which no promise reports) and trailing or
// miscounted data renders as garbage. So the viewer checks the headers itself, before the library sees the bytes. The
// worker (chaya_worker.ksplat.validate) and the API (KsplatValidator) apply the same rules before publishing.
// Pure: no DOM, no Next modules, so node --test runs it.

export const KSPLAT_CONTRACT = {
  library: "@mkkellogg/gaussian-splats-3d",
  libraryVersion: "0.4.7",
  versionMajor: 0,
  versionMinor: 1,
  fileHeaderBytes: 4096,
  sectionHeaderBytes: 1024,
  compressionLevel: 0,
  sphericalHarmonicsDegree: 0,
  bytesPerSplat: 44,
  sectionCount: 1,
  minSplatCount: 1,
  /** 512 MiB, about 12.2 million splats: more than a browser tab can hold as download, parsed buffer and GPU textures. */
  maxBytes: 512 * 1024 * 1024,
} as const;

const C = KSPLAT_CONTRACT;
const HEADERS = C.fileHeaderBytes + C.sectionHeaderBytes * C.sectionCount;

export type KsplatInvalidReason =
  | "TRUNCATED"
  | "TRAILING_BYTES"
  | "TOO_LARGE"
  | "UNSUPPORTED_VERSION"
  | "UNSUPPORTED_LAYOUT"
  | "UNSUPPORTED_COMPRESSION"
  | "UNSUPPORTED_SH_DEGREE"
  | "INCONSISTENT_COUNTS"
  | "EMPTY";

export class KsplatInvalidError extends Error {
  readonly reason: KsplatInvalidReason;

  constructor(reason: KsplatInvalidReason, detail: string) {
    super(`The reconstruction file is not a valid viewer asset (${reason}): ${detail}`);
    this.name = "KsplatInvalidError";
    this.reason = reason;
  }
}

/** What a valid file's headers say; the same shape the API stores and returns as ArtifactRef.format. */
export interface KsplatInfo {
  format: "ksplat";
  contract: string;
  version: string;
  compressionLevel: number;
  sphericalHarmonicsDegree: number;
  sectionCount: number;
  splatCount: number;
}

/** The smallest file the contract allows, and the largest. */
export const MIN_KSPLAT_BYTES = HEADERS + C.bytesPerSplat * C.minSplatCount;

/** Refuses a size no valid file has, before downloading anything. */
export function checkKsplatSize(sizeBytes: number): void {
  if (!Number.isSafeInteger(sizeBytes) || sizeBytes < MIN_KSPLAT_BYTES) {
    throw new KsplatInvalidError("TRUNCATED", `${sizeBytes} bytes is shorter than the headers and one splat`);
  }
  if (sizeBytes > C.maxBytes) {
    throw new KsplatInvalidError("TOO_LARGE", `${sizeBytes} bytes; the limit is ${C.maxBytes}`);
  }
}

/** Validates a whole downloaded file against the contract. Throws KsplatInvalidError; returns what its headers say. */
export function validateKsplat(buffer: ArrayBuffer): KsplatInfo {
  const size = buffer.byteLength;
  checkKsplatSize(size);
  const h = new DataView(buffer, 0, HEADERS);
  const u32 = (o: number) => h.getUint32(o, true);
  const u16 = (o: number) => h.getUint16(o, true);
  const major = h.getUint8(0);
  const minor = h.getUint8(1);
  if (major !== C.versionMajor || minor !== C.versionMinor) {
    throw new KsplatInvalidError("UNSUPPORTED_VERSION", `version ${major}.${minor}; only ${C.versionMajor}.${C.versionMinor}`);
  }
  if (u32(4) !== C.sectionCount || u32(8) !== C.sectionCount) {
    throw new KsplatInvalidError("UNSUPPORTED_LAYOUT", `${u32(4)} section slots, ${u32(8)} sections; only ${C.sectionCount}`);
  }
  if (u16(20) !== C.compressionLevel) {
    throw new KsplatInvalidError("UNSUPPORTED_COMPRESSION", `compression level ${u16(20)}; only ${C.compressionLevel}`);
  }
  const s = C.fileHeaderBytes;
  if (u16(s + 40) !== C.sphericalHarmonicsDegree) {
    throw new KsplatInvalidError("UNSUPPORTED_SH_DEGREE", `spherical-harmonics degree ${u16(s + 40)}; only ${C.sphericalHarmonicsDegree}`);
  }
  // Level 0 has no position buckets; any bucket storage would move where the library reads the splats from.
  if (u32(s + 8) || u32(s + 12) || u16(s + 20) || u32(s + 32) || u32(s + 36)) {
    throw new KsplatInvalidError("UNSUPPORTED_LAYOUT", "bucket fields are set; compression level 0 has no buckets");
  }
  const counts = new Set([u32(12), u32(16), u32(s), u32(s + 4)]);
  if (counts.size !== 1) {
    throw new KsplatInvalidError("INCONSISTENT_COUNTS", `splat counts disagree: ${[...counts].join(", ")}`);
  }
  const n = u32(16);
  if (n < C.minSplatCount) throw new KsplatInvalidError("EMPTY", "the file has no splats");
  if (u32(s + 28) !== n * C.bytesPerSplat) {
    throw new KsplatInvalidError("INCONSISTENT_COUNTS", `section storage size ${u32(s + 28)} is not ${n} x ${C.bytesPerSplat}`);
  }
  const expected = HEADERS + n * C.bytesPerSplat;
  if (size < expected) throw new KsplatInvalidError("TRUNCATED", `${size} bytes; ${n} splats need ${expected}`);
  if (size > expected) throw new KsplatInvalidError("TRAILING_BYTES", `${size} bytes; ${n} splats need exactly ${expected}`);
  return {
    format: "ksplat",
    contract: `${C.library}@${C.libraryVersion}`,
    version: `${major}.${minor}`,
    compressionLevel: C.compressionLevel,
    sphericalHarmonicsDegree: C.sphericalHarmonicsDegree,
    sectionCount: C.sectionCount,
    splatCount: n,
  };
}
