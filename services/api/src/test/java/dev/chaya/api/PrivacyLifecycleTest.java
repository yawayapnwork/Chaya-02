package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.pipeline.PipelineDefinition;
import dev.chaya.api.pipeline.PipelineService;
import dev.chaya.api.processing.JobStage;
import dev.chaya.api.reconstruction.ReconstructionService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.web.NotFoundException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Review S-7 (PII staging never outlives the run that needs it) and S-6 (a reconstruction trained without privacy
 * preprocessing never reaches an anonymous public link), against real PostgreSQL and real MinIO objects.
 */
class PrivacyLifecycleTest extends PipelineTestSupport {

    @Autowired PipelineService pipeline;
    @Autowired ReconstructionService reconstructions;

    private List<String> piiKeys(UUID run) {
        return jdbc.sql("SELECT a.object_key FROM processing_artifact a JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id "
            + "WHERE sr.run_id = :r AND a.contains_pii").param("r", run).query(String.class).list();
    }

    private void endedHoursAgo(UUID run, int hours) {
        jdbc.sql("UPDATE pipeline_run SET finished_at = now() - make_interval(hours => :h) WHERE id = :r")
            .param("h", hours).param("r", run).update();
    }

    // ---- S-7 -------------------------------------------------------------------------------------------------------

