package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.audit.AuditService;
import dev.chaya.api.erasure.ErasureObjectPurger;
import dev.chaya.api.erasure.ErasureService;
import dev.chaya.api.erasure.ErasureService.ErasureView;
import dev.chaya.api.erasure.ErasureService.Target;
import dev.chaya.api.pipeline.PipelineDefinition;
import dev.chaya.api.pipeline.PipelineService;
import dev.chaya.api.processing.JobStage;
import dev.chaya.api.rescan.ScanVersionService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.storage.ObjectStore;
import dev.chaya.api.storage.StorageException;
import dev.chaya.api.storage.StorageProperties;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Review E-1 (data erasure) and S-7 (PII staging survives failed runs), against real PostgreSQL and real MinIO objects:
 * every row and object a capture, a scan version or a venue owns is gone afterwards -- not flagged -- including objects
 * no row names; a storage failure leaves a durable, retried erasure; a refused request changes nothing; public links stop
 * reaching erased data.
 */
class ErasureTest extends PipelineTestSupport {

    @Autowired ObjectStore raw;
    @Autowired ErasureObjectPurger purger;
    @Autowired PipelineService pipeline;
    @Autowired TenantGuard guard;
    @Autowired AuditService audit;
    @Autowired TransactionTemplate tx;
    @Autowired ScanVersionService versions;
    @Autowired StorageProperties storage;
    @Autowired ObjectMapper objectMapper;

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private String manager(Ctx c) {
        return TestJwt.user(c.org(), "venue-manager").venues(c.venue()).token();
    }

    private String admin(Ctx c) {
        return TestJwt.user(c.org(), "admin").token();
    }

    private ResultActions delete(String url, String bearer) throws Exception {
        return call(HttpMethod.DELETE, url, bearer, null);
    }

    private JsonNode json(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** Runs every stage, calibrating before the first metric stage, so the run succeeds and is published as its floor's current version. */
    private Started published() throws Exception {
        var s = startRun();
        List<JobStage> plan = PipelineDefinition.STAGES;
        for (JobStage stage : plan) {
            if (stage == JobStage.SEMANTIC_INDEXING) { // the first stage in canonical metres, as with the real worker
                calibrateOk(s, controlPointCalibration(0));
            }
            succeed(claimExpecting(stage.name()));
        }
        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        return s;
    }

    private UUID versionOf(Started s) {
        return jdbc.sql("SELECT id FROM scan_version WHERE pipeline_run_id = :r").param("r", s.run()).query(UUID.class).single();
    }

    private String own(Ctx c) {
        return "org/" + c.org() + "/venue/" + c.venue() + "/";
    }

    /** Every object under the venue's prefixes, in both buckets. */
    private List<String> objectsOf(Ctx c) {
        List<String> all = new ArrayList<>();
        for (ObjectStore store : List.of(raw, derived)) {
            all.addAll(store.list(own(c)));
            all.addAll(store.list("sealed/" + own(c)));
        }
        return all;
    }

    private static void put(ObjectStore store, String key, String content) {
        String upload = store.beginMultipart(key, "application/octet-stream");
        String etag = store.uploadPart(key, upload, 1, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        store.completeMultipart(key, upload, List.of(new ObjectStore.PartEtag(1, etag)));
    }

    private int count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Integer.class).single();
    }

    /** A detected object of the run's version (with an image embedding, as SEMANTIC_INDEXING produces) and a staff POI
     * placed against the same version. Returns {detectedPoi, manualPoi}. */
    private UUID[] poisOn(Started s, UUID version) {
        Ctx c = s.c();
        UUID[] ids = new UUID[2];
        for (int i = 0; i < 2; i++) {
            boolean detected = i == 0;
            UUID poi = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
                .param("o", c.org()).param("v", c.venue()).param("f", c.floor()).query(UUID.class).single();
            jdbc.sql("""
                    INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, x, y, z, created_by, source,
                        pipeline_run_id, scan_version_id, image_embedding, image_embedding_model)
                    VALUES (:o, :v, :p, 1, :label, 1, 2, 0, 'tester', :src, :run, :sv,
                        CASE WHEN :detected THEN CAST(array_fill(0.05, ARRAY[512]) AS vector) END,
                        CASE WHEN :detected THEN 'clip-test' END)
                    """)
                .param("o", c.org()).param("v", c.venue()).param("p", poi).param("label", detected ? "laptop" : "Reception")
                .param("src", detected ? "AUTO_DETECTED" : "MANUAL").param("run", detected ? s.run() : null)
                .param("sv", version).param("detected", detected).update();
            ids[i] = poi;
        }
        return ids;
    }

