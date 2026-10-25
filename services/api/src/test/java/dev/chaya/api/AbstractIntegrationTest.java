package dev.chaya.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the application against real PostgreSQL (pgvector) and real MinIO; Flyway applies the real
 * migrations. Skipped (not failed) when Docker is unavailable.
 */
// Classification B (docs/ACCEPTANCE.md): service integration against real PostgreSQL and MinIO. @Tag is inherited by every subclass.
@org.junit.jupiter.api.Tag("integration")
@SpringBootTest(properties = {
    "chaya.security.issuer=" + TestJwt.ISSUER,
    "chaya.security.audience=" + TestJwt.AUDIENCE,
    "chaya.uploads.part-size-bytes=5242880",
    "chaya.uploads.max-image-bytes=1048576",
    "chaya.uploads.min-images-without-video=3",
    "chaya.storage.bucket=chaya-raw-test",
    "chaya.storage.derived-bucket=chaya-derived-test",
    "chaya.clamav.enabled=false",
    // Shared context: many tests call from one address and must not trip the limiter (RateLimiterTest covers it),
    // and health must be recomputed per request (HealthServiceTest covers the cache).
    "chaya.rate-limit.enabled=false",
    "chaya.health.cache-ttl=PT0S",
    // The POI embedding backfill must not race assertions; PoiEmbeddingServiceTest drives it directly.
    "chaya.search.embedding-backfill-initial-delay=PT24H",
    "chaya.search.embedding-backfill-interval=PT24H",
    // ErasureTest drives the erasure sweep itself and asserts states between passes.
    "chaya.erasure.sweep-initial-delay=PT24H",
    // The production relevance margin (0.078) is calibrated for CLIP. The test embeddings are random unit vectors
    // (TestEmbeddingConfig), whose unrelated pairs sit near cosine 0 +- 0.04: 0.3 separates them unambiguously.
    "chaya.search.relevance-min-margin=0.3",
    // The fixture worker (PipelineTestSupport) publishes placeholder POSES with no real cameras to triangulate through, so the
    // control-plane tests calibrate with operator-supplied reconstruction points. MeasuredCalibrationTest runs with the
    // production default (false) and covers the gate and the triangulated path.
    "chaya.frames.accept-operator-supplied-points=true"
})
@AutoConfigureMockMvc
@Import({TestJwtConfig.class, TestScannerConfig.class, TestEmbeddingConfig.class})
@Testcontainers(disabledWithoutDocker = true)
abstract class AbstractIntegrationTest {

    // One container of each for the whole test JVM: Spring caches the application context across
    // test classes, so per-class containers would leave cached contexts pointing at stopped services.
    static final PostgreSQLContainer PG = new PostgreSQLContainer(
        DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    // The project's GHCR mirror of quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z (linux/amd64), byte-identical and
    // pinned by digest, as in infra/docker/docker-compose.yml (infra/mirror/README.md: upstream refuses anonymous pulls).
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
            "ghcr.io/yawayapnwork/mirror/minio@sha256:a1a8bd4ac40ad7881a245bab97323e18f971e4d4cba2c2007ec1bedd21cbaba2"))
        .withCommand("server", "/data")
        .withEnv("MINIO_ROOT_USER", "testaccess")
        .withEnv("MINIO_ROOT_PASSWORD", "testsecret123")
        .withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            PG.start();
            MINIO.start();
            // As minio-init does in every real stack: the buckets exist before the API serves (its health reports a
            // missing bucket as storage DOWN).
            try {
                var r = MINIO.execInContainer("sh", "-c", "mc alias set t http://127.0.0.1:9000 testaccess testsecret123 > /dev/null "
                    + "&& mc mb --ignore-existing t/chaya-raw-test t/chaya-derived-test");
                if (r.getExitCode() != 0) {
                    throw new IllegalStateException("creating the test buckets failed: " + r.getStderr());
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("chaya.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("chaya.storage.access-key", () -> "testaccess");
        registry.add("chaya.storage.secret-key", () -> "testsecret123");
    }

    @Autowired protected JdbcClient jdbc;
    protected Fixtures fx;

    @org.junit.jupiter.api.BeforeEach
    void initFixtures() {
        fx = new Fixtures(jdbc);
    }
}
