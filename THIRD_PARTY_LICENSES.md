# Third-party licenses

**This is not a statement of license compliance.** It records what the license scanners found and what they did not
cover. Some items below need a human decision. No legal review has been done.

- Scanners: `scripts/licenses/npm-licenses.mjs`, `scripts/licenses/python-licenses.py` and, for Maven,
  `license-maven-plugin` (which writes `THIRD-PARTY.txt`) checked by `scripts/licenses/maven-licenses.py`. CI runs all
  three (`.github/workflows/security.yml`). `scripts/security-scan.sh` runs whichever of them are available locally.
- Policy: `scripts/licenses/policy.json`. **allow** means permissive. **review** means weak copyleft or attribution
  terms, and needs a recorded decision below. Anything else fails, including UNKNOWN.
- **What fails CI:** a denied or unknown license, or a review license on a package that is not listed in
  `policy.json` `pending_review`. The packages in the "Needs a decision" tables below are listed there. They are
  reported as PENDING REVIEW on every run, but do not fail it. That listing is **not approval**: it only stops known,
  open items from hiding new ones. Remove a package from `pending_review` when its decision is recorded here.
- Source of the license data: the metadata each package declares about itself (`package.json` `license`; Python
  `License-Expression`, `License` or trove classifiers). The scanners do not read license files or scan binaries.
  They cannot see licenses of native code bundled inside wheels or npm binaries unless the package declares them.

Last scan: 2026-09-23, on a developer workstation (Windows x64). Platform-specific packages, such as
`@img/sharp-win32-x64`, differ on Linux build machines. Re-run the scanners on the machine that builds the images.

## Needs a decision (review)

| Package | Version | Declared license | Where from | Why it needs a decision |
|---|---|---|---|---|
| `@img/sharp-win32-x64`, `@img/sharp-wasm32` (Linux: `@img/sharp-libvips-*`) | 0.35.4 | Apache-2.0 AND LGPL-3.0-or-later (AND MIT) | `next` → `sharp` (image optimisation) | The prebuilt binaries bundle **libvips (LGPL-3.0-or-later)**. Redistributing them, for example inside a container image, brings LGPL obligations: provide the license text and allow the library to be replaced. If Next image optimisation is not needed, `images.unoptimized` removes the need for sharp at runtime. |
| `caniuse-lite` | 1.0.30001810 | CC-BY-4.0 | `next` (browser targets at build time) | Requires attribution when redistributed. It is used at build time; it is not clear whether it ships in the runtime image. |
| `certifi` | 2026.7.22 | MPL-2.0 | `requests`, `botocore` (worker) | File-level copyleft: fine if unmodified; modifications to its files must be published. |
| `axe-core`, `lightningcss` (dev only) | 4.13.0, 1.32.0 | MPL-2.0 | lint/build tooling | Not shipped at runtime (development tree only). Listed for completeness. |
| `ch.qos.logback:logback-classic`, `logback-core` | 1.5.18 | EPL-1.0 or LGPL-2.1 | Spring Boot logging | Dual-licensed: either may be chosen. Both are weak copyleft. Unmodified use as a library is the usual reading. |
| `org.hibernate.orm:hibernate-core` | 6.6.29.Final | LGPL-2.1-or-later | Spring Data JPA | Ships inside the API image (a Spring Boot fat jar). LGPL obligations apply to redistribution: license text, and the ability to replace the library. |
| `org.aspectj:aspectjweaver` | 1.9.24 | EPL-2.0 | Spring AOP (method security) | File-level weak copyleft. Unmodified use. |
| `jakarta.annotation:jakarta.annotation-api`, `jakarta.transaction:jakarta.transaction-api` | 2.1.1, 2.0.1 | EPL-2.0 or GPL-2.0 with Classpath Exception | Jakarta EE APIs | Dual-licensed. The policy judges them on EPL-2.0. |

## Scanned: web (`apps/web`), production dependency tree

35 packages. All are allowed except the review items above.
MIT: `@emnapi/core`, `@emnapi/runtime`, `@emnapi/wasi-threads`, `@img/colour`, `@mkkellogg/gaussian-splats-3d`,
`@napi-rs/wasm-runtime`, `@next/env`, `@next/swc-win32-x64-msvc`, `@tybys/wasm-util`, `client-only`, `hash-wasm`,
`jwt-decode`, `nanoid`, `next`, `postcss`, `react`, `react-dom`, `scheduler`, `styled-jsx`, `three`.
Apache-2.0: `@playwright/test`, `@swc/helpers`, `baseline-browser-mapping`, `detect-libc`, `oidc-client-ts`,
`playwright`, `playwright-core`, `sharp`. ISC: `picocolors`, `semver`. BSD-3-Clause: `source-map-js`. 0BSD: `tslib`.
(`@playwright/test` appears because `next` declares it as an optional peer dependency.)

