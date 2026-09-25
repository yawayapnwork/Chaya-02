package dev.chaya.api.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The vision client's circuit breaker against a real local HTTP server that can be switched between healthy, failing
 * and "model loading". */
class HttpTextEmbeddingClientTest {

    private HttpServer server;
    private final AtomicBoolean embedFails = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(true);
    private final AtomicInteger embedCalls = new AtomicInteger();
    private final AtomicInteger readyCalls = new AtomicInteger();
    private HttpTextEmbeddingClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embed-text", ex -> {
            embedCalls.incrementAndGet();
            if (embedFails.get()) {
                reply(ex, 500, "{\"detail\":\"down\"}");
            } else {
                reply(ex, 200, "{\"embedding\":[0.6,0.8],\"model\":\"open_clip:test\"}");
            }
        });
        server.createContext("/health/ready", ex -> {
            readyCalls.incrementAndGet();
            reply(ex, ready.get() ? 200 : 503, "{}");
        });
        server.start();
        SearchProperties props = new SearchProperties("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2),
            10, 50, Duration.ofMillis(100), null, 0, 0);
        client = new HttpTextEmbeddingClient(RestClient.builder(), props);
    }

    @AfterEach
    void stop() {
        client.destroy();
        server.stop(0);
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void aHealthyServiceIsCalledAndItsModelReported() {
        var e = client.embedWithModel("cafe");
        assertThat(e.vector()).containsExactly(0.6f, 0.8f);
        assertThat(e.model()).isEqualTo("open_clip:test");
        assertThat(client.available()).isTrue();
    }

    @Test
    void afterAFailureCallsFailImmediatelyWithoutReachingTheServiceUntilTheProbeSeesItReady() throws InterruptedException {
        embedFails.set(true);
        ready.set(false); // e.g. restarting, model still loading
        assertThatThrownBy(() -> client.embedWithModel("cafe")).isInstanceOf(EmbeddingUnavailableException.class);
        assertThat(client.available()).isFalse();
        int callsWhenOpened = embedCalls.get();

        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> client.embedWithModel("cafe"))
                .isInstanceOf(EmbeddingUnavailableException.class).hasMessageContaining("checking again");
        }
        long perCallMs = (System.nanoTime() - t0) / 20 / 1_000_000;
        assertThat(embedCalls.get()).as("an open breaker never calls the service").isEqualTo(callsWhenOpened);
        assertThat(perCallMs).as("an open breaker answers at once").isLessThan(50);

        await(() -> readyCalls.get() >= 2);
        assertThat(client.available()).as("a 503 from /health/ready (model loading) keeps it open").isFalse();

        embedFails.set(false);
        ready.set(true);
        await(client::available);
        assertThat(client.available()).isTrue();
        assertThat(client.embedWithModel("cafe").model()).isEqualTo("open_clip:test");
        int probesAfterClose = readyCalls.get();
        Thread.sleep(300);
        assertThat(readyCalls.get()).as("no probing while closed").isEqualTo(probesAfterClose);
    }

    @Test
    void aMalformedReplyAlsoOpensTheBreaker() throws IOException {
        server.removeContext("/v1/embed-text");
        server.createContext("/v1/embed-text", ex -> reply(ex, 200, "{\"embedding\":[],\"model\":\"open_clip:test\"}"));
        assertThatThrownBy(() -> client.embedWithModel("cafe")).hasMessageContaining("no embedding");
        assertThat(client.available()).isFalse();
    }
}
