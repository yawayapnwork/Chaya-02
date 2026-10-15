package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.capture.ProcessingBroadcaster;
import dev.chaya.api.pipeline.PipelineDefinition;
import dev.chaya.api.pipeline.PipelineService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Live processing status (docs/pipeline.md, "Live progress"): the persisted snapshot with its revision, the
 * Server-Sent Events stream, and what both say about success, failure, a lost worker, retry and cancellation. Real HTTP
 * through MockMvc against real PostgreSQL and MinIO; the worker is played by the test with the real worker protocol.
 */
// The tests drive the broadcaster ticks themselves: a scheduled one would write into the mock response while a test reads it.
@TestPropertySource(properties = "chaya.processing.stream.interval-ms=3600000")
class ProcessingLiveStatusTest extends PipelineTestSupport {

    @Autowired PipelineService pipeline;
    @Autowired ProcessingBroadcaster broadcaster;

    private String processingUrl(Started s) {
        return capUrl(s.c(), s.capture()) + "/processing";
    }

    private JsonNode snapshot(Started s) throws Exception {
        return mapper.readTree(get(processingUrl(s), s.c().operator()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static String stageState(JsonNode status, String stage) {
        for (JsonNode st : status.get("run").get("stages")) {
            if (st.get("stage").asText().equals(stage)) {
                return st.get("state").asText();
            }
        }
        return null;
    }

    private MvcResult openStream(Started s, String bearer, String lastEventId) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(processingUrl(s) + "/stream").header("Authorization", "Bearer " + bearer).accept("text/event-stream");
        if (lastEventId != null) {
            req.header("Last-Event-ID", lastEventId);
        }
        return mvc.perform(req).andExpect(request().asyncStarted()).andReturn();
    }

    /** The status events written to a stream so far, in order. */
    private List<JsonNode> events(MvcResult stream) throws Exception {
        MockHttpServletResponse res = stream.getResponse();
        List<JsonNode> out = new ArrayList<>();
        for (String frame : res.getContentAsString().split("\n\n")) {
            if (!frame.contains("event:status")) {
                continue;
            }
            for (String line : frame.split("\n")) {
                if (line.startsWith("data:")) {
                    out.add(mapper.readTree(line.substring(5)));
                }
            }
        }
        return out;
    }

    @Test
    void aSuccessfulRunIsObservableStageByStageWithAStrictlyIncreasingRevision() throws Exception {
        var s = startRun();
        JsonNode first = snapshot(s);
        assertThat(first.get("venueId").asText()).isEqualTo(s.c().venue().toString());
        assertThat(first.get("scanId").asText()).isEqualTo(s.scan().toString());
        assertThat(first.get("progress").get("stagesTotal").asInt()).isEqualTo(PipelineDefinition.STAGES.size());
        assertThat(first.get("progress").get("stagesSucceeded").asInt()).isZero();
        assertThat(first.get("progress").get("activeStage").asText()).isEqualTo("INPUT_VALIDATION");
        assertThat(first.get("progress").get("activeStageState").asText()).isEqualTo("QUEUED");
        assertThat(first.get("progress").get("basis").asText()).isEqualTo("COMPLETED_STAGES");
        assertThat(snapshot(s).get("revision").asLong()).as("the same state reads as the same revision").isEqualTo(first.get("revision").asLong());

        long previous = first.get("revision").asLong();
        int done = 0;
        for (var stage : PipelineDefinition.STAGES) {
            if (stage.name().equals("SEMANTIC_INDEXING")) {
                calibrateOk(s, controlPointCalibration(0));
            }
            JsonNode order = claimExpecting(stage.name());
            JsonNode running = snapshot(s);
            assertThat(running.get("revision").asLong()).isGreaterThan(previous);
            assertThat(running.get("progress").get("activeStage").asText()).isEqualTo(stage.name());
            assertThat(running.get("progress").get("activeStageState").asText()).isEqualTo("RUNNING");
            assertThat(running.get("activeJobs")).singleElement().satisfies(j -> {
                assertThat(j.get("stage").asText()).isEqualTo(stage.name());
                assertThat(j.get("leaseExpired").asBoolean()).isFalse();
            });
            // A heartbeat renews the lease and changes nothing the status shows.
            heartbeat(order, order.get("leaseToken").asText()).andExpect(status().isOk());
            assertThat(snapshot(s).get("revision").asLong()).isEqualTo(running.get("revision").asLong());
            succeed(order);
            JsonNode after = snapshot(s);
            assertThat(after.get("revision").asLong()).isGreaterThan(running.get("revision").asLong());
            assertThat(after.get("progress").get("stagesSucceeded").asInt()).isEqualTo(++done);
            previous = after.get("revision").asLong();
        }
        JsonNode end = snapshot(s);
        assertThat(end.get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(end.get("progress").get("activeStage").isNull()).isTrue();
        assertThat(end.get("activeJobs")).isEmpty();
        assertThat(end.get("run").get("stages").findValues("artifacts")).as("every stage lists its outputs")
            .allSatisfy(a -> assertThat(a).isNotEmpty());
    }

    @Test
    void aStageFailureIsReportedWithItsCodeAndWhetherItCanBeRetriedThenARetryStartsAttemptTwo() throws Exception {
        var s = startRun();
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        send(order, report("FAILED", List.of(), "INPUT_INVALID", "the video could not be decoded"), svc).andExpect(status().isOk());
        JsonNode failed = snapshot(s);
        assertThat(failed.get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("run").get("failureStage").asText()).isEqualTo("INPUT_VALIDATION");
        assertThat(failed.get("run").get("failureCode").asText()).isEqualTo("INPUT_INVALID");
        assertThat(failed.get("progress").get("stagesFailed").asInt()).isEqualTo(1);
        assertThat(failed.get("run").get("retryable").asBoolean()).as("a failure the server will re-queue").isTrue();
        int left = failed.get("run").get("retriesRemaining").asInt();
        assertThat(left).isPositive();
        assertThat(failed.get("run").get("retryBlockedReason").isNull()).isTrue();

        post(processingUrl(s) + "/retry", s.c().operator(), null).andExpect(status().is2xxSuccessful());
        JsonNode retried = snapshot(s);
        assertThat(retried.get("revision").asLong()).isGreaterThan(failed.get("revision").asLong());
        assertThat(retried.get("run").get("status").asText()).isEqualTo("RUNNING");
        assertThat(retried.get("progress").get("activeStageAttempt").asInt()).isEqualTo(2);
        assertThat(retried.get("run").get("retryable").asBoolean()).as("a running run is not retried").isFalse();

        // Every retry the stage is allowed, each failing again: then the status says it cannot be retried, and why,
        // exactly when the retry endpoint starts refusing.
        for (int i = 1; ; i++) {
            send(claimExpecting("INPUT_VALIDATION"), report("FAILED", List.of(), "INPUT_INVALID", "still undecodable"), svc)
                .andExpect(status().isOk());
            JsonNode again = snapshot(s);
            assertThat(again.get("run").get("retriesRemaining").asInt()).isEqualTo(left - i);
            if (left - i == 0) {
                assertThat(again.get("run").get("retryable").asBoolean()).isFalse();
                assertThat(again.get("run").get("retryBlockedReason").asText()).isEqualTo("RETRIES_EXHAUSTED");
                post(processingUrl(s) + "/retry", s.c().operator(), null).andExpect(status().isConflict());
                break;
            }
            assertThat(again.get("run").get("retryable").asBoolean()).isTrue();
            post(processingUrl(s) + "/retry", s.c().operator(), null).andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void aCrashedWorkersExpiredLeaseIsShownThenTheStageFailsAsWorkerLostNeverSucceeded() throws Exception {
        var s = startRun();
        claimExpecting("INPUT_VALIDATION");
        MvcResult stream = openStream(s, s.c().operator(), null);
        long running = events(stream).get(0).get("revision").asLong();
        // The worker process dies: no report, no heartbeat. Its lease runs out.
        jdbc.sql("UPDATE processing_job SET lease_expires_at = now() - interval '5 seconds' WHERE scan_id = :s AND status = 'RUNNING'")
            .param("s", s.scan()).update();
        JsonNode silent = snapshot(s);
        assertThat(silent.get("revision").asLong()).as("nothing the status shows was written: time ran out").isEqualTo(running);
        assertThat(silent.get("activeJobs")).singleElement().satisfies(j -> assertThat(j.get("leaseExpired").asBoolean()).isTrue());
        // The stream still tells subscribers: the lease's expiry changes what the status shows.
        broadcaster.tick();
        assertThat(events(stream)).hasSize(2).last().satisfies(e -> {
            assertThat(e.get("revision").asLong()).isEqualTo(running);
            assertThat(e.get("activeJobs").get(0).get("leaseExpired").asBoolean()).isTrue();
        });
        assertThat(stageState(silent, "INPUT_VALIDATION")).as("not failed until the enforcer settles it, never succeeded").isEqualTo("RUNNING");

        pipeline.enforceLeasesAndDeadlines();
        JsonNode lost = snapshot(s);
        assertThat(lost.get("revision").asLong()).isGreaterThan(silent.get("revision").asLong());
        assertThat(lost.get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(lost.get("run").get("failureCode").asText()).isEqualTo("WORKER_LOST");
        assertThat(lost.get("run").get("retryable").asBoolean()).isTrue();
        assertThat(stageState(lost, "INPUT_VALIDATION")).isEqualTo("FAILED");
        assertThat(lost.get("activeJobs")).isEmpty();
        broadcaster.tick();
        assertThat(events(stream)).last().satisfies(e -> assertThat(e.get("run").get("failureCode").asText()).isEqualTo("WORKER_LOST"));
    }

    @Test
    void aStageStillRunningAfterTheTimeBudgetIsInterruptedNotSucceeded() throws Exception {
        var s = startRun();
        claimExpecting("INPUT_VALIDATION");
        long before = snapshot(s).get("revision").asLong();
        jdbc.sql("UPDATE pipeline_run SET deadline_at = now() - interval '1 day' WHERE scan_id = :s").param("s", s.scan()).update();
        pipeline.enforceLeasesAndDeadlines();
        JsonNode over = snapshot(s);
        assertThat(over.get("revision").asLong()).isGreaterThan(before);
        assertThat(over.get("run").get("status").asText()).as("no reconstruction yet, so nothing partial to keep").isEqualTo("FAILED");
        assertThat(over.get("run").get("failureCode").asText()).isEqualTo("TIME_LIMIT_EXCEEDED");
        assertThat(stageState(over, "INPUT_VALIDATION")).isEqualTo("FAILED");
        assertThat(over.get("progress").get("stagesSucceeded").asInt()).isZero();
        assertThat(over.get("activeJobs")).isEmpty();
    }

    @Test
    void aCancelledRunStopsAndSaysSo() throws Exception {
        var s = startRun();
        claimExpecting("INPUT_VALIDATION");
        long before = snapshot(s).get("revision").asLong();
        post(processingUrl(s) + "/cancel", s.c().operator(), null).andExpect(status().is2xxSuccessful());
        JsonNode cancelled = snapshot(s);
        assertThat(cancelled.get("run").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("revision").asLong()).isGreaterThan(before);
        assertThat(cancelled.get("progress").get("activeStage").isNull()).isTrue();
    }

    @Test
    void theStreamSendsTheSnapshotFirstThenEachChangeAndAReconnectWithTheCurrentRevisionGetsNoDuplicate() throws Exception {
        var s = startRun();
        long r0 = snapshot(s).get("revision").asLong();
        MvcResult stream = openStream(s, s.c().operator(), null);
        assertThat(stream.getResponse().getContentAsString()).contains("id:" + r0);
        assertThat(events(stream)).singleElement().satisfies(e -> assertThat(e.get("revision").asLong()).isEqualTo(r0));

        // A change is pushed on the next tick; ticks with no change push nothing but heartbeats.
        JsonNode order = claimExpecting("INPUT_VALIDATION");
        broadcaster.tick();
        broadcaster.tick();
        List<JsonNode> pushed = events(stream);
        assertThat(pushed).hasSize(2);
        assertThat(pushed.get(1).get("revision").asLong()).isGreaterThan(r0);
        assertThat(pushed.get(1).get("progress").get("activeStageState").asText()).isEqualTo("RUNNING");
        long r1 = pushed.get(1).get("revision").asLong();

        // Reconnecting with the revision already held: no duplicate. With an older one (events were missed): the current one.
        MvcResult upToDate = openStream(s, s.c().operator(), Long.toString(r1));
        assertThat(events(upToDate)).isEmpty();
        assertThat(upToDate.getResponse().getContentAsString()).contains("up to date");
        MvcResult behind = openStream(s, s.c().operator(), Long.toString(r0));
        assertThat(events(behind)).singleElement().satisfies(e -> assertThat(e.get("revision").asLong()).isEqualTo(r1));
        succeed(order);
    }

    @Test
    void onlyAuthorizedCallersCanSubscribeAndAStreamEndsWhenItsTokenExpires() throws Exception {
        var s = startRun();
        int open = broadcaster.subscriberCount();
        // Another organization's operator: the capture does not exist for them. A viewer may not read processing at all.
        String outsider = TestJwt.user(fx.organization(), "operator").token();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(processingUrl(s) + "/stream").header("Authorization", "Bearer " + outsider)).andExpect(status().isNotFound());
        String viewer = TestJwt.user(s.c().org(), "viewer").venues(s.c().venue()).token();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(processingUrl(s) + "/stream").header("Authorization", "Bearer " + viewer)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(processingUrl(s) + "/stream")).andExpect(status().isUnauthorized());
        // The same organization, but an operator of another venue only: through either venue, the capture is not there.
        java.util.UUID venue2 = fx.venue(s.c().org());
        String otherVenue = TestJwt.user(s.c().org(), "operator").venues(venue2).token();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(processingUrl(s) + "/stream").header("Authorization", "Bearer " + otherVenue)).andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/venues/" + venue2 + "/captures/" + s.capture() + "/processing/stream")
            .header("Authorization", "Bearer " + otherVenue)).andExpect(status().isNotFound());
        assertThat(broadcaster.subscriberCount()).as("no refused caller holds a stream").isEqualTo(open);
        String expired = TestJwt.user(s.c().org(), "operator").venues(s.c().venue()).lifetime(Duration.ofMinutes(-5)).token(); // beyond the 60 s clock skew JWT validation allows
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(processingUrl(s) + "/stream").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());

        // A token that expires while the stream is open: the stream is told and ends.
        String shortLived = TestJwt.user(s.c().org(), "operator").venues(s.c().venue()).lifetime(Duration.ofSeconds(2)).token();
        MvcResult stream = openStream(s, shortLived, null);
        Thread.sleep(2_500);
        broadcaster.tick();
        assertThat(stream.getResponse().getContentAsString()).contains("event:session-expired");
    }

    /**
     * One real run, recorded as the API answered it, for the web panel's replay spec (apps/web/e2e/processing-live.spec.ts):
     * the snapshot and capture the page loads, the exact bytes the stream wrote after each step, and the answers to retry
     * and cancel. Nothing in it is hand-written. -Dchaya.processing.fixture=PATH writes it there; without the property the
     * run is only checked.
     */
    @Test
    void aRunRecordedAsTheApiStreamedItForTheWebReplay() throws Exception {
        var s = startRun();
        String op = s.c().operator();
        var out = mapper.createObjectNode();
        out.put("note", "Recorded by ProcessingLiveStatusTest#aRunRecordedAsTheApiStreamedItForTheWebReplay against real "
            + "PostgreSQL and MinIO: the API's own answers and stream bytes. Regenerate it, do not edit it.");
        out.put("venueId", s.c().venue().toString());
        out.put("floorId", s.c().floor().toString());
        out.put("captureId", s.capture().toString());
        out.set("captureList", mapper.readTree(get("/api/v1/venues/" + s.c().venue() + "/captures", op).andReturn().getResponse().getContentAsString()));
        out.set("capture", mapper.readTree(get(capUrl(s.c(), s.capture()), op).andReturn().getResponse().getContentAsString()));
        out.set("snapshot", snapshot(s));

        MvcResult stream = openStream(s, op, null);
        var steps = mapper.createArrayNode();
        int[] seen = {0};
        java.util.function.Consumer<String> step = name -> {
            try {
                broadcaster.tick();
                String all = stream.getResponse().getContentAsString();
                steps.addObject().put("name", name).put("sse", all.substring(seen[0]));
                seen[0] = all.length();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        step.accept("queued");
        JsonNode first = claimExpecting(PipelineDefinition.STAGES.get(0).name());
        step.accept("first-stage-running");
        succeed(first);
        step.accept("first-stage-succeeded");
        claimExpecting(PipelineDefinition.STAGES.get(1).name());
        step.accept("second-stage-running");
        jdbc.sql("UPDATE processing_job SET lease_expires_at = now() - interval '5 seconds' WHERE scan_id = :s AND status = 'RUNNING'")
            .param("s", s.scan()).update();
        step.accept("second-stage-lease-expired");
        pipeline.enforceLeasesAndDeadlines();
        step.accept("worker-lost");
        out.set("retryAnswer", mapper.readTree(post(processingUrl(s) + "/retry", op, null).andExpect(status().is2xxSuccessful())
            .andReturn().getResponse().getContentAsString()));
        step.accept("retried");
        claimExpecting(PipelineDefinition.STAGES.get(1).name());
        step.accept("second-stage-attempt-2-running");
        out.set("cancelAnswer", mapper.readTree(post(processingUrl(s) + "/cancel", op, null).andExpect(status().is2xxSuccessful())
            .andReturn().getResponse().getContentAsString()));
        out.set("captureAfterCancel", mapper.readTree(get(capUrl(s.c(), s.capture()), op).andReturn().getResponse().getContentAsString()));
        step.accept("cancelled");
        out.set("steps", steps);

        // What the replay relies on: each step after the first pushed exactly one new status, and they are ordered.
        long last = -1;
        for (JsonNode st : steps) {
            List<String> ids = st.get("sse").asText().lines().filter(l -> l.startsWith("id:")).toList();
            assertThat(ids).as(st.get("name").asText()).hasSize(1);
            long id = Long.parseLong(ids.get(0).substring(3));
            assertThat(id).as(st.get("name").asText()).isGreaterThanOrEqualTo(last);
            last = id;
        }
        assertThat(out.get("cancelAnswer").get("run").get("status").asText()).isEqualTo("CANCELLED");

        String target = System.getProperty("chaya.processing.fixture");
        if (target != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(target), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        }
    }

    @Test
    void concurrentRunsEachStreamOnlyTheirOwnCapture() throws Exception {
        var a = startRun();
        var b = startRun();
        MvcResult streamA = openStream(a, a.c().operator(), null);
        MvcResult streamB = openStream(b, b.c().operator(), null);
        // The queue interleaves the two runs' jobs; each claim changes only its own capture's status.
        for (int i = 0; i < 2; i++) {
            claim();
        }
        broadcaster.tick();
        assertThat(events(streamA)).allSatisfy(e -> assertThat(e.get("captureId").asText()).isEqualTo(a.capture().toString()));
        assertThat(events(streamB)).allSatisfy(e -> assertThat(e.get("captureId").asText()).isEqualTo(b.capture().toString()));
        assertThat(events(streamA).get(events(streamA).size() - 1).get("progress").get("activeStageState").asText()).isEqualTo("RUNNING");
        assertThat(events(streamB).get(events(streamB).size() - 1).get("progress").get("activeStageState").asText()).isEqualTo("RUNNING");
        assertThat(streamA.getResponse().getContentAsString()).doesNotContain(b.capture().toString());
        assertThat(streamB.getResponse().getContentAsString()).doesNotContain(a.capture().toString());
    }
}
