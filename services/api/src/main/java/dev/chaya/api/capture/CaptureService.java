package dev.chaya.api.capture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.audit.AuditService;
import dev.chaya.api.pipeline.PipelineDtos.RunView;
import dev.chaya.api.pipeline.PipelineService;
import dev.chaya.api.processing.ProcessingJobRepository;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.TenantGuard;
import dev.chaya.api.storage.ObjectStore;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.web.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Capture session lifecycle: create, complete upload (with validation), start processing, inspect. */
@Service
public class CaptureService {

    public record CaptureView(UUID id, UUID venueId, UUID floorId, String operatorId, CaptureStatus status,
                              Map<String, Object> device, Instant startedAt, Instant endedAt, Double durationSeconds,
                              String qualityState, String failureCode, String failureMessage,
                              int mediaCount, int acceptedMediaCount, Instant createdAt) {}

    public record JobView(UUID id, String stage, String status, int retryCount, String errorCode,
                          String errorMessage, Instant queuedAt, Instant startedAt, Instant finishedAt) {}

    public record ProcessingStatus(UUID captureId, CaptureStatus captureStatus, UUID scanId, RunView run, List<JobView> jobs) {}

    private static final int MAX_DEVICE_JSON_BYTES = 16 * 1024;

    private static final String SELECT = """
            SELECT c.id, c.venue_id, c.floor_id, c.operator_id, c.status, c.device, c.started_at, c.ended_at,
                   c.duration_seconds, c.quality_state, c.failure_code, c.failure_message, c.created_at,
                   (SELECT count(*) FROM capture_media m WHERE m.capture_session_id = c.id) AS media_count,
                   (SELECT count(*) FROM capture_media m WHERE m.capture_session_id = c.id AND m.status = 'ACCEPTED') AS accepted_count
              FROM capture_session c
             WHERE c.venue_id = :v AND c.organization_id = :o""";

    private final JdbcClient jdbc;
    private final TenantGuard guard;
    private final AuditService audit;
    private final PipelineService pipeline;
    private final ObjectStore store;
    private final UploadProperties uploads;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public CaptureService(JdbcClient jdbc, TenantGuard guard, AuditService audit, PipelineService pipeline,
                          ObjectStore store, UploadProperties uploads, ObjectMapper mapper, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.pipeline = pipeline;
        this.store = store;
        this.uploads = uploads;
        this.mapper = mapper;
        this.tx = tx;
    }

    private CaptureView map(ResultSet rs, int i) throws SQLException {
        try {
            Timestamp ended = rs.getTimestamp("ended_at");
            java.math.BigDecimal duration = rs.getBigDecimal("duration_seconds");
            @SuppressWarnings("unchecked")
            Map<String, Object> device = mapper.readValue(rs.getString("device"), Map.class);
            return new CaptureView(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getString("operator_id"),
                CaptureStatus.valueOf(rs.getString("status")), device, rs.getTimestamp("started_at").toInstant(),
                ended == null ? null : ended.toInstant(), duration == null ? null : duration.doubleValue(),
                rs.getString("quality_state"), rs.getString("failure_code"), rs.getString("failure_message"),
                rs.getInt("media_count"), rs.getInt("accepted_count"), rs.getTimestamp("created_at").toInstant());
        } catch (JsonProcessingException e) {
            throw new SQLException("corrupt device metadata", e);
        }
    }

    public CaptureView create(Actor actor, UUID venueId, UUID floorId, Map<String, Object> device, Instant startedAt) {
        guard.requireVenue(actor, venueId);
        if (floorId != null && jdbc.sql("SELECT count(*) FROM floor WHERE id = :f AND venue_id = :v AND deleted_at IS NULL")
                .param("f", floorId).param("v", venueId).query(Integer.class).single() == 0) {
            throw new NotFoundException("floor not found");
        }
        Map<String, Object> dev = device == null ? Map.of() : device;
        String deviceJson = toJson(dev);
        if (deviceJson.length() > MAX_DEVICE_JSON_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DEVICE_METADATA_TOO_LARGE", "device metadata is limited to 16 KiB");
        }
        Instant started = startedAt == null ? Instant.now() : startedAt;
        if (started.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_START_TIME", "startedAt is in the future");
        }
        UUID id = tx.execute(s -> {
            UUID created = jdbc.sql("INSERT INTO capture_session (organization_id, venue_id, floor_id, operator_id, device, started_at) "
                    + "VALUES (:o, :v, :f, :op, CAST(:d AS jsonb), :s) RETURNING id")
                .param("o", actor.organizationId()).param("v", venueId).param("f", floorId).param("op", actor.subject())
                .param("d", deviceJson).param("s", Timestamp.from(started)).query(UUID.class).single();
            audit.success(actor, venueId, "capture.create", "capture_session", created, Map.of());
            return created;
        });
        return get(actor, venueId, id);
    }

