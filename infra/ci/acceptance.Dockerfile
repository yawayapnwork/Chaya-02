# TEST-ONLY runner for the cross-service acceptance suite (services/reconstruction/tests/acceptance, docs/ACCEPTANCE.md):
# the production CPU worker image plus pytest. The suite runs inside the stack's network so it reaches the API, Keycloak and
# object storage by their service names, exactly as the worker does; the repository is mounted read-only at /repo and its
# chaya_worker package (PYTHONPATH) is the code under test, not the copy installed in the image.
# Nothing here is published or used in production.
#   compose:  additional_contexts: { base: "service:reconstruction-worker" }
#   docker:   docker build -f infra/ci/acceptance.Dockerfile --build-context base=docker-image://<worker image> infra/ci

FROM base
USER 0
# scikit-image: the capture video's face comes from skimage.data (tests/conftest.py face_video), as in the stack smoke.
RUN pip install --no-cache-dir pytest==9.1.1 scikit-image==0.26.0
USER 10001:10001
ENV PYTHONPATH=/repo/services/reconstruction PYTHONDONTWRITEBYTECODE=1
WORKDIR /repo/services/reconstruction
ENTRYPOINT ["python", "-m", "pytest", "-p", "no:cacheprovider"]
