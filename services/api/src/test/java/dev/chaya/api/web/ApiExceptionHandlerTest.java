package dev.chaya.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.chaya.api.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/** Dependency outages reaching a controller become explicit 503 problems that do not leak internals. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void storageUnavailableIs503() {
        ProblemDetail p = handler.storage(new StorageException("object storage failed to begin upload", new RuntimeException("Connection refused: 127.0.0.1:9000")));
        assertThat(p.getStatus()).isEqualTo(503);
        assertThat(p.getProperties()).containsEntry("code", "STORAGE_UNAVAILABLE");
        assertThat(p.getDetail()).doesNotContain("127.0.0.1");
    }

    @Test
    void databaseUnavailableIs503() {
        ProblemDetail p = handler.databaseUnavailable(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection; jdbc:postgresql://db:5432/chaya"));
        assertThat(p.getStatus()).isEqualTo(503);
        assertThat(p.getProperties()).containsEntry("code", "DATABASE_UNAVAILABLE");
        assertThat(p.getDetail()).doesNotContain("jdbc:");
    }
}
