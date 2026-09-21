package dev.chaya.api.scan;

import dev.chaya.api.audit.AuditService;
import dev.chaya.api.processing.JobStage;
import dev.chaya.api.processing.ProcessingJobRepository;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.web.NotFoundException;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Scans and the control of their processing jobs. Every method is venue-guarded and audited. */
@Service
public class ScanService {

    public record CreatedScan(UUID scanId, UUID captureSessionId) {}

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;
    private final ProcessingJobRepository jobs;

    public ScanService(JdbcClient jdbc, TenantGuard guard, AuditService audit, ProcessingJobRepository jobs) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.jobs = jobs;
    }

    @Transactional
    public CreatedScan createScan(Actor actor, UUID venueId, UUID floorId) {
        guard.requireVenue(actor, venueId);
        if (floorId != null && jdbc.sql("SELECT count(*) FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
                .param("f", floorId).param("v", venueId).query(Integer.class).single() == 0) {
            throw new NotFoundException("floor not found");
        }
        UUID session = jdbc.sql("INSERT INTO capture_session (organization_id, venue_id, floor_id, operator_id) "
                + "VALUES (:o, :v, :f, :op) RETURNING id")
            .param("o", actor.organizationId()).param("v", venueId).param("f", floorId).param("op", actor.subject())
            .query(UUID.class).single();
        UUID scan = jdbc.sql("INSERT INTO scan (organization_id, venue_id, capture_session_id) VALUES (:o, :v, :c) RETURNING id")
            .param("o", actor.organizationId()).param("v", venueId).param("c", session).query(UUID.class).single();
        audit.success(actor, venueId, "scan.create", "scan", scan, Map.of("captureSessionId", session.toString()));
        return new CreatedScan(scan, session);
    }

    @Transactional
    public UUID enqueueJob(Actor actor, UUID venueId, UUID scanId, JobStage stage) {
        guard.requireVenue(actor, venueId);
        requireScan(actor, venueId, scanId);
        UUID job = jobs.enqueue(actor.organizationId(), venueId, scanId, null, stage);
        audit.success(actor, venueId, "job.enqueue", "processing_job", job,
            Map.of("stage", stage.name(), "scanId", scanId.toString()));
        return job;
    }

    @Transactional
    public void cancelJob(Actor actor, UUID venueId, UUID jobId) {
        guard.requireVenue(actor, venueId);
        requireJob(actor, venueId, jobId);
        jobs.cancel(jobId);
        audit.success(actor, venueId, "job.cancel", "processing_job", jobId, Map.of());
    }

    @Transactional
    public void retryJob(Actor actor, UUID venueId, UUID jobId) {
        guard.requireVenue(actor, venueId);
        requireJob(actor, venueId, jobId);
        jobs.retry(jobId);
        audit.success(actor, venueId, "job.retry", "processing_job", jobId, Map.of());
    }

    private void requireScan(Actor actor, UUID venueId, UUID scanId) {
        if (jdbc.sql("SELECT count(*) FROM scan WHERE id = :s AND venue_id = :v AND organization_id = :o")
                .param("s", scanId).param("v", venueId).param("o", actor.organizationId())
                .query(Integer.class).single() == 0) {
            throw new NotFoundException("scan not found");
        }
    }

    private void requireJob(Actor actor, UUID venueId, UUID jobId) {
        if (jdbc.sql("SELECT count(*) FROM processing_job WHERE id = :j AND venue_id = :v AND organization_id = :o")
                .param("j", jobId).param("v", venueId).param("o", actor.organizationId())
                .query(Integer.class).single() == 0) {
            throw new NotFoundException("job not found");
        }
    }
}
