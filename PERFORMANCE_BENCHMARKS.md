# PERFORMANCE BENCHMARKS REPORT: CHAYA 02
**Empirical Measurements, Algorithmic Comparisons & Resource Utilization**

**Audit Date:** 2026-11-01  
**Lead Auditor:** Independent Principal Engineer & QA Lead  
**Evaluation Scope:** Benchmarks B1 through B7, Algorithmic Profiling & Hardware Resource Characterization  

---

## 0. Benchmark Methodology & Metric Provenance

To eliminate fabrication and ambiguous claims, every quantitative metric in this audit is strictly categorized under one of the following provenance labels:

| Category | Definition | Example in Report |
|---|---|---|
| **[MEASURED]** | Produced by direct execution of reproducible code on recorded data during audit runs | Query latency p95 = 4.8 ms; 559 unit tests passed |
| **[ESTIMATE]** | Derived by deterministic mathematical formula from measured values and constants | Total capture time = lap time + length / 0.5 m/s + wp × 3s |
| **[CONFIGURATION]**| Configured constant of the system, benchmark suite, or simulation environment | Agent radius = 0.35 m; max step-free slope = 4.76 deg |
| **[ASSUMPTION]** | Stated input condition or synthetic proxy representing real-world distribution | Synthetic 3D point cloud stands in for reconstructed splat |
| **[REFERENCE]** | Analytic baseline for comparison | Random ranking MRR; trivial heuristic path length |
| **[UNAVAILABLE]** | Blocked due to missing hardware or real venue data; command documented | Real CUDA gsplat training time on custom venue |

---

## 1. Summary of Benchmark Suite (B1 – B7)

| Benchmark | Focus Area | Core Contribution Under Test | Primary Finding | Evidentiary Basis | Status |
|---|---|---|---|---|---|
| **B1** | Capture Path Planning | 2D recon lap + frontier TSP path planning | Beats equal-distance lawnmower across all 7 synthetic scenes; 0 redundant waypoints | Synthetic floor plans + visibility simulation | **VERIFIED** |
| **B2** | Geometry Cleanup | Statistical & radius outlier filtering | Statistical filter targets weak points **6.6×** better than chance on SfM points | Sceaux SfM point cloud (8,911 points) | **VERIFIED** |
| **B2c** | Controlled Cleanup | Semantic-aware cleanup on synthetic scene | Removes 86% of noise at 99.95% precision; preserves stairs/ramps | Synthetic multi-level room with ground truth | **VERIFIED** |
| **B3** | Semantic Search | CLIP text-space ranking + relevance threshold | Detected objects rank in top-5 for **22/22** queries; filters **86.7%** nonsense queries | 30 POIs, 15 COCO crops, live pgvector | **VERIFIED** |
| **B4** | Accessible Routing | Recast/Detour multi-floor graph routing | Avoids stairs, steep ramps, and narrow corridors (< 0.9 m) in step-free mode | Synthetic two-floor venue with stairs & ramp | **VERIFIED** |
| **B5** | Incremental Rescan | Similarity registration with scale prior | Recovers misalignments up to 30 deg and **×1.08 scale error**; **138/138** bad alignments rejected | Real SfM geometry with simulated motion | **VERIFIED** |
| **B6** | Vision Model Baseline| Grounding DINO + SegFormer stock checkpoints | Baseline mIoU **0.548** (SegFormer); mAP@0.5 **0.500** (DINO); recall@5 **0.744** (CLIP) | 102 held-out indoor COCO images | **VERIFIED** |
| **B7** | Multi-Room Stitching | Free-space ICP & pose-graph loop closure | Placed every room in **10/10** trials (median error 0.9 mm); seam step **3–10 mm** | Three-room synthetic layout (400 pts/m²) | **VERIFIED** |

---

## 2. Detailed Benchmark Results

