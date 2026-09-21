package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Runs the real migrations against real pgvector. Skipped when Docker is unavailable. */
@Testcontainers(disabledWithoutDocker = true)
class MigrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Test
    void migrationsApplyAndEnableVector() {
        Flyway.configure()
            .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
            .load()
            .migrate();
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Integer n = new JdbcTemplate(ds)
            .queryForObject("select count(*) from pg_extension where extname = 'vector'", Integer.class);
        assertThat(n).isEqualTo(1);
    }
}