Full development tree: 374 packages. The only additions to the review list are the dev-only MPL-2.0 packages above.

## Scanned: reconstruction worker (`services/reconstruction`), base and dev dependencies

27 distributions in the worker's development environment. All allowed except `certifi`.
Apache-2.0: `boto3`, `botocore`, `s3transfer`, `requests`, `opencv-python-headless`. MIT: `charset-normalizer`,
`iniconfig`, `jmespath`, `pluggy`, `pytest`, `six`, `urllib3`. MIT-CMU: `pillow`. BSD: `colorama`, `idna`, `ImageIO`,
`imageio-ffmpeg`, `lazy-loader`, `networkx`, `numpy`, `Pygments`, `scikit-image`, `scipy`, `tifffile`.
Apache-2.0 OR BSD-2-Clause: `packaging`. Apache-2.0 OR BSD: `python-dateutil`.

**Not scanned: the `reconstruction` extra** (`torch`, `gsplat`, `open3d`, `transformers`, `huggingface-hub`,
`open_clip_torch`). It was not installed on the scanning machine. These packages and the CUDA libraries they pull in
carry their own terms (for example the NVIDIA CUDA EULA for `nvidia-*` wheels). Scan the GPU worker image before
distributing it.

**Native code not visible to the scanner:** `numpy` and `scipy` wheels bundle OpenBLAS and gfortran runtime
libraries. `opencv-python-headless` bundles FFmpeg libraries, which may be LGPL or GPL depending on the build. The
worker image also installs Debian's `ffmpeg` package (`services/reconstruction/Dockerfile`), whose license depends on
Debian's build options. None of these are in the tables above.

## Scanned: vision service (`services/vision`), base dependencies

18 distributions, all allowed: `annotated-doc`, `annotated-types`, `anyio`, `fastapi`, `h11`, `httptools`, `pydantic`,
`pydantic_core`, `PyYAML`, `typing-inspection`, `watchfiles` (MIT); `click`, `idna`, `python-dotenv`, `starlette`,
`uvicorn`, `websockets` (BSD-3-Clause); `typing_extensions` (PSF-2.0).
**Not scanned:** the `model` extra (`torch`, `open_clip_torch`).

## Scanned: API (`services/api`, Maven), compile and runtime scopes

Scanned 2026-09-24 with `license-maven-plugin` 2.4.0 (`add-third-party`) and `maven-licenses.py`. There are 141
artifacts: 135 allowed, and the 6 in "Needs a decision" above. Licenses of the 135, as declared in their POMs:
Apache-2.0 (119, under five spellings), EDL-1.0, the Eclipse Distribution License, which is BSD-3-Clause (8, including
`jakarta.persistence-api`, which also offers EPL-2.0), MIT (3), BSD-2-Clause (2), BSD-3-Clause (1), MIT-0 (1) and CC0
(1; one of the BSD-2-Clause artifacts also offers CC0). The full list is the CI artifact `maven-reports` (`THIRD-PARTY.txt`).

Test-scope dependencies (JUnit, Testcontainers, and so on) are not shipped, and were not scanned.

## Model weights and runtime downloads (not packages)

Model weights are downloaded at runtime and have their own licenses, separate from the code that loads them:

| Model | Used by | Note |
|---|---|---|
| `IDEA-Research/grounding-dino-tiny` | SEMANTIC_INDEXING | Check the model card license at the pinned revision (`GROUNDING_DINO_REVISION`). |
| `nvidia/segformer-b0-finetuned-ade-512-512` | SEMANTIC_SEGMENTATION | NVIDIA's SegFormer weights have historically used a **non-commercial** license. Confirm it before any commercial use. |
| CLIP ViT-B/32 "openai" (`open_clip`) | search embeddings | Check the terms of the specific pretrained checkpoint. |
| OpenCV Haar cascades | privacy stage | Shipped inside `opencv-python-headless` (Intel License Agreement, BSD-style). |

## Container base images

`python:3.12-slim` (Debian), `pgvector/pgvector`, `quay.io/minio/minio` (AGPL-3.0: used unmodified, as a separate
network service), `quay.io/keycloak/keycloak` (Apache-2.0), `clamav/clamav` (GPL-2.0: used unmodified, as a separate
service), `redis:7-alpine` (RSALv2/SSPL from Redis 7.4; this compose file uses 7.x, so check the exact tag),
Prometheus, Loki (AGPL-3.0), Alloy, Grafana (AGPL-3.0), GlitchTip. Running AGPL services unmodified, as separate
processes, is the common reading of their terms. It is still a decision to confirm, not something asserted here.