    @Test
    void aRunThatFailsBeforePrivacyKeepsItsPiiStagingOnlyForTheRetentionPeriod() throws Exception {
        var s = startRun();
        succeed(claimExpecting("INPUT_VALIDATION"));
        succeed(claimExpecting("FFMPEG_PREPROCESS"));
        JsonNode quality = claimExpecting("FRAME_QUALITY_FILTER");
        send(quality, report("FAILED", List.of(), "INSUFFICIENT_QUALITY_FRAMES", "too few sharp frames"), svc).andExpect(status().isOk());
        List<String> pii = piiKeys(s.run());
        assertThat(pii).isNotEmpty();

        // Within the retention period the unanonymised frames stay, so the run can be retried.
        pipeline.sweepPiiStaging();
        assertThat(pii).allMatch(this::existsInDerived);

        // Past it, the sweep deletes them, records each deletion and audits it.
        endedHoursAgo(s.run(), 25);
        assertThat(pipeline.sweepPiiStaging()).isEqualTo(1);
        assertThat(pii).noneMatch(this::existsInDerived);
        assertThat(jdbc.sql("SELECT count(*) FROM pii_staging_purge WHERE run_id = :r").param("r", s.run()).query(Integer.class).single())
            .isEqualTo(pii.size());
        assertThat(jdbc.sql("SELECT metadata->>'reason' FROM audit_log WHERE action = 'pipeline.pii_purged' AND resource_id = :r")
            .param("r", s.run()).query(String.class).list()).containsExactly("retention-sweep");
        assertThat(pipeline.sweepPiiStaging()).as("idempotent: nothing is due any more").isZero();

        // A retry that would need the deleted frames is refused with a reason, not handed to a worker to fail obscurely.
        post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PII_STAGING_PURGED"));
        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void aRunThatFailsAfterPrivacyIsStillRetryableOnceItsStagingIsGone() throws Exception {
        var s = startRun();
        runThrough("PRIVACY_PREPROCESS");
        List<String> pii = piiKeys(s.run());
        assertThat(pii).noneMatch(this::existsInDerived);
        JsonNode pose = claimExpecting("POSE_ESTIMATION");
        send(pose, report("FAILED", List.of(), "MAPPING_FAILED", "no model"), svc).andExpect(status().isOk());
        endedHoursAgo(s.run(), 48);
        pipeline.sweepPiiStaging();
        post(capUrl(s.c(), s.capture()) + "/processing/retry", s.c().operator(), null).andExpect(status().isAccepted());
        JsonNode again = claimExpecting("POSE_ESTIMATION");
        assertThat(again.get("inputs").findValuesAsText("containsPii")).containsOnly("false");
    }

    @Test
    void aCancelledRunsStagingIsPurgedAtOnce() throws Exception {
        var s = startRun();
        succeed(claimExpecting("INPUT_VALIDATION"));
        succeed(claimExpecting("FFMPEG_PREPROCESS"));
        List<String> pii = piiKeys(s.run());
        post(capUrl(s.c(), s.capture()) + "/processing/cancel", s.c().operator(), null).andExpect(status().isOk());
        assertThat(pii).isNotEmpty().noneMatch(this::existsInDerived);
        assertThat(pipeline.sweepPiiStaging()).isZero();
    }

    // ---- S-6 (and S-7 for a privacy-disabled run) ------------------------------------------------------------------

    private Started startWithoutPrivacy() throws Exception {
        var c = ctx();
        UUID capture = newCapture(c);
        var u = upload(c, capture, "VIDEO", "walk.mp4", "video/mp4", mp4(4096));
        awaitSettled(c, capture, u.mediaId());
        post(capUrl(c, capture) + "/complete-upload", c.operator(), null).andExpect(status().isOk());
        String admin = TestJwt.user(c.org(), "admin").venues(c.venue()).token();
        JsonNode n = mapper.readTree(post(capUrl(c, capture) + "/processing", admin, "{\"privacyEnabled\":false}")
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        return new Started(c, capture, UUID.fromString(n.get("scanId").asText()), UUID.fromString(n.get("run").get("id").asText()));
    }

    /** Runs every stage, calibrating the reconstruction before the last one, so the run succeeds with a canonical frame and
     * is published as its floor's current scan version (V28): only a published version is ever shown to a viewer. */
    private void runPlan(Started s, List<JobStage> plan) throws Exception {
        for (JobStage stage : plan) {
            if (stage == plan.get(plan.size() - 1)) {
                calibrateOk(s, controlPointCalibration(0));
            }
            succeed(claimExpecting(stage.name()));
        }
    }

    @Test
    void aPrivacyDisabledReconstructionIsNeverServedToAPublicLinkAndKeepsNoStaging() throws Exception {
        var s = startWithoutPrivacy();
        runPlan(s, PipelineDefinition.plan(false));
        assertThat(processing(s).get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        // S-7: nothing anonymised these frames, and the run no longer needs them.
        assertThat(piiKeys(s.run())).isNotEmpty().noneMatch(this::existsInDerived);

        Actor member = new Actor(Actor.Kind.USER, "viewer", s.c().org(), Set.of(s.c().venue()), Set.of(Role.VIEWER));
        Actor publicLink = new Actor(Actor.Kind.PUBLIC_VIEWER, "public-link:" + UUID.randomUUID(), s.c().org(),
            Set.of(s.c().venue()), Set.of(Role.PUBLIC_VIEWER));
        assertThat(reconstructions.listForFloor(member, s.c().venue(), s.c().floor())).hasSize(1);
        assertThat(reconstructions.get(member, s.c().venue(), s.run()).artifacts()).isNotEmpty();
        assertThat(reconstructions.artifactBytes(member, s.c().venue(), s.run(), "KSPLAT")).isNotNull();

        assertThat(reconstructions.listForFloor(publicLink, s.c().venue(), s.c().floor())).isEmpty();
        assertThatThrownBy(() -> reconstructions.latestForFloor(publicLink, s.c().venue(), s.c().floor()))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> reconstructions.get(publicLink, s.c().venue(), s.run())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> reconstructions.artifactBytes(publicLink, s.c().venue(), s.run(), "KSPLAT"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aPrivacyEnabledReconstructionIsServedToAPublicLink() throws Exception {
        var s = startRun();
        runPlan(s, PipelineDefinition.STAGES);
        Actor publicLink = new Actor(Actor.Kind.PUBLIC_VIEWER, "public-link:" + UUID.randomUUID(), s.c().org(),
            Set.of(s.c().venue()), Set.of(Role.PUBLIC_VIEWER));
        assertThat(reconstructions.listForFloor(publicLink, s.c().venue(), s.c().floor())).hasSize(1);
        assertThat(reconstructions.artifactBytes(publicLink, s.c().venue(), s.run(), "KSPLAT")).isNotNull();
    }
}
