"""A small, valid posed dataset for the GPU acceptance test (tests/gpu/test_splat_acceptance.py), in exactly the form
POSE_ESTIMATION publishes it: SPARSE_MODEL (a binary COLMAP model), POSES (poses.json), FRAME_ARCHIVE_ANON and PRIVACY_MASKS.

Two sources:
  * `from_colmap_project(dir)`: a REAL COLMAP project (`images/` and `sparse/0/*.bin`), e.g. a real indoor capture
    reconstructed with COLMAP. Selected with CHAYA_SPLAT_DATASET.
  * `rendered_room_corner()`: a SYNTHETIC scene, labelled as such. It is a textured floor and two walls, ray-traced here
    with exact pinhole cameras (no gsplat involved, so training is not fitted to its own renderer). The SfM points are
    surface samples with their true colours, and their tracks are the views that really see them (depth-tested).
    It shows that gsplat training runs and learns on CUDA. It says nothing about reconstruction quality on a real venue.

The binary model follows COLMAP's documented format (src/colmap/scene/reconstruction_io.cc; scripts/python/read_write_model.py),
and the stage converts it with the real `colmap model_converter`, which validates it.
"""

from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

from chaya_worker import archive
from chaya_worker.privacy import masks as privacy_masks

PINHOLE_MODEL_ID = 1
W, H, F = 192, 144, 170.0


@dataclass
class Dataset:
    name: str
    synthetic: bool
    sparse_tar: Path
    poses_json: Path
    frames_tar: Path
    masks_tar: Path
    images: dict[str, np.ndarray]  # name -> RGB float32 [0, 1], the ground truth
    cameras: list[dict]  # name, viewmat (4x4 world-to-camera), K, width, height
    points: int


def _quat_wxyz(r: np.ndarray) -> np.ndarray:
    m = r
    tr = np.trace(m)
    if tr > 0:
        s = np.sqrt(tr + 1.0) * 2
        q = [0.25 * s, (m[2, 1] - m[1, 2]) / s, (m[0, 2] - m[2, 0]) / s, (m[1, 0] - m[0, 1]) / s]
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = np.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
        q = [(m[2, 1] - m[1, 2]) / s, 0.25 * s, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s]
    elif m[1, 1] > m[2, 2]:
        s = np.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
        q = [(m[0, 2] - m[2, 0]) / s, (m[0, 1] + m[1, 0]) / s, 0.25 * s, (m[1, 2] + m[2, 1]) / s]
    else:
        s = np.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
        q = [(m[1, 0] - m[0, 1]) / s, (m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, 0.25 * s]
    q = np.asarray(q)
    return q / np.linalg.norm(q) * (1 if q[0] >= 0 else -1)


def write_colmap_binary(out: Path, cameras: list[dict], points_xyz: np.ndarray, points_rgb: np.ndarray, tracks: list[list[tuple]]) -> None:
    """cameras: one shared PINHOLE camera (id 1); tracks[i] = [(image_id, (u, v)), ...] for point i (id i + 1)."""
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "cameras.bin", "wb") as f:
        f.write(struct.pack("<Q", 1))
        f.write(struct.pack("<iiQQ", 1, PINHOLE_MODEL_ID, W, H))
        f.write(struct.pack("<4d", F, F, W / 2, H / 2))
    points2d: dict[int, list] = {i + 1: [] for i in range(len(cameras))}
    point_tracks = []
    for pid, track in enumerate(tracks, start=1):
        elems = []
        for image_id, (u, v) in track:
            elems.append((image_id, len(points2d[image_id])))
            points2d[image_id].append((u, v, pid))
        point_tracks.append(elems)
    with open(out / "images.bin", "wb") as f:
        f.write(struct.pack("<Q", len(cameras)))
        for image_id, cam in enumerate(cameras, start=1):
            r, t = cam["viewmat"][:3, :3], cam["viewmat"][:3, 3]
            f.write(struct.pack("<I4d3dI", image_id, *_quat_wxyz(r), *t, 1))
            f.write(cam["name"].encode("utf-8") + b"\x00")
            f.write(struct.pack("<Q", len(points2d[image_id])))
            for u, v, pid in points2d[image_id]:
                f.write(struct.pack("<ddq", u, v, pid))
    with open(out / "points3D.bin", "wb") as f:
        f.write(struct.pack("<Q", len(points_xyz)))
        for pid, (xyz, rgb, elems) in enumerate(zip(points_xyz, points_rgb, point_tracks, strict=True), start=1):
            f.write(struct.pack("<Q3d3Bd", pid, *map(float, xyz), *map(int, rgb), 0.5))
            f.write(struct.pack("<Q", len(elems)))
            for image_id, idx in elems:
                f.write(struct.pack("<II", image_id, idx))