    // ---- successful deletion ----------------------------------------------------------------------------------------

    @Test
    void erasingACaptureRemovesEveryRowAndObjectDerivedFromItAndIsIdempotent() throws Exception {
        Started s = published();
        Ctx c = s.c();
        UUID version = versionOf(s);
        UUID[] pois = poisOn(s, version);
        // A stage that uploaded unanonymised frames and never registered them: no row names this object.
        String orphan = own(c) + "scan/" + s.scan() + "/run/" + s.run() + "/FFMPEG_PREPROCESS/attempt-9/pii/frame-000001.png";
        put(derived, orphan, "unblurred");
        List<String> before = objectsOf(c);
        assertThat(before).contains(orphan).anyMatch(k -> k.contains("/capture/" + s.capture() + "/raw/"))
            .anyMatch(k -> k.startsWith("sealed/"));

        JsonNode erasure = json(delete(capUrl(c, s.capture()), manager(c)).andExpect(status().isOk()));
        assertThat(erasure.get("status").asText()).isEqualTo("COMPLETED");

        // Rows: deleted, not flagged.
        assertThat(count("SELECT count(*) FROM capture_session WHERE id = :id", s.capture())).isZero();
        assertThat(count("SELECT count(*) FROM capture_media WHERE capture_session_id = :id", s.capture())).isZero();
        assertThat(count("SELECT count(*) FROM scan WHERE id = :id", s.scan())).isZero();
        assertThat(count("SELECT count(*) FROM pipeline_run WHERE id = :id", s.run())).isZero();
        assertThat(count("SELECT count(*) FROM processing_job WHERE scan_id = :id", s.scan())).isZero();
        assertThat(count("SELECT count(*) FROM processing_artifact WHERE scan_id = :id", s.scan())).isZero();
        assertThat(count("SELECT count(*) FROM pipeline_stage_run WHERE run_id = :id", s.run())).isZero();
        assertThat(count("SELECT count(*) FROM scan_version WHERE id = :id", version)).isZero();
        assertThat(count("SELECT count(*) FROM coordinate_frame WHERE source_run_id = :id", s.run())).isZero();
        assertThat(count("SELECT count(*) FROM pii_staging_purge WHERE run_id = :id", s.run())).isZero();
        // The detected object and its embedding (the vector index) are gone; the staff POI stays, detached.
        assertThat(count("SELECT count(*) FROM poi WHERE id = :id", pois[0])).isZero();
        assertThat(count("SELECT count(*) FROM poi_version WHERE image_embedding IS NOT NULL AND venue_id = :id", c.venue())).isZero();
        assertThat(count("SELECT count(*) FROM poi_version WHERE scan_version_id IS NULL AND poi_id = :id", pois[1])).isOne();
        // The floor no longer publishes it.
        assertThat(count("SELECT count(*) FROM floor WHERE current_scan_version_id IS NULL AND id = :id", c.floor())).isOne();

        // Objects: every one, including the unregistered pii/ upload and the sealed copies.
        assertThat(objectsOf(c)).isEmpty();
        assertThat(before).allMatch(k -> !existsInDerived(k));

        // Unreachable through the API.
        get(capUrl(c, s.capture()), c.operator()).andExpect(status().isNotFound());
        get("/api/v1/venues/" + c.venue() + "/scan-versions/" + version + "/reconstruction", manager(c)).andExpect(status().isNotFound());
        get("/api/v1/venues/" + c.venue() + "/reconstructions/" + s.run(), manager(c)).andExpect(status().isNotFound());

        // Audited once, with counts and ids only: nothing the capture contained.
        List<String> audits = jdbc.sql("SELECT metadata::text FROM audit_log WHERE action = 'erasure.capture' AND resource_id = :id")
            .param("id", s.capture()).query(String.class).list();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).contains(erasure.get("id").asText()).doesNotContain("walk.mp4", "test-phone", "laptop", "Reception");

