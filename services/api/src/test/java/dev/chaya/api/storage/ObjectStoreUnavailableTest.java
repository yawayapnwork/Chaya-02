package dev.chaya.api.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * MinIO unavailable: the real S3 client pointed at an address where nothing listens. Health must read DOWN quickly,
 * and an operation must fail with StorageException (mapped to 503 STORAGE_UNAVAILABLE, see ApiExceptionHandlerTest),
 * never pretend to have stored anything.
 */
class ObjectStoreUnavailableTest {

    private final S3ObjectStore dead = new S3ObjectStore(
        new StorageProperties("http://127.0.0.1:1", "us-east-1", "k", "s", "chaya-raw", "chaya-derived"), "chaya-raw");

    @Test
    void pingIsFalseAndBounded() {
        Instant start = Instant.now();
        assertThat(dead.ping()).isFalse();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void operationsFailWithStorageException() {
        assertThatThrownBy(() -> dead.beginMultipart("org/x/raw/y", "video/mp4")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> dead.size("org/x/raw/y")).isInstanceOf(StorageException.class);
    }
}
