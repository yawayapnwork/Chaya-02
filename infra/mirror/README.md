# Owned image mirrors (MinIO)

The API's integration tests, the stack smoke test, the development stack, the production compose file and the backup
scripts all need MinIO. Since late September 2026 the upstream registries (`quay.io/minio/*` and `docker.io/minio/*`)
refuse anonymous pulls (`401 unauthorized`), so every CI job that needed MinIO failed, and only workstations with an
old cached copy still worked. This directory holds the project's own GHCR copies of the **exact upstream images already
pinned in this repository**: same version, same bytes, same digest. Nothing is rebuilt, and no community fork is used.

## Images

| | Server | Client |
|---|---|---|
| Upstream | `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z` | `quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z` |
| Upstream manifest list (multi-arch, the digest pinned here before) | `sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e` | `sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727` |
| Upstream linux/amd64 manifest (listed in the above) | `sha256:a1a8bd4ac40ad7881a245bab97323e18f971e4d4cba2c2007ec1bedd21cbaba2` | `sha256:eb4ea9884b77704230e2423e9004d2fa738dc272876b9cc41a297d29443b8780` |
| **Mirror (use this)** | `ghcr.io/yawayapnwork/mirror/minio:RELEASE.2025-09-07T16-13-09Z-linux-amd64@sha256:a1a8bd4a…baba2` | `ghcr.io/yawayapnwork/mirror/mc:RELEASE.2025-08-13T08-35-41Z-linux-amd64@sha256:eb4ea988…b8780` |
| Source (AGPL-3.0) | [minio/minio@RELEASE.2025-09-07T16-13-09Z](https://github.com/minio/minio/tree/RELEASE.2025-09-07T16-13-09Z) | [minio/mc@RELEASE.2025-08-13T08-35-41Z](https://github.com/minio/mc/tree/RELEASE.2025-08-13T08-35-41Z) |

The mirror digest **is** the upstream linux/amd64 digest: the registry serves the upstream manifest, config and layers
unchanged. `image.env` in each subdirectory holds these values; `scripts/ci/minio-preflight.sh` fails if a consumer
pins anything else.

**Naming.** `ghcr.io/<owner>/mirror/<upstream image name>:<upstream tag>-<os>-<arch>`, always consumed as
`…@sha256:<digest>`. The `mirror/` prefix keeps copies of third-party images apart from the project's own
`chaya-*` images; the tag suffix states what the copy contains.

**Only linux/amd64.** The upstream list also names arm64 and ppc64le, but those were never downloaded before the
registries closed, so they cannot be mirrored byte-exactly. CI runners, and the `chaya-*` images themselves
(built on amd64 runners without `platforms:`), are amd64-only already. On an Apple Silicon machine these images run
under emulation. If upstream becomes available again, mirror the whole list (see "Updating").

## Provenance you can check

`<name>/upstream-manifest-list.json` and `<name>/linux-amd64-manifest.json` are the upstream manifests, byte for byte
(`.gitattributes` keeps line endings out of them). Their SHA-256 are the digests above:

    sha256sum infra/mirror/*/*.json

The upstream list was the digest this repository pinned since 2026-09-24 (`infra/docker/docker-compose.yml` history,
`docs/e2e-evidence/stack.txt`). It names the amd64 manifest, which pins the config and every layer by digest, so a
registry that serves `MIRROR@DIGEST` is serving exactly what upstream published.

The first mirror was seeded on 2026-09-30 from a workstation's Docker image store that still held the upstream list
(`docker image inspect` ID = `sha256:14cea493…` / `sha256:a7fe349e…`), with every blob re-hashed before the push.
Content addressing makes the source irrelevant once the digests match; that is what `mirror-image.sh` checks.

## Access

The two GHCR packages are **public**: CI, developers, deploy hosts and the backup scripts pull them anonymously, and no
workflow logs in for them. A new GHCR package is private by default; after the first push, set each package to Public
(package page, Package settings, Change visibility). If they were ever private, `docker pull` fails with
`unauthorized` / `denied`, and `minio-preflight.sh` says so first.

## Verifying, publishing, updating

Verify what the registry serves (anyone, no credentials):

    scripts/mirror/mirror-image.sh minio --verify-only
    scripts/mirror/mirror-image.sh mc --verify-only
    scripts/ci/minio-preflight.sh          # pulls fresh, checks digests, starts MinIO, makes a bucket with mc

Publish (a maintainer, once per version; needs the containerd image store and `docker login ghcr.io` with a token
that has `write:packages`, for example `gh auth refresh -s write:packages && gh auth token | docker login ghcr.io -u
<you> --password-stdin`; log out afterwards):

    scripts/mirror/mirror-image.sh minio [SOURCE]
    scripts/mirror/mirror-image.sh mc [SOURCE]

`SOURCE` defaults to `UPSTREAM@UPSTREAM_LIST_DIGEST` and is pulled from upstream if upstream allows it.

To move to a new MinIO release: obtain the upstream release's manifest list from a registry you trust (authenticated
upstream access, or an official distribution MinIO names), save the list and the linux/amd64 manifest into
`<name>/`, update `image.env`, run `mirror-image.sh`, then change every pin together (the preflight lists them) and
follow DEPLOYMENT.md "Third-party images". Never mirror an image whose digest you cannot tie to MinIO's own release.