    public CaptureView get(Actor actor, UUID venueId, UUID captureId) {
        guard.requireVenue(actor, venueId);
        return jdbc.sql(SELECT + " AND c.id = :c").param("v", venueId).param("o", actor.organizationId())
            .param("c", captureId).query(this::map).optional().orElseThrow(() -> new NotFoundException("capture not found"));
    }

    public List<CaptureView> list(Actor actor, UUID venueId) {
        guard.requireVenue(actor, venueId);
        return jdbc.sql(SELECT + " ORDER BY c.created_at DESC LIMIT 100").param("v", venueId)
            .param("o", actor.organizationId()).query(this::map).list();
    }

    /**
     * Ends the upload phase. UPLOADING -> UPLOADED -> VALIDATING (committed), then a real integrity
     * check of every accepted object in storage, then READY_FOR_PROCESSING or FAILED.
     */
    public CaptureView completeUpload(Actor actor, UUID venueId, UUID captureId, Instant endedAt, Double durationSeconds) {
        CaptureView c = get(actor, venueId, captureId);
        if (c.status() != CaptureStatus.UPLOADING) {
            throw invalid(c.status(), CaptureStatus.UPLOADED);
        }
        List<Map<String, Object>> counts = jdbc.sql("SELECT kind, status, count(*) AS n FROM capture_media "
                + "WHERE capture_session_id = :c GROUP BY kind, status").param("c", captureId).query().listOfRows();
        int unsettled = 0;
        int acceptedVideo = 0;
        int acceptedImages = 0;
        for (Map<String, Object> row : counts) {
            int n = ((Number) row.get("n")).intValue();
            String status = (String) row.get("status");
            if (status.equals("PENDING") || status.equals("VALIDATING") || status.equals("QUARANTINED")) {
                unsettled += n;
            } else if (status.equals("ACCEPTED")) {
                if (row.get("kind").equals("VIDEO")) {
                    acceptedVideo += n;
                } else if (row.get("kind").equals("IMAGE")) {
                    acceptedImages += n;
                }
            }
        }
        if (unsettled > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "UPLOADS_NOT_SETTLED",
                unsettled + " file(s) are still uploading, being validated, or quarantined. Finish, retry or remove them first.");
        }
        if (acceptedVideo == 0 && acceptedImages < uploads.minImagesWithoutVideo()) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_MEDIA",
                "A capture needs at least one accepted video or " + uploads.minImagesWithoutVideo()
                    + " accepted images (has " + acceptedVideo + " videos, " + acceptedImages + " images).");
        }
        Instant ended = endedAt == null ? Instant.now() : endedAt;
        if (ended.isBefore(c.startedAt()) || ended.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_END_TIME", "endedAt must be after startedAt and not in the future");
        }
        double duration = durationSeconds != null ? durationSeconds
            : Duration.between(c.startedAt(), ended).toMillis() / 1000.0;
        if (duration < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DURATION", "durationSeconds must not be negative");
        }

        tx.executeWithoutResult(s -> {
            int rows = jdbc.sql("UPDATE capture_session SET status = 'UPLOADED', ended_at = :e, duration_seconds = :d "
                    + "WHERE id = :c AND status = 'UPLOADING'")
                .param("e", Timestamp.from(ended)).param("d", duration).param("c", captureId).update();
            if (rows == 0) {
                throw invalid(CaptureStatus.UPLOADING, CaptureStatus.UPLOADED);
            }
            transition(captureId, CaptureStatus.UPLOADED, CaptureStatus.VALIDATING, null, null);
            audit.success(actor, venueId, "capture.upload_complete", "capture_session", captureId, Map.of());
        });

        String problem = verifyStoredObjects(captureId);
        if (problem != null) {
            tx.executeWithoutResult(s -> {
                transition(captureId, CaptureStatus.VALIDATING, CaptureStatus.FAILED, "STORAGE_INTEGRITY", problem);
                audit.success(actor, venueId, "capture.fail", "capture_session", captureId, Map.of("code", "STORAGE_INTEGRITY"));
            });
            throw new ApiException(HttpStatus.CONFLICT, "STORAGE_INTEGRITY", problem);
        }
        tx.executeWithoutResult(s -> transition(captureId, CaptureStatus.VALIDATING, CaptureStatus.READY_FOR_PROCESSING, null, null));
        return get(actor, venueId, captureId);
    }

    /** Every accepted object must still exist in storage with the size recorded at validation. */
    private String verifyStoredObjects(UUID captureId) {
        List<Map<String, Object>> accepted = jdbc.sql("SELECT id, object_key, declared_size_bytes FROM capture_media "
                + "WHERE capture_session_id = :c AND status = 'ACCEPTED'").param("c", captureId).query().listOfRows();
        for (Map<String, Object> m : accepted) {
            try {
                long size = store.size((String) m.get("object_key"));
                if (size != ((Number) m.get("declared_size_bytes")).longValue()) {
                    return "stored object for media " + m.get("id") + " has an unexpected size";
                }
            } catch (RuntimeException e) {
                return "stored object for media " + m.get("id") + " is missing or unreadable";
            }
        }
        return null;
    }

    public ProcessingStatus startProcessing(Actor actor, UUID venueId, UUID captureId, Boolean privacyEnabled, Integer timeBudgetSeconds) {
        CaptureView c = get(actor, venueId, captureId);
        if (c.status() != CaptureStatus.READY_FOR_PROCESSING) {
            throw invalid(c.status(), CaptureStatus.PROCESSING);
        }
        tx.executeWithoutResult(s -> {
            transition(captureId, CaptureStatus.READY_FOR_PROCESSING, CaptureStatus.PROCESSING, null, null);
            UUID scan = jdbc.sql("INSERT INTO scan (organization_id, venue_id, capture_session_id) VALUES (:o, :v, :c) RETURNING id")
                .param("o", actor.organizationId()).param("v", venueId).param("c", captureId).query(UUID.class).single();
            UUID run = pipeline.start(actor, venueId, captureId, scan, privacyEnabled, timeBudgetSeconds);
            audit.success(actor, venueId, "capture.start_processing", "capture_session", captureId,
                Map.of("scanId", scan.toString(), "runId", run.toString()));
        });
        return processingStatus(actor, venueId, captureId);
    }

    public ProcessingStatus retryProcessing(Actor actor, UUID venueId, UUID captureId) {
        get(actor, venueId, captureId);
        UUID scan = scanOf(captureId, venueId);
        pipeline.retry(actor, venueId, scan);
        return processingStatus(actor, venueId, captureId);
    }

    public ProcessingStatus cancelProcessing(Actor actor, UUID venueId, UUID captureId) {
        get(actor, venueId, captureId);
        UUID scan = scanOf(captureId, venueId);
        pipeline.cancel(actor, venueId, scan);
        return processingStatus(actor, venueId, captureId);
    }

    private UUID scanOf(UUID captureId, UUID venueId) {
        return jdbc.sql("SELECT id FROM scan WHERE capture_session_id = :c AND venue_id = :v").param("c", captureId).param("v", venueId)
            .query(UUID.class).optional().orElseThrow(() -> new NotFoundException("processing has not been started for this capture"));
    }

    public ProcessingStatus processingStatus(Actor actor, UUID venueId, UUID captureId) {
        CaptureView c = get(actor, venueId, captureId);
        UUID scan = jdbc.sql("SELECT id FROM scan WHERE capture_session_id = :c AND venue_id = :v")
            .param("c", captureId).param("v", venueId).query(UUID.class).optional().orElse(null);
        List<JobView> list = scan == null ? List.of() : jdbc.sql("SELECT id, stage, status, retry_count, error_code, error_message, "
                + "queued_at, started_at, finished_at FROM processing_job WHERE scan_id = :s ORDER BY created_at")
            .param("s", scan).query((rs, i) -> new JobView(rs.getObject("id", UUID.class), rs.getString("stage"),
                rs.getString("status"), rs.getInt("retry_count"), rs.getString("error_code"),
                rs.getString("error_message"), rs.getTimestamp("queued_at").toInstant(),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant())).list();
        return new ProcessingStatus(captureId, c.status(), scan, scan == null ? null : pipeline.runView(scan).orElse(null), list);
    }

    /** Compare-and-set transition; refuses anything the state machine does not allow. */
    public void transition(UUID captureId, CaptureStatus from, CaptureStatus to, String failureCode, String failureMessage) {
        if (!from.canTransitionTo(to)) {
            throw invalid(from, to);
        }
        int rows = jdbc.sql("UPDATE capture_session SET status = :to, failure_code = :fc, failure_message = :fm "
                + "WHERE id = :c AND status = :from")
            .param("to", to.name()).param("fc", failureCode).param("fm", failureMessage)
            .param("c", captureId).param("from", from.name()).update();
        if (rows == 0) {
            throw invalid(from, to);
        }
    }

    static ApiException invalid(CaptureStatus from, CaptureStatus to) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_CAPTURE_TRANSITION",
            "capture is " + from + "; it cannot move to " + to);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JSON", "device metadata is not valid JSON");
        }
    }
}