        // Idempotent: the same erasure again, nothing new.
        JsonNode again = json(delete(capUrl(c, s.capture()), manager(c)).andExpect(status().isOk()));
        assertThat(again.get("id").asText()).isEqualTo(erasure.get("id").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'erasure.capture' AND resource_id = :id")
            .param("id", s.capture()).query(Integer.class).single()).isEqualTo(1);
        get("/api/v1/erasures/" + erasure.get("id").asText(), c.operator()).andExpect(status().isForbidden());
        get("/api/v1/erasures/" + erasure.get("id").asText(), admin(c)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void erasingACaptureWithReScansNeedsCascadeAndThenErasesThemToo() throws Exception {
        Started s = published();
        Ctx c = s.c();
        UUID version = versionOf(s);
        String rescan = post("/api/v1/venues/" + c.venue() + "/floors/" + c.floor() + "/rescan", c.operator(),
                "{\"parentVersionId\":\"" + version + "\",\"region\":{\"points\":[[0,0],[0,3],[3,3],[3,0]]}}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID dependent = UUID.fromString(field(rescan, "captureId"));

        delete(capUrl(c, s.capture()), manager(c)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ERASURE_HAS_DEPENDENTS"));
        assertThat(count("SELECT count(*) FROM capture_session WHERE id = :id", s.capture())).as("refused: nothing erased").isOne();

        JsonNode erasure = json(delete(capUrl(c, s.capture()) + "?cascade=true", manager(c)).andExpect(status().isOk()));
        assertThat(erasure.get("summary").get("dependentCaptures").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM capture_session WHERE id = :id", dependent)).isZero();
        // The dependent is reported as erased by the same request.
        assertThat(json(delete(capUrl(c, dependent), manager(c)).andExpect(status().isOk())).get("id").asText())
            .isEqualTo(erasure.get("id").asText());
    }

    // ---- partial deletion retry -------------------------------------------------------------------------------------

    /** Object storage that deletes `succeed` objects and then fails, like a store that went away mid-erasure. */
    static final class FailingAfter implements ObjectStore {
        private final ObjectStore d;
        private final AtomicInteger left;

        FailingAfter(ObjectStore delegate, int succeed) {
            this.d = delegate;
            this.left = new AtomicInteger(succeed);
        }

        @Override public void delete(String key) {
            if (left.getAndDecrement() <= 0) {
                throw new StorageException("object storage failed to delete object", new RuntimeException("connection refused"));
            }
            d.delete(key);
        }
        @Override public void ensureBucket() { d.ensureBucket(); }
        @Override public String beginMultipart(String k, String t) { return d.beginMultipart(k, t); }
        @Override public String uploadPart(String k, String u, int n, byte[] b) { return d.uploadPart(k, u, n, b); }
        @Override public void completeMultipart(String k, String u, List<PartEtag> p) { d.completeMultipart(k, u, p); }
        @Override public void abortMultipart(String k, String u) { d.abortMultipart(k, u); }
        @Override public long size(String k) { return d.size(k); }
        @Override public InputStream open(String k) { return d.open(k); }
        @Override public void copy(String a, String b) { d.copy(a, b); }
        @Override public List<String> list(String p) { return d.list(p); }
        @Override public int abortUploads(String p) { return d.abortUploads(p); }
        @Override public boolean ping() { return d.ping(); }
    }

    @Test
    void aStorageFailureLeavesADurablePendingErasureThatARetryFinishes() throws Exception {
        Started s = published();
        Ctx c = s.c();
        assertThat(objectsOf(c)).hasSizeGreaterThan(3);
        ErasureObjectPurger flaky = new ErasureObjectPurger(jdbc, new FailingAfter(raw, 0), new FailingAfter(derived, 2), storage,
            audit, tx);
        ErasureService withFlakyStorage = new ErasureService(jdbc, guard, audit, tx, flaky, versions, pipeline, storage, objectMapper);
        Actor mgr = new Actor(Actor.Kind.USER, "manager-sub", c.org(), Set.of(c.venue()), Set.of(Role.VENUE_MANAGER));

        ErasureView first = withFlakyStorage.erase(mgr, c.venue(), Target.CAPTURE, s.capture(), false);
        // The rows are gone at once; the objects are not all, and the erasure says so instead of claiming success.
        assertThat(first.status()).isEqualTo("OBJECTS_PENDING");
        assertThat(first.objectAttempts()).isEqualTo(1);
        assertThat(first.lastError()).contains("failed");
        assertThat(count("SELECT count(*) FROM capture_session WHERE id = :id", s.capture())).isZero();
        int left = objectsOf(c).size();
        assertThat(left).isPositive();
        assertThat(count("SELECT count(*) FROM erasure_object WHERE request_id = :id AND deleted_at IS NULL", first.id())).isPositive();

        // Retrying (here: the same DELETE again; the sweep does the same every minute) deletes the rest.
        JsonNode retried = json(delete(capUrl(c, s.capture()), manager(c)).andExpect(status().isOk()));
        assertThat(retried.get("id").asText()).isEqualTo(first.id().toString());
        assertThat(retried.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(retried.get("lastError").isNull()).isTrue();
        assertThat(retried.get("summary").get("objectsDeleted").asInt()).isGreaterThanOrEqualTo(left);
        assertThat(objectsOf(c)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'erasure.completed' AND resource_id = :id")
            .param("id", first.id()).query(Integer.class).single()).isOne();
        assertThat(purger.sweep()).as("nothing left to retry").isZero();
    }

    // ---- failed processing cleanup ----------------------------------------------------------------------------------

    @Test
    void aRunThatFailedBeforePrivacyLosesEvenItsUnregisteredStagingOnceRetentionEnds() throws Exception {
        Started s = startRun();
        succeed(claimExpecting("INPUT_VALIDATION"));
        succeed(claimExpecting("FFMPEG_PREPROCESS"));
        JsonNode quality = claimExpecting("FRAME_QUALITY_FILTER");
        // The stage wrote unanonymised frames, then failed without registering them.
        String orphan = (String) artifact(quality, "frames-selected.tar", "FRAMES", true, false, "unblurred").get("key");
        send(quality, report("FAILED", List.of(), "INSUFFICIENT_QUALITY_FRAMES", "too few sharp frames"), svc).andExpect(status().isOk());

        pipeline.sweepPiiStaging();
        assertThat(existsInDerived(orphan)).as("kept within the retention period, like the registered staging").isTrue();

        jdbc.sql("UPDATE pipeline_run SET finished_at = now() - interval '25 hours' WHERE id = :r").param("r", s.run()).update();
        pipeline.sweepPiiStaging();
        assertThat(existsInDerived(orphan)).isFalse();
        assertThat(derived.list(own(s.c()) + "scan/" + s.scan() + "/")).noneMatch(k -> k.contains("/pii/"));
        assertThat(derived.list("sealed/" + own(s.c()) + "scan/" + s.scan() + "/")).noneMatch(k -> k.contains("/pii/"));
        assertThat(count("SELECT objects_deleted FROM pii_staging_sweep WHERE run_id = :id", s.run())).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'pipeline.pii_orphans_purged' AND resource_id = :id")
            .param("id", s.run()).query(Integer.class).single()).isOne();
    }

    @Test
    void aWorkerStillRunningWhenItsCaptureIsErasedLeavesNothingBehind() throws Exception {
        Started s = startRun();
        Ctx c = s.c();
        succeed(claimExpecting("INPUT_VALIDATION"));
        JsonNode running = claimExpecting("FFMPEG_PREPROCESS"); // leased, still working

        JsonNode erasure = json(delete(capUrl(c, s.capture()), manager(c)).andExpect(status().isAccepted()));
        assertThat(erasure.get("status").asText()).as("a lease was outstanding: prefixes must settle").isEqualTo("OBJECTS_PENDING");
        // The worker is told to stop, and anything it still uploads is neither registered nor kept.
        assertThat(json(heartbeat(running, running.get("leaseToken").asText())).get("keepGoing").asBoolean()).isFalse();
        Map<String, Object> late = artifact(running, "frames.tar", "FRAMES", true, false, "unblurred");
        assertThat(send(running, report("SUCCEEDED", List.of(late), null, null), svc).andReturn().getResponse().getStatus())
            .isGreaterThanOrEqualTo(400);

        purger.sweep();
        assertThat(existsInDerived((String) late.get("key"))).isFalse();
        Map<String, Object> later = artifact(running, "frames-2.tar", "FRAMES", true, false, "unblurred");
        jdbc.sql("UPDATE erasure_request SET settle_after = now() - interval '1 second' WHERE id = :id")
            .param("id", UUID.fromString(erasure.get("id").asText())).update();
        assertThat(purger.sweep()).isOne();
        assertThat(existsInDerived((String) later.get("key"))).isFalse();
        assertThat(objectsOf(c)).isEmpty();
    }

    // ---- unauthorized deletion --------------------------------------------------------------------------------------

    @Test
    void onlyAnAdminOrTheVenuesManagerMayEraseAndARefusalChangesNothing() throws Exception {
        Started s = published();
        Ctx c = s.c();
        UUID version = versionOf(s);
        int objects = objectsOf(c).size();
        String viewer = TestJwt.user(c.org(), "viewer").venues(c.venue()).token();
        String otherAdmin = TestJwt.user(fx.organization(), "admin").token();
        String otherManager = TestJwt.user(c.org(), "venue-manager").venues(fx.venue(c.org())).token();

        delete(capUrl(c, s.capture()), c.operator()).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ERASURE_NOT_PERMITTED"));
        delete(capUrl(c, s.capture()), viewer).andExpect(status().isForbidden());
        delete(capUrl(c, s.capture()), otherAdmin).andExpect(status().isNotFound());
        delete(capUrl(c, s.capture()), otherManager).andExpect(status().isNotFound());
        delete("/api/v1/venues/" + c.venue() + "/scan-versions/" + version, c.operator()).andExpect(status().isForbidden());
        delete("/api/v1/venues/" + c.venue(), manager(c)).andExpect(status().isForbidden());
        delete("/api/v1/venues/" + c.venue(), c.operator()).andExpect(status().isForbidden());
        delete("/api/v1/venues/" + c.venue(), null).andExpect(status().isUnauthorized());

        String link = post("/api/v1/venues/" + c.venue() + "/public-links", manager(c), "{\"ttl\":\"PT1H\"}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = field(post("/api/v1/public/viewer-token", null, "{\"secret\":\"" + field(link, "secret") + "\"}")
            .andReturn().getResponse().getContentAsString(), "token");
        withViewerToken(HttpMethod.DELETE, capUrl(c, s.capture()), token, null).andExpect(status().isForbidden());

        assertThat(count("SELECT count(*) FROM capture_session WHERE id = :id", s.capture())).isOne();
        assertThat(count("SELECT count(*) FROM scan_version WHERE id = :id", version)).isOne();
        assertThat(objectsOf(c)).hasSize(objects);
        assertThat(count("SELECT count(*) FROM erasure_request WHERE venue_id = :id", c.venue())).isZero();
        assertThat(auditCount(c.org(), "erasure.capture", "DENIED")).isEqualTo(2);
        assertThat(auditCount(c.org(), "erasure.venue", "DENIED")).isEqualTo(2);
    }

    // ---- public-link invalidation -----------------------------------------------------------------------------------

    @Test
    void aPublicLinkStopsReachingAnErasedVersionAndDiesWithAnErasedVenue() throws Exception {
        Started s = published();
        Ctx c = s.c();
        UUID version = versionOf(s);
        String v = "/api/v1/venues/" + c.venue();
        String link = post(v + "/public-links", manager(c), "{\"label\":\"lobby\",\"ttl\":\"PT1H\"}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String secret = field(link, "secret");
        String token = field(post("/api/v1/public/viewer-token", null, "{\"secret\":\"" + secret + "\"}")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "token");
        withViewerToken(HttpMethod.GET, v + "/scan-versions/" + version + "/reconstruction", token, null).andExpect(status().isOk());
        withViewerToken(HttpMethod.GET, v + "/scan-versions/" + version + "/artifacts/KSPLAT", token, null).andExpect(status().isOk());
        withViewerToken(HttpMethod.GET, v + "/floors/" + c.floor() + "/reconstructions/latest", token, null).andExpect(status().isOk());
        jdbc.sql("INSERT INTO search_query (organization_id, venue_id, actor_id, query_normalized, result_count, latency_ms) "
            + "VALUES (:o, :v, 'visitor-sub', 'where is the pharmacy', 0, 3)").param("o", c.org()).param("v", c.venue()).update();

        // The version goes: the same live token can no longer reach it, by id or as the floor's current reconstruction.
        delete(v + "/scan-versions/" + version, manager(c)).andExpect(status().isOk());
        withViewerToken(HttpMethod.GET, v + "/scan-versions/" + version + "/reconstruction", token, null).andExpect(status().isNotFound());
        withViewerToken(HttpMethod.GET, v + "/scan-versions/" + version + "/artifacts/KSPLAT", token, null).andExpect(status().isNotFound());
        withViewerToken(HttpMethod.GET, v + "/reconstructions/" + s.run() + "/artifacts/KSPLAT", token, null).andExpect(status().isNotFound());
        assertThat(withViewerToken(HttpMethod.GET, v + "/floors/" + c.floor() + "/reconstructions/latest", token, null)
            .andReturn().getResponse().getStatus()).isIn(404, 409);
        // Erasing a version keeps the capture it was made from (the source is erased with the capture).
        get(capUrl(c, s.capture()), c.operator()).andExpect(status().isOk());

        // The venue goes: links, tokens, search history and every object; the venue is 404 to everyone, its admin too.
        JsonNode erasure = json(delete(v, admin(c)).andExpect(status().isOk()));
        withViewerToken(HttpMethod.GET, v, token, null).andExpect(status().isUnauthorized());
        post("/api/v1/public/viewer-token", null, "{\"secret\":\"" + secret + "\"}").andExpect(status().isNotFound());
        get(v, admin(c)).andExpect(status().isNotFound());
        get(capUrl(c, s.capture()), admin(c)).andExpect(status().isNotFound());
        for (String table : List.of("public_viewer_link", "search_query", "capture_session", "floor", "poi", "scan_version",
                "processing_artifact", "pipeline_run", "coordinate_frame")) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE venue_id = :id", c.venue())).as(table).isZero();
        }
        assertThat(objectsOf(c)).isEmpty();
        assertThat(jdbc.sql("SELECT name, deleted_at IS NOT NULL FROM venue WHERE id = :v").param("v", c.venue())
            .query((rs, i) -> rs.getString(1) + "/" + rs.getBoolean(2)).single()).isEqualTo("erased/true");
        assertThat(json(delete(v, admin(c)).andExpect(status().isOk())).get("id").asText()).isEqualTo(erasure.get("id").asText());
    }

    // ---- the guard bypass stays where it belongs ---------------------------------------------------------------------

    @Test
    void theGuardsYieldOnlyToTheErasureServiceAndOnlyForItsTransaction() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> setters = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return Files.readString(p).contains("'chaya.erasure'");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(setters).extracting(p -> p.getFileName().toString()).containsExactly("ErasureService.java");
        }
        Fixtures.Tree t = fx.tree();
        // Outside an erasure the guards still refuse (the flag is transaction-local and off by default).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("DELETE FROM capture_session WHERE id = :id")
            .param("id", t.session()).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_log").update())
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
