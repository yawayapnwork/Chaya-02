package dev.chaya.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the application against real PostgreSQL (pgvector) and real MinIO; Flyway applies the real
 * migrations. Skipped (not failed) when Docker is unavailable.
 */
@SpringBootTest(properties = {
    "chaya.security.issuer=" + TestJwt.ISSUER,
    "chaya.security.audience=" + TestJwt.AUDIENCE,
    "chaya.uploads.part-size-bytes=5242880",
    "chaya.uploads.max-image-bytes=1048576",
    "chaya.uploads.min-images-without-video=3",
    "chaya.storage.bucket=chaya-raw-test",
    "chaya.storage.derived-bucket=chaya-derived-test",
    "chaya.clamav.enabled=false"
})
@AutoConfigureMockMvc
@Import({TestJwtConfig.class, TestScannerConfig.class, TestEmbeddingConfig.class})
@Testcontainers(disabledWithoutDocker = true)
abstract class AbstractIntegrationTest {

    // One container of each for the whole test JVM: Spring caches the application context across
    // test classes, so per-class containers would leave cached contexts pointing at stopped services.
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:latest"))
        .withCommand("server", "/data")
        .withEnv("MINIO_ROOT_USER", "testaccess")
        .withEnv("MINIO_ROOT_PASSWORD", "testsecret123")
        .withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            PG.start();
            MINIO.start();
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