# ---- the synthetic scene ------------------------------------------------------------------------------------------------

# Planes: (origin, axis_u, axis_v, normal, extent_u, extent_v). Floor z = 0, walls x = 0 and y = 0, metres.
PLANES = [
    (np.array([0.0, 0.0, 0.0]), np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0]), 4.0, 4.0),
    (np.array([0.0, 0.0, 0.0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0]), np.array([1.0, 0, 0]), 4.0, 2.5),
    (np.array([0.0, 0.0, 0.0]), np.array([1.0, 0, 0]), np.array([0, 0, 1.0]), np.array([0, 1.0, 0]), 4.0, 2.5),
]
BACKGROUND = np.array([0.5, 0.5, 0.5], np.float32)


def texture(plane: int, u: np.ndarray, v: np.ndarray) -> np.ndarray:
    """Deterministic, feature-rich colour (RGB [0, 1]) at plane coordinates (metres): a checkerboard plus smooth bands."""
    check = ((np.floor(u / 0.25) + np.floor(v / 0.25)) % 2)
    base = [np.array([0.85, 0.35, 0.2]), np.array([0.2, 0.6, 0.85]), np.array([0.3, 0.8, 0.35])][plane]
    other = 1.0 - base * 0.8
    c = np.where(check[..., None] > 0, base, other)
    bands = 0.15 * np.sin(u * 7.0 + plane)[..., None] * np.cos(v * 5.0)[..., None]
    return np.clip(c + bands, 0.0, 1.0).astype(np.float32)


def look_at(center: np.ndarray, target: np.ndarray) -> np.ndarray:
    """World-to-camera 4x4 in OpenCV/COLMAP axes (x right, y down, z forward) for a camera at `center`; +Z is up."""
    forward = (target - center) / np.linalg.norm(target - center)
    right = np.cross(forward, [0.0, 0.0, 1.0])
    right /= np.linalg.norm(right)
    down = np.cross(forward, right)
    r = np.stack([right, down, forward])
    v = np.eye(4)
    v[:3, :3], v[:3, 3] = r, -r @ center
    return v


