"""Privacy masks: which pixels of an anonymised frame still show the scene, and which do not (review G-2).

PRIVACY_PREPROCESS replaces faces and screens/documents with pixelated, blurred or solid-filled blocks. Those blocks are
not the scene: the pixels under them are gone, and anything learned from them is learned from the anonymiser. So the
stage publishes, next to FRAME_ARCHIVE_ANON, a PRIVACY_MASKS archive with one mask per frame, and every stage that
learns from frames honours it:

  * POSE_ESTIMATION gives COLMAP the masks (eroded further, see `sfm_mask`), so no keypoint, descriptor or SfM point
    comes from a masked pixel, and so neither does a point's colour that seeds the splat;
  * SPLAT_RECONSTRUCTION zeroes the masked pixels of every training image and excludes them from L1 and D-SSIM;
  * SEMANTIC_SEGMENTATION casts no class vote from a masked pixel;
  * SEMANTIC_INDEXING drops detections that are mostly fill and associates geometry only through unmasked pixels.

A mask is a single-channel 8-bit PNG named `<frame name>.png` (COLMAP's --ImageReader.mask_path convention), the
frame's size, with 255 where the pixel is RECONSTRUCTION-VALID (exactly the captured scene, after the archive's JPEG
round trip) and 0 where it is PRIVACY-MASKED. It holds rectangles only: no pixel content, so it is not PII.

How "exactly the captured scene" is guaranteed. anonymize() rewrites only each region's `covered_box`. The anonymised
frame is then stored as JPEG, whose 4:2:0 encoding works on 16x16 MCUs and whose decoder's chroma upsampling reads one
chroma sample (two pixels) across an MCU edge. So the mask covers every MCU that touches a rewritten pixel plus one
MCU of margin all round (`codec_safe`): outside it, the decoded anonymised frame is bit-identical to the decoded
original frame (tests/orchestration/test_privacy_masks.py checks this).

Masks are not a privacy control themselves: they stop the fills from becoming scene content. What is detected and
anonymised at all is chaya_worker.privacy.detectors' job, with the limits stated there.
"""

from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np

from .. import archive
from ..errors import StageError
from .detectors import Region, covered_box

KIND = "PRIVACY_MASKS"
VALID = 255
MASKED = 0
CODEC_BLOCK = 16  # JPEG 4:2:0 MCU, in pixels


def mask_name(frame_name: str) -> str:
    return f"{frame_name}.png"


def covered(regions: list[Region], height: int, width: int) -> np.ndarray:
    """Pure: bool (H, W), True on every pixel anonymize(regions) rewrites."""
    out = np.zeros((height, width), dtype=bool)
    for r in regions:
        x0, y0, x1, y1 = covered_box(r, height, width)
        out[y0:y1, x0:x1] = True
    return out


def codec_safe(rewritten: np.ndarray, block: int = CODEC_BLOCK) -> np.ndarray:
    """Pure: grow a bool (H, W) set of rewritten pixels to every `block` x `block` cell (grid anchored at 0, 0) that
    touches it, plus one cell all round. Outside the result a JPEG round trip of the frame cannot differ from one of
    the untouched frame."""
    h, w = rewritten.shape
    gh, gw = -(-h // block), -(-w // block)
    padded = np.zeros((gh * block, gw * block), dtype=bool)
    padded[:h, :w] = rewritten
    cells = padded.reshape(gh, block, gw, block).any(axis=(1, 3)).astype(np.uint8)
    cells = cv2.dilate(cells, np.ones((3, 3), np.uint8))
    return np.kron(cells, np.ones((block, block), np.uint8)).astype(bool)[:h, :w]


def valid_mask(regions: list[Region], height: int, width: int) -> np.ndarray:
    """Pure: the uint8 mask for a frame anonymised with `regions` (VALID / MASKED)."""
    masked = codec_safe(covered(regions, height, width)) if regions else np.zeros((height, width), dtype=bool)
    return np.where(masked, MASKED, VALID).astype(np.uint8)


def write_mask(directory: Path, frame_name: str, mask: np.ndarray) -> Path:
    path = directory / mask_name(frame_name)
    ok, buf = cv2.imencode(".png", mask)
    if not ok:
        raise StageError(f"could not encode the privacy mask of {frame_name}", code="PRIVACY_WRITE_FAILED")
    path.write_bytes(buf.tobytes())
    return path


def sfm_mask(valid: np.ndarray, margin_px: int) -> np.ndarray:
    """Pure: the COLMAP feature mask, a bool valid mask with its masked area grown by `margin_px` so that a SIFT
    keypoint's descriptor window never reaches a masked pixel. Returned as uint8 VALID / MASKED."""
    masked = ~valid
    if margin_px > 0 and masked.any():
        k = 2 * margin_px + 1
        masked = cv2.dilate(masked.astype(np.uint8), np.ones((k, k), np.uint8)).astype(bool)
    return np.where(masked, MASKED, VALID).astype(np.uint8)


class FrameMasks:
    """The PRIVACY_MASKS of one run, unpacked. `valid(frame_name, shape)` is the bool (H, W) reconstruction-valid mask
    of a frame; a missing, unreadable, wrongly sized or non-binary mask fails the stage (fail closed: an unmasked frame
    would train its fills)."""

    def __init__(self, directory: Path) -> None:
        self.directory = directory

    def valid(self, frame_name: str, shape: tuple[int, int]) -> np.ndarray:
        path = self.directory / mask_name(frame_name)
        if not path.is_file():
            raise StageError(f"frame {frame_name} has no privacy mask", code="PRIVACY_MASK_INVALID",
                             details={"frame": frame_name})
        mask = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_UNCHANGED)
        if mask is None or mask.ndim != 2 or mask.dtype != np.uint8:
            raise StageError(f"the privacy mask of {frame_name} is not a single-channel 8-bit image",
                             code="PRIVACY_MASK_INVALID", details={"frame": frame_name})
        if mask.shape != tuple(shape):
            raise StageError(f"the privacy mask of {frame_name} is {mask.shape[1]}x{mask.shape[0]}, the frame is "
                             f"{shape[1]}x{shape[0]}", code="PRIVACY_MASK_INVALID", details={"frame": frame_name})
        if not np.isin(mask, (VALID, MASKED)).all():
            raise StageError(f"the privacy mask of {frame_name} is not binary", code="PRIVACY_MASK_INVALID",
                             details={"frame": frame_name})
        return mask == VALID


def from_inputs(ctx) -> FrameMasks | None:
    """The run's PRIVACY_MASKS, unpacked into the stage's workdir. In a privacy-enabled run they are required: every
    frame a later stage reads was anonymised, and without its mask the fills would be taken for the scene."""
    inputs = ctx.inputs_of(KIND)
    if inputs:
        directory = ctx.workdir / "privacy-masks"
        archive.unpack(inputs[0].path, directory)
        return FrameMasks(directory)
    if ctx.order.get("privacyEnabled"):
        raise StageError(f"{ctx.stage} reads anonymised frames but no PRIVACY_MASKS were provided; without them privacy "
                         "fills would be learned as scene content. Re-run PRIVACY_PREPROCESS.", code="PRIVACY_MASKS_MISSING")
    return None
