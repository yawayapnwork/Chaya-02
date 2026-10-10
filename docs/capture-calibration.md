# Capture calibration evidence

How an operator records the physical measurements that give a capture's reconstruction real-world scale, and how
those measurements become a metric coordinate frame. The frame model, the math and the downstream gates are described in
[coordinate-frames.md](coordinate-frames.md). This document covers what happens at capture time (migration V32).

**Status:** implemented and tested only with synthetic data (see [Validation](#validation)). **No real indoor capture
has been calibrated this way.**

## Why

A COLMAP/GLOMAP reconstruction has an arbitrary scale, rotation and origin. The only way to metres is a physical
measurement made on site. Before V32, such measurements existed only as numbers typed into a calibration request after
reconstruction. Nothing tied them to the capture, the floor, the unit they were measured in, or the images the measured
points appear in.

## Flow

```
create capture (with a floor) -> upload media -> record measurements -> finish upload -> processing
                                     |                                                     |
                                     |   each measured point marked in >= 2 images/frames   v
                                     +------------------------------------------>  POSE_ESTIMATION (arbitrary scale)
                                                                                           |
                       operator locates each measured point in the reconstruction          v
                       POST .../captures/{id}/calibration  ------------------->  coordinate_frame (METRIC)
```

Measurements can be recorded once the capture has accepted media, and until it is calibrated (recalibration is
allowed later). A `FAILED` capture accepts no evidence. Recording does not require the upload to be finished.

## What the operator records

Two kinds of evidence are accepted. Either of them is sufficient.

| Kind | What | Needed |
|---|---|---|
| `DISTANCE` | A physical length between two marked points A and B, with a tape or laser meter (a door width, a wall, the distance between two floor marks) | at least `chaya.frames.min-distance-references` (2) |
| `CONTROL_POINT` | One physical point P with surveyed venue coordinates x, y, z (+Z up) in a named survey datum | at least 3, not on one line |

Every measurement carries:

- `label` (unique among the capture's active measurements), and `method` (`TAPE`, `LASER_DISTANCE_METER`,
  `TOTAL_STATION`, `SURVEY_PLAN`, `OTHER`; this is stated by the operator and not verified);
- `unit` (`m`, `cm`, `mm`, `ft`, `in`). The value is stored exactly as entered **and** in metres. A unit is never
  assumed;
- optional `uncertainty`, in the same unit;
- **observations**: each point (A and B, or P) marked as a pixel in at least **two different views**. A view is one
  accepted image, or one frame (`frameTimeSeconds`) of one accepted video **of the same capture**. Pixel (0, 0) is the
  top-left corner of the top-left pixel.

The floor is the capture's floor (a database trigger enforces this). A capture created without a floor cannot take
calibration evidence (`409 FLOOR_REQUIRED`).

### Refusals

Every refusal is a problem+json with a stable `code`. Nothing is stored when a request is refused.

| Code | Status | Cause |
|---|---|---|
| `MISSING_UNIT`, `INVALID_UNIT` | 400 | no unit, or one that is not supported |
| `MISSING_MEASUREMENT`, `MISSING_DATUM` | 400 | no value (distance), or no venue coordinates or datum (control point) |
| `INVALID_MEASUREMENT` | 400 | non-positive or non-finite value, bad label or method, bad uncertainty |
| `INCONSISTENT_MEASUREMENT` | 400 | a distance with venue coordinates, or a control point with a value |
| `MEASUREMENT_OUT_OF_RANGE` | 400 | a distance outside 0.05–200 m, or a control point further than 100 km from its datum origin (usually a unit mistake) |
| `MEASUREMENT_TOO_UNCERTAIN` | 400 | a stated uncertainty larger than `max-scale-relative-spread` (3 %) of the distance |
| `INCONSISTENT_UNITS`, `INCONSISTENT_DATUM` | 400 | a control point in a different unit or datum from the capture's other control points |
| `DEGENERATE_GEOMETRY` | 400 | A and B marked at the same pixel of one view, or a control point within 0.1 m of another |
| `INSUFFICIENT_OBSERVATIONS` | 400 | a point seen in fewer than two different views |
| `INVALID_OBSERVATION` | 400 | wrong point name, the same view twice, a pixel outside the image, a video mark without a frame time, or an image mark with one |
| `MEDIA_NOT_OBSERVABLE` | 422 | the media is not an `ACCEPTED` image or video of this capture |
| `DUPLICATE_MEASUREMENT` | 409 | the label is already used by an active measurement |
| `CAPTURE_NOT_OWNED` | 403 | not the capture's operator, a venue manager, or an admin |

Image bounds are checked against the size the server reads from an accepted image's own header
(`capture_media.pixel_width/pixel_height`). That size is `null` for videos and HEIC images, so pixels marked in them are
not bounds-checked.

Measurements are immutable. To correct one, withdraw it (`POST .../measurements/{id}/withdraw` with a reason) and record
a new one. A measurement that the active coordinate frame was computed from cannot be withdrawn
(`409 MEASUREMENT_IN_USE`) until the reconstruction is recalibrated without it.

## Status: `GET .../captures/{id}/calibration`

| Field | Values |
|---|---|
| `captureStage` | `INCOMPLETE_CAPTURE` (CREATED, UPLOADING), `VALIDATING_UPLOAD`, `MEDIA_UPLOADED` (READY_FOR_PROCESSING), `PROCESSING`, `COMPLETED`, `FAILED` |
| `state` | `NO_EVIDENCE`, `EVIDENCE_INCOMPLETE`, `AWAITING_RECONSTRUCTION`, `READY_TO_CALIBRATE` (the UI shows all four as *calibration pending*), `CALIBRATED`, `REJECTED` |
| `reconstructionFrame` | `NO_RECONSTRUCTION`, `ARBITRARY_SCALE`, `METRIC_NOT_ALIGNED`, `CANONICAL` |
| `requirements` | what the operator must still provide, in words |

`CALIBRATED` means that the run's ACTIVE coordinate frame is `METRIC`. That frame may come from this capture's
evidence, or from the reconstruction calibration endpoint. `REJECTED` means that the last calibration attempt was
refused and no metric frame exists. Recording evidence never changes `reconstructionFrame`: until a calibration is
accepted, the reconstruction stays `ARBITRARY_SCALE`. The next metric stage's work order carries no frame and fails with
`NOT_CALIBRATED` ([coordinate-frames.md §6](coordinate-frames.md#6-failure-behaviour-without-calibration)).

## Calibration: `POST .../captures/{id}/calibration`

```json
{"resolvedPoints": [{"measurementId": "…", "point": "A", "reconstruction": [x, y, z]}, …],
 "gravity": {"source": "RECONSTRUCTED_FLOOR_PLANE"}, "note": "…"}
```

- **The operator must supply the reconstruction coordinates of every measured point used.** Locating the marked pixels
  in the reconstruction automatically is not implemented. That would mean triangulating them through the reconstructed
  camera poses, and mapping video frame times onto the extracted frame names in `poses.json`. The recorded observations
  are what such a step would consume. Until it exists, `requirements` names each point that is still needed.
- Only measurements named in `resolvedPoints` are used. Each one needs all its points (A and B, or P); otherwise the
  request is refused with `400 UNRESOLVED_POINTS`.
- The measured lengths and venue coordinates come from the recorded measurements, never from the request.
- The request is evaluated by the existing calibration (`CoordinateFrameService`): its checks, tolerances and errors
  (`INVALID_CALIBRATION`, `CALIBRATION_INCONSISTENT`, `GRAVITY_UNAVAILABLE`) apply unchanged.
- Every attempt is recorded in `capture_calibration_attempt`, which is append-only. Refused attempts are recorded too.
  An accepted frame is linked to its measurements in `coordinate_frame_measurement`.

## Data model (V32)

| Table | What |
|---|---|
| `capture_measurement` | one distance or control point: capture, floor, kind, label, method, unit, value as entered and in metres, datum, uncertainty, status (`ACTIVE` → `WITHDRAWN` only), who and when |
| `capture_measurement_observation` | point, media (composite FK: same capture), frame time, pixel u/v; immutable |
| `capture_calibration_attempt` | run, outcome, frame or error code and message, measurement ids; append-only |
| `coordinate_frame_measurement` | frame ↔ measurement; append-only |
| `capture_media.pixel_width/height` | an accepted image's decoded size from its header, or `null` |

Every table carries `organization_id` and `venue_id`, with a composite foreign key to `venue`
(`TenantSchemaInvariantTest`). Every read and write first goes through `TenantGuard` and the capture lookup, so another
organization or venue gets a 404. The immutability guards yield only to a data erasure: erasing a capture erases its
evidence, its attempts and its links ([privacy-erasure.md](privacy-erasure.md)).

## Device and camera metadata

The web client records what the browser reports: user agent, platform, language, screen, device pixel ratio, hardware
concurrency, and, where the browser exposes them, `deviceMemory` and the user-agent client hints (model, platform
version). Camera intrinsics, lens distortion and sensor capabilities are **not** observable from a browser, so they
are not recorded. An operator who knows the intrinsics uploads them as a `cameraCalibration` metadata file
([capture-ingestion.md](capture-ingestion.md)).

## Web UI (`/capture`, `CalibrationPanel`)

- Two badges: the capture stage (incomplete capture / checking upload / media uploaded …) and the calibration state
  (calibration pending / calibrated / calibration rejected). The reconstruction frame and the server's `requirements`
  are shown below them.
- **Recording a measurement.** The operator chooses the kind, label, method and unit, then enters the value or the
  venue coordinates.
  - Points are marked by clicking a local copy of an image or video uploaded from the page. For a video, the click
    records the current frame time.
  - For media uploaded elsewhere, the operator types the pixel coordinates.
- **Calibrating.** The form takes `x y z` reconstruction coordinates for each point and a gravity source.
- **Resuming after a reload.** "Continue an unfinished capture" lists the venue's open captures. Re-selecting a file
  that is partly on the server resumes it: the file is matched by SHA-256 and size, and only missing parts are sent.

## Validation

The following ran on 2026-10-10, **with synthetic data only**:

- **Backend.** `CaptureCalibrationTest` (7 tests) and the new resume test in `CaptureIngestionTest` ran against real
  PostgreSQL and MinIO in Testcontainers.
  - The media are generated 16×16 PNGs and an MP4-signature container fixture, not real footage.
  - The pixel marks are arbitrary positions.
  - The measured values and reconstruction coordinates come from
    `packages/contracts/fixtures/synthetic-calibration.json`, a mathematical fixture.
  - The "reconstruction" is a pipeline that the test drives as a worker would.
- **Web.** `lib/calibration.test.ts` and `lib/upload-plan.test.ts` (resume matching) use made-up drafts.

The following still needs a real indoor capture:

- measuring real distances or control points on site and marking them in real photos or video frames;
- locating those points in a real COLMAP/GLOMAP reconstruction and checking that the resulting scale agrees with
  independent check distances;
- the pixel picker on real phone images and videos in a browser (the UI is lint-, type- and unit-checked only; it
  has not been run in a browser);
- whether 0.05–200 m, 0.1 m and 0.5 m are the right indoor limits.
