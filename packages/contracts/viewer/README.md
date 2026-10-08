# Viewer artifact contract

`ksplat-contract.json` defines the `.ksplat` files that Chaya publishes and that its web viewer loads. It is derived from the
reader of the pinned viewer library, `@mkkellogg/gaussian-splats-3d` **0.4.7** (`build/gaussian-splats-3d.module.js`:
`SplatBuffer.parseHeader`, `parseSectionHeaders`, `calculateComponentStorage`, `KSplatLoader.checkVersion`). It is
narrowed to the one layout the worker writes (`chaya_worker/ksplat.py`), which `apps/web/lib/ksplat-compat.test.ts`
proves loads correctly in that library.

The library itself checks only the two version bytes. Run against the committed fixture, it silently accepts:

- a file that is one byte short;
- trailing bytes;
- file and section splat counts that disagree;
- version 0.2.

A file missing a whole splat, or with a level-1 header on level-0 data, makes it throw from inside a timer, where no
promise reports the error (`apps/web/lib/ksplat-validation.test.ts` shows each case). The rules below are therefore enforced three times, with the same reasons:

| Where | When | Code | On failure |
|---|---|---|---|
| Worker, `chaya_worker.ksplat.validate` | ARTIFACT_GENERATION, before upload; the size limit is checked before encoding | `KSPLAT_TOO_LARGE`, `KSPLAT_INVALID` (stage error) | Stage FAILED; nothing uploaded |
| API, `dev.chaya.api.pipeline.KsplatValidator` | Stage report, on the sealed copy after its size and SHA-256 are checked; the size limit is checked before copying | 409 `ARTIFACT_TOO_LARGE`, 409 `KSPLAT_INVALID` | Report refused; nothing registered; sealed copy deleted |
| Viewer, `apps/web/lib/ksplat-validation.ts` + `artifact-integrity.ts` | Before downloading, while downloading, and before handing bytes to GaussianSplats3D | `KsplatInvalidError`, `ArtifactIntegrityError` | Error on screen; nothing rendered |

Each of the three has a test asserting that its constants equal `ksplat-contract.json`.

## Format rules (all multi-byte values little-endian)

| # | Rule | Reason code |
|---|---|---|
| 1 | Size ≤ 536,870,912 bytes (512 MiB, about 12.2 million splats). | `TOO_LARGE` (API: `ARTIFACT_TOO_LARGE`) |
| 2 | Size ≥ 4096 + 1024 + 44 = 5,164 bytes (headers and one splat). | `TRUNCATED` |
| 3 | Version bytes `[0]`, `[1]` are exactly `0`, `1`. The library would also accept 0.2+ and 1.x; their layout is unproven. | `UNSUPPORTED_VERSION` |
| 4 | `maxSectionCount` (uint32 @ 4) = `sectionCount` (uint32 @ 8) = 1. | `UNSUPPORTED_LAYOUT` |
| 5 | `compressionLevel` (uint16 @ 20) = 0. | `UNSUPPORTED_COMPRESSION` |
| 6 | Section spherical-harmonics degree (uint16 @ 4096 + 40) = 0. | `UNSUPPORTED_SH_DEGREE` |
| 7 | Section bucket fields are all 0: bucketSize @ 4096 + 8, bucketCount @ +12, bucketStorageSizeBytes (uint16) @ +20, fullBucketCount @ +32, partiallyFilledBucketCount @ +36. Level 0 has no buckets, and any bucket storage moves where the library reads splats from. | `UNSUPPORTED_LAYOUT` |
| 8 | File `maxSplatCount` (@ 12) = file `splatCount` (@ 16) = section `splatCount` (@ 4096) = section `maxSplatCount` (@ 4096 + 4) = N. | `INCONSISTENT_COUNTS` |
| 9 | N ≥ 1. | `EMPTY` |
| 10 | Section storage size (uint32 @ 4096 + 28) = N × 44. | `INCONSISTENT_COUNTS` |
| 11 | Size = 5,120 + N × 44 exactly. | `TRUNCATED` if shorter, `TRAILING_BYTES` if longer |

## Integrity rules (viewer, `fetchArtifact` → `verifyArtifact`)

1. `ArtifactRef.sha256` must be 64 lowercase hex characters, and `sizeBytes` must pass format rules 1–2. This is checked
   before any request is made.
2. `Content-Length`, when present and the response has no `Content-Encoding`, must equal `sizeBytes`. A mismatch cancels
   the response unread.
3. The body is read into a buffer of exactly `sizeBytes` bytes. Any byte beyond that cuts the download off, and a body
   that ends short is refused (`SIZE_MISMATCH`).
4. The SHA-256 of the bytes must equal `sha256` (`CHECKSUM_MISMATCH`), computed with Web Crypto. Outside a secure
   context Web Crypto is missing, and the viewer refuses to load (`INTEGRITY_UNAVAILABLE`).
5. Format rules 1–11.
6. When the API recorded a format (`ArtifactRef.format`, for every KSPLAT published since V31), the file's headers must
   say the same thing: version, compression, SH degree, sections and splat count (`FORMAT_MISMATCH`).

Only bytes that pass all six become the Blob that GaussianSplats3D loads.

## Scope rules (viewer, `checkSceneScope`, before anything is downloaded)

1. The returned reconstruction is the run that was requested.
2. Its `scanVersionId` is the one the version list named for that run.
3. It has exactly one KSPLAT. For a scan version its URL is `/api/v1/venues/{venue}/scan-versions/{version}/artifacts/KSPLAT`,
   the asset that version pinned, never the run's current one. Otherwise it is
   `/api/v1/venues/{venue}/reconstructions/{run}/artifacts/KSPLAT`.
4. A scan version always has its recorded coordinate frame.
5. The frame's `floorId`, when set, is the scene's floor.
6. A canonical frame has a finite, positive scale, a unit rotation (within 1e-3) and a finite translation.

A scene that fails any of these is not shown (`OUT_OF_SCOPE`).

## Stored with the published artifact

`processing_artifact` keeps `checksum_sha256` and `size_bytes`; both were already verified against storage when the
artifact was sealed, and are verified again on every read (`VerifyingInputStream`). Since V31 it also keeps
`format_metadata`, which records what the validated headers say:

```json
{"format": "ksplat", "contract": "@mkkellogg/gaussian-splats-3d@0.4.7", "version": "0.1", "compressionLevel": 0,
 "sphericalHarmonicsDegree": 0, "sectionCount": 1, "splatCount": 3701}
```

A CHECK constraint (`processing_artifact_ksplat_validated`, `NOT VALID`) refuses any new KSPLAT row without this
metadata. KSPLATs published before V31 have none; they are still served, and the viewer still validates their headers.
The worker's `manifest.json` records the same object for the `.ksplat` it generated.

## Changing the contract

The contract is tied to one library version, and `package.json` pins that version exactly (no range). To upgrade the
library or the format:

1. Read the new version's reader.
2. Update the encoder.
3. Regenerate the fixtures (`packages/contracts/fixtures/README.md`).
4. Update this JSON and the three validators.
5. Re-run `ksplat-compat.test.ts`, `ksplat-validation.test.ts`, `KsplatValidatorTest`, `test_ksplat_validation.py`
   and the Playwright `ksplat-viewer.spec.ts`.