def trace(viewmat: np.ndarray, us: np.ndarray, vs: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Colour of the scene along the rays through pixel positions (us, vs), and the distance to the hit (inf: none)."""
    r, t = viewmat[:3, :3], viewmat[:3, 3]
    origin = -r.T @ t
    dirs_cam = np.stack([(us - W / 2) / F, (vs - H / 2) / F, np.ones_like(us)], axis=-1)
    dirs = dirs_cam @ r  # = (r.T @ d) for each ray
    best = np.full(us.shape, np.inf)
    colour = np.broadcast_to(BACKGROUND, (*us.shape, 3)).copy()
    for i, (o, au, av, n, eu, ev) in enumerate(PLANES):
        denom = dirs @ n
        with np.errstate(divide="ignore", invalid="ignore"):
            tt = ((o - origin) @ n) / denom
        hit = origin + dirs * tt[..., None]
        pu, pv = (hit - o) @ au, (hit - o) @ av
        ok = (tt > 1e-6) & (tt < best) & (pu >= 0) & (pu <= eu) & (pv >= 0) & (pv <= ev)
        best = np.where(ok, tt, best)
        colour[ok] = texture(i, pu[ok], pv[ok])
    distance = best * np.linalg.norm(dirs, axis=-1)  # rays are not unit length: hit = origin + t * dirs
    return colour, distance


def render(viewmat: np.ndarray, supersample: int = 3) -> np.ndarray:
    s = supersample
    ys, xs = np.mgrid[0:H * s, 0:W * s]
    colour, _ = trace(viewmat, (xs + 0.5) / s, (ys + 0.5) / s)
    return colour.reshape(H, s, W, s, 3).mean(axis=(1, 3)).astype(np.float32)


def rendered_room_corner(out: Path, *, n_points: int = 4000, seed: int = 0) -> Dataset:
    rng = np.random.default_rng(seed)
    target = np.array([0.7, 0.7, 0.8])
    cameras = []
    for ring, height in enumerate((1.0, 1.7)):
        for k, angle in enumerate(np.linspace(np.radians(12), np.radians(78), 8)):
            center = np.array([3.4 * np.cos(angle), 3.4 * np.sin(angle), height])
            cameras.append({"name": f"{ring * 8 + k + 1:06d}.png", "viewmat": look_at(center, target),
                            "K": np.array([[F, 0, W / 2], [0, F, H / 2], [0, 0, 1.0]]), "width": W, "height": H})
    images = {c["name"]: render(c["viewmat"]) for c in cameras}

    # SfM-like points: surface samples, kept when at least two views see them unoccluded.
    areas = np.array([p[4] * p[5] for p in PLANES])
    which = rng.choice(len(PLANES), size=n_points * 2, p=areas / areas.sum())
    xyz, rgb, tracks = [], [], []
    for plane in which:
        o, au, av, _n, eu, ev = PLANES[plane]
        pu, pv = rng.uniform(0, eu), rng.uniform(0, ev)
        p = o + au * pu + av * pv
        track = []
        for image_id, cam in enumerate(cameras, start=1):
            pc = cam["viewmat"][:3, :3] @ p + cam["viewmat"][:3, 3]
            if pc[2] <= 0.05:
                continue
            u, v = F * pc[0] / pc[2] + W / 2, F * pc[1] / pc[2] + H / 2
            if not (0 <= u < W and 0 <= v < H):
                continue
            _, depth = trace(cam["viewmat"], np.array([u]), np.array([v]))
            ray_len = np.linalg.norm(p + cam["viewmat"][:3, :3].T @ cam["viewmat"][:3, 3])
            if abs(depth[0] - ray_len) < 1e-3 * ray_len:
                track.append((image_id, (float(u), float(v))))
        if len(track) >= 2:
            xyz.append(p)
            rgb.append(np.round(texture(plane, np.array([pu]), np.array([pv]))[0] * 255).astype(np.uint8))
            tracks.append(track)
        if len(xyz) == n_points:
            break
    return _package(out, "rendered-room-corner (SYNTHETIC)", True, cameras, images, np.array(xyz), np.array(rgb), tracks,
                    calibration_source="SYNTHETIC_TEST_DATASET: exact pinhole intrinsics and poses")


def _package(out: Path, name: str, synthetic: bool, cameras, images, xyz, rgb, tracks, *, calibration_source: str) -> Dataset:
    sparse = out / "sparse"
    write_colmap_binary(sparse, cameras, xyz, rgb, tracks)
    frames, masks = out / "frames", out / "masks"
    frames.mkdir(parents=True)
    masks.mkdir(parents=True)
    for cam in cameras:
        img = images[cam["name"]]
        cv2.imwrite(str(frames / cam["name"]), cv2.cvtColor((img * 255).round().astype(np.uint8), cv2.COLOR_RGB2BGR))
        privacy_masks.write_mask(masks, cam["name"], privacy_masks.valid_mask([], cam["height"], cam["width"]))
    poses = {"mapper": "test-dataset", "fallback_used": False, "frames": len(cameras), "registered": len(cameras),
             "calibration_source": calibration_source, "capture_calibration": None,
             "poses": [{"image_id": i, "name": c["name"], "camera_id": 1, "rotation_wxyz": _quat_wxyz(c["viewmat"][:3, :3]).tolist(),
                        "translation": c["viewmat"][:3, 3].tolist()} for i, c in enumerate(cameras, start=1)]}
    poses_json = out / "poses.json"
    poses_json.write_text(json.dumps(poses), encoding="utf-8")
    sparse_tar, frames_tar, masks_tar = out / "sparse-model.tar", out / "frames.tar", out / "masks.tar"
    archive.pack(sparse, sparse_tar)
    archive.pack(frames, frames_tar)
    archive.pack(masks, masks_tar)
    reloaded = {c["name"]: cv2.cvtColor(cv2.imread(str(frames / c["name"])), cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
                for c in cameras}
    return Dataset(name, synthetic, sparse_tar, poses_json, frames_tar, masks_tar, reloaded, cameras, len(xyz))


# ---- a real COLMAP project ------------------------------------------------------------------------------------------------


def from_colmap_project(project: Path, out: Path, colmap: str, runner) -> Dataset:
    """A real dataset: `project/images/*` and `project/sparse/0` (cameras.bin, images.bin, points3D.bin) from COLMAP.
    Converted with the real model_converter and the worker's own parsers. Only registered images are used."""
    from chaya_worker.colmap_txt import parse_cameras_txt, parse_points3d_txt  # noqa: PLC0415
    from chaya_worker.stages.pose_estimation import parse_images_txt  # noqa: PLC0415

    sparse = project / "sparse" / "0"
    txt = out / "txt"
    txt.mkdir(parents=True)
    runner([colmap, "model_converter", "--input_path", str(sparse), "--output_path", str(txt), "--output_type", "TXT"])
    poses = parse_images_txt((txt / "images.txt").read_text(encoding="utf-8"))
    cams_model = parse_cameras_txt((txt / "cameras.txt").read_text(encoding="utf-8"))
    points = parse_points3d_txt((txt / "points3D.txt").read_text(encoding="utf-8"))
    frames, masks = out / "frames", out / "masks"
    frames.mkdir(parents=True)
    masks.mkdir(parents=True)
    images = {}
    for p in poses:
        img = cv2.imread(str(project / "images" / p["name"]))
        if img is None:
            raise FileNotFoundError(f"registered image {p['name']} is missing from {project / 'images'}")
        (frames / p["name"]).parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(frames / p["name"]), img)
        (masks / p["name"]).parent.mkdir(parents=True, exist_ok=True)
        privacy_masks.write_mask(masks, p["name"], privacy_masks.valid_mask([], img.shape[0], img.shape[1]))
        images[p["name"]] = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    poses_json = out / "poses.json"
    poses_json.write_text(json.dumps({"mapper": "external-colmap-project", "registered": len(poses), "frames": len(poses),
                                      "calibration_source": "SFM_SELF_CALIBRATION", "capture_calibration": None,
                                      "cameras": {str(k): c.record() for k, c in cams_model.items()}, "poses": poses}), encoding="utf-8")
    sparse_tar, frames_tar, masks_tar = out / "sparse-model.tar", out / "frames.tar", out / "masks.tar"
    archive.pack(sparse, sparse_tar)
    archive.pack(frames, frames_tar)
    archive.pack(masks, masks_tar)
    return Dataset(f"colmap-project:{project.name} (REAL)", False, sparse_tar, poses_json, frames_tar, masks_tar, images, [],
                   len(points["ids"]))