### Benchmark B1: Capture Path Planning (`benchmarks/b1_capture_path`)
- **Question:** Does Chaya’s planned capture path improve spatial coverage more than walking a naive boundary or lawnmower path of equivalent distance?
- **Setup:** 7 synthetic floor plans ranging from a simple 8×6 m room to a 16×10 m office with annex. Walking speed: 0.5 m/s; waypoint dwell: 3.0 s.
- **Results:**
  - **Room 8×6 m:** Planner achieves **92.6% coverage** (4.1 m² uncovered) in 15.7 m (80 s) with 0 redundant waypoints. Lawnmower at equal distance achieves only **71.7% coverage** (15.6 m² uncovered).
  - **Partitioned Room:** Planner achieves **90.2% coverage** (5.2 m² uncovered) in 21.6 m (101 s). Lawnmower at equal distance achieves **78.6% coverage** (11.4 m² uncovered).
  - **Three Rooms & Doorways:** Planner achieves **89.8% coverage** (5.8 m² uncovered) in 20.1 m (99 s). Lawnmower at equal distance achieves **63.2% coverage** (20.7 m² uncovered).
- **Execution Time [MEASURED]:** Planning algorithm execution latency: **22 ms to 226 ms** (median 48 ms).

### Benchmark B2: Geometry Cleanup (`benchmarks/b2_geometry_cleanup`)
- **Question:** Can statistical and radius outlier filtering isolate noise and floater Gaussians without eroding architectural features?
- **Dataset:** Château de Sceaux SfM point cloud (8,911 points with reprojection error and track lengths).
- **Results:**
  - **Statistical Outlier Removal (SOR):** Targets points with high reprojection error (> 2.0 px) and short track lengths (**6.6× better than random selection**).
  - **Radius Outlier Filter Tuning:** Initial settings (`radius_nb_points = 8`) over-thinned flat surfaces, retaining only 41% of walls and floors. Retuned configuration (`radius_nb_points = 4`, `radius_m = 0.12`) retains **94.2% of planar surfaces** while rejecting isolated floating clusters.

### Benchmark B3: Semantic Search & Ranking (`benchmarks/b3_semantic_search`)
- **Question:** Does hybrid vector ranking in CLIP text space outperform traditional lexical search and pure image-to-image similarity?
- **Dataset:** 30 human-curated venue POIs, 15 annotated COCO indoor object crops, 59 natural language queries.
- **Results:**
  - **Synonym Queries:** Top-1 accuracy **58.8%** with semantic ranking vs. **5.9%** with pure SQL `ILIKE` lexical search.
  - **Nonsense Query Rejection:** Configured relevance threshold (cosine distance < 0.68) successfully rejects **86.7% of adversarial/nonsense queries** without returning spurious false positives.
  - **Detected Object Recall:** Queries seeking physical objects ("find fire extinguisher", "where is coffee machine") returned the correct object in top-5 for **22/22 (100%)** of test queries.
  - **Latency [MEASURED]:** pgvector HNSW cosine query latency: **median 1.8 ms, p95 4.8 ms**. Fallback lexical search during simulated vision outage: **median 0.9 ms, p95 3.2 ms**.

### Benchmark B4: Accessible Pathfinding (`benchmarks/b4_accessible_routing`)
- **Question:** Does Detour path generation strictly enforce wheelchair accessibility constraints across multi-floor transitions?
- **Setup:** Synthetic two-floor venue featuring a 1:14 ramp (accessible), an internal staircase (inaccessible), a narrow 0.75 m doorway, and an elevator.
- **Results:**
  - **Standard Routing:** Selects the shortest metric path via stairs (distance: 14.2 m, travel time: 28.4 s).
  - **Step-Free Routing:** Avoids stairs and routes via the 1:14 ramp (max slope: 4.09 deg <= 4.76 deg limit; distance: 22.8 m).
  - **Clearance Gating:** When wheelchair width requirement is set to 0.85 m, the router successfully excludes the 0.75 m narrow doorway, routing around the exterior hallway.

### Benchmark B5: Incremental Rescan Alignment (`benchmarks/b5_incremental_rescan`)
- **Question:** Does similarity registration converge across independent SfM coordinate frames with arbitrary scale discrepancies?
- **Setup:** Real SfM point cloud subjected to controlled rotations (0 to 30 deg), translations (0 to 5 m), and scale distortion (factor ×1.08).
- **Results:**
  - **Convergence:** FPFH feature matching + RANSAC similarity alignment recovers rotation within **0.005 deg** and scale within **0.01%** under noise-free conditions.
  - **Adversarial Gate Defense:** Under simulated high-noise or non-overlapping scenes, the physical quality gates rejected **138/138 (100%) invalid alignments**, with 0 false acceptances (`ALIGNMENT_REJECTED`).

### Benchmark B6: Vision Model Baselines (`docs/ML_EVALUATION.md`)
- **Evaluated Checkpoints:** Stock `IDEA-Research/grounding-dino-tiny`, `nvidia/segformer-b0-finetuned-ade-512-512`, `openai/clip-vit-base-patch32`.
- **Dataset:** 102 held-out indoor images from COCO val2017.
- **Results:**
  - **SegFormer Segmentation:** Mean IoU **0.548** over indoor architectural classes (floor, wall, ceiling).
  - **Grounding DINO Detection:** Mean AP@0.5 **0.500** across 4 indoor classes ("door", "chair", "table", "screen"). "Table" performed poorest at 0.07 mAP due to occlusions.
  - **CLIP Crop Retrieval:** Text-to-crop Recall@5 **0.744**.
  - **Note on Fine-Tuning:** The solution document claims custom "fine-tuned" models. In reality, **zero models have been fine-tuned**; all models are stock checkpoints.

### Benchmark B7: Multi-Room Registration & Stitching (`benchmarks/b7_multiroom`)
- **Setup:** Three synthetic rooms ("A", "B", "C") in a loop with 60% corrupted feature correspondences and 3 mm Gaussian noise.
- **Results:**
  - **Loop Closure:** **10/10 trials** converged, closing the loop with a median translation error of **0.9 mm** (2.1 mm at 10 mm noise).
  - **Seam Alignment:** Stitched boundary seam step measured between **3 mm and 10 mm**; duplicate Gaussian points in overlapping volumes <= 0.3%.

---

## 3. Hardware Resource Utilization & Host Profile

Measurements taken on the primary audit host during test suite execution:

| Subsystem / Process | Host Hardware | Observed CPU Usage | Observed RAM Peak | Disk Footprint | CUDA Status |
|---|---|---|---|---|---|
| **Python Unit Suite (559 tests)** | Intel Core Ultra 5 125H | 82 % (multi-threaded) | 1.84 GB | ~ 1.2 GB | N/A (CPU) |
| **Playwright Web Suite (68 tests)**| Chromium headless | 45 % | 920 MB | ~ 450 MB | Software WebGL |
| **API Backend (Spring Boot)** | JVM (JDK 21) | 28 % | 680 MB heap | ~ 280 MB | N/A |
| **PostgreSQL + pgvector** | PostgreSQL 16 container | 12 % | 340 MB | ~ 180 MB | N/A |
| **MinIO Object Storage** | MinIO RELEASE container | 4 % | 120 MB | ~ 45 MB | N/A |
| **gsplat Training Worker** | Required: NVIDIA L4/A10G | **0 % [UNAVAILABLE]** | **0 MB [UNAVAILABLE]** | **0 GB [UNAVAILABLE]** | **ABSENT** |

---

## 4. Benchmark Verification Verdict

The mathematical and algorithmic benchmarks (B1, B2, B3, B4, B5, B7) are genuine, reproducible, and supported by committed code and fixtures. However, all claims regarding real-world 3D reconstruction throughput, training seconds per square meter, and mobile GPU battery consumption remain **UNAVAILABLE** and cannot be certified without a physical CUDA deployment.
