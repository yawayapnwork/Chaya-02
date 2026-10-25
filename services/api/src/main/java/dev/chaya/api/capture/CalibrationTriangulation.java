package dev.chaya.api.capture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chaya.api.capture.CaptureCalibrationDtos.MeasurementView;
import dev.chaya.api.capture.CaptureCalibrationDtos.ObservationView;
import dev.chaya.api.frame.FrameProperties;
import dev.chaya.api.frame.Quaternion;
import dev.chaya.api.frame.Triangulator;
import dev.chaya.api.storage.ObjectStore;
import dev.chaya.api.storage.StorageException;
import dev.chaya.api.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Locates a capture's measured points in its reconstruction from the pixels the operator marked, through the run's own
 * SfM camera poses (docs/coordinate-frames.md, "Triangulated measurement points").
 *
 * <p>An observation (a pixel in an accepted source image, or in a source video at a time) is mapped to the frame SfM saw:
 * <ol>
 *   <li>the frame FFMPEG_PREPROCESS made from that source (FRAME_MANIFEST.frame_sources): the image's one frame, or the
 *       video frame nearest the marked time, which must be within chaya.frames.max-video-frame-offset-seconds;</li>
 *   <li>registered by POSE_ESTIMATION (POSES.poses names it), else the observation is unusable and said so;</li>
 *   <li>the pixel scaled from the source's decoded size to the frame's (corner convention: a plain multiply), refused when
 *       the scaling is not uniform (a rotated or re-cropped video).</li>
 * </ol>
 * Then each point is triangulated ({@link Triangulator}) and checked against the limits. Nothing is guessed: every
 * observation that could not be used, and every point that could not be located, is reported with the reason.
 */
@Component
public class CalibrationTriangulation {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** points: measurementId -> point (A, B, P) -> reconstruction xyz; failures: one sentence each; diagnostics: per point. */
    public record Outcome(Map<UUID, Map<String, List<Double>>> points, List<String> failures, Map<String, Object> diagnostics) {}

    private record FrameRef(String name, String kind, Double time, int width, int height) {}

    private final JdbcClient jdbc;
    private final ObjectStore derived;
    private final ObjectMapper mapper;
    private final FrameProperties props;

    public CalibrationTriangulation(JdbcClient jdbc, @Qualifier("derived") ObjectStore derived, ObjectMapper mapper, FrameProperties props) {
        this.jdbc = jdbc;
        this.derived = derived;
        this.mapper = mapper;
        this.props = props;
    }

    public Outcome locate(UUID captureId, UUID runId, List<MeasurementView> measurements) {
        Map<String, Object> poses = artifact(runId, "POSE_ESTIMATION", "POSES");
        Map<String, Object> manifest = artifact(runId, "FFMPEG_PREPROCESS", "FRAME_MANIFEST");
        Object sourcesRaw = manifest.get("frame_sources");
        if (!(sourcesRaw instanceof List<?> frameSources)) {
            throw new ApiException(HttpStatus.CONFLICT, "FRAME_MANIFEST_OUTDATED", "this run's FRAME_MANIFEST does not say which source "
                + "and time each frame came from (it predates frame_sources): reprocess the capture to calibrate it from its marks");
        }
        Map<UUID, List<FrameRef>> framesBySource = new HashMap<>();
        Map<String, Triangulator.View> views;
        try {
            views = views(poses);
        } catch (RuntimeException e) {  // an unsupported camera model, a missing field, a non-numeric value
            throw new ApiException(HttpStatus.CONFLICT, "POSES_INVALID", "the run's POSES cannot be used to locate points: " + e.getMessage());
        }
        try {
            for (Object o : frameSources) {
                Map<?, ?> f = (Map<?, ?>) o;
                List<?> size = (List<?>) f.get("frame_size");
                framesBySource.computeIfAbsent(UUID.fromString((String) f.get("artifact_id")), k -> new ArrayList<>())
                    .add(new FrameRef((String) f.get("frame"), (String) f.get("kind"), f.get("time_seconds") == null ? null
                        : ((Number) f.get("time_seconds")).doubleValue(), ((Number) size.get(0)).intValue(), ((Number) size.get(1)).intValue()));
            }
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.CONFLICT, "FRAME_MANIFEST_INVALID", "the run's FRAME_MANIFEST is malformed: " + e.getMessage());
        }
        Map<UUID, int[]> mediaSize = new HashMap<>();
        jdbc.sql("SELECT id, pixel_width, pixel_height FROM capture_media WHERE capture_session_id = :c").param("c", captureId)
            .query((rs, i) -> Map.entry(rs.getObject(1, UUID.class), new int[]{rs.getInt(2), rs.getInt(3)}))
            .list().forEach(e -> mediaSize.put(e.getKey(), e.getValue()));

        Map<UUID, Map<String, List<Double>>> points = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        for (MeasurementView m : measurements) {
            Map<String, List<ObservationView>> byPoint = new LinkedHashMap<>();
            for (ObservationView o : m.observations()) {
                byPoint.computeIfAbsent(o.point(), k -> new ArrayList<>()).add(o);
            }
            for (var e : byPoint.entrySet()) {
                String key = m.label() + " (" + e.getKey() + ")";
                List<Triangulator.Observation> usable = new ArrayList<>();
                List<String> unusable = new ArrayList<>();
                for (ObservationView o : e.getValue()) {
                    String why = map(o, framesBySource, views, mediaSize, usable);
                    if (why != null) {
                        unusable.add(why);
                    }
                }
                Triangulator.Result r = Triangulator.triangulate(usable, props.triangulationLimits());
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("measurementId", m.id().toString());
                d.put("role", m.role());
                d.put("views", r.viewNames());
                d.put("unusableObservations", unusable);
                d.put("maxRayAngleDeg", r.maxAngleDeg());
                d.put("rmsReprojectionPx", r.rmsReprojectionPx());
                d.put("maxReprojectionPx", r.maxReprojectionPx());
                if (r.ok()) {
                    d.put("reconstruction", List.of(r.point()[0], r.point()[1], r.point()[2]));
                    points.computeIfAbsent(m.id(), k -> new HashMap<>()).put(e.getKey(), List.of(r.point()[0], r.point()[1], r.point()[2]));
                } else {
                    d.put("failure", r.failure());
                    failures.add(key + ": " + r.failure() + " (" + r.message() + (unusable.isEmpty() ? "" : "; unusable: " + unusable) + ")");
                }
                diagnostics.put(key, d);
            }
        }
        return new Outcome(points, failures, diagnostics);
    }

    /** Adds the observation's view to {@code usable}, or returns why it cannot be used. */
    private String map(ObservationView o, Map<UUID, List<FrameRef>> framesBySource, Map<String, Triangulator.View> views,
                       Map<UUID, int[]> mediaSize, List<Triangulator.Observation> usable) {
        List<FrameRef> frames = framesBySource.getOrDefault(o.mediaId(), List.of());
        if (frames.isEmpty()) {
            return "media " + o.mediaId() + " produced no frame in this run";
        }
        FrameRef frame;
        if (o.frameTimeSeconds() == null) {
            frame = frames.get(0);
        } else {
            frame = frames.stream().filter(f -> f.time() != null)
                .min((a, b) -> Double.compare(Math.abs(a.time() - o.frameTimeSeconds()), Math.abs(b.time() - o.frameTimeSeconds())))
                .orElse(null);
            if (frame == null || Math.abs(frame.time() - o.frameTimeSeconds()) > props.maxVideoFrameOffsetSeconds()) {
                return "the mark at " + o.frameTimeSeconds() + " s of video " + o.mediaId() + " is not on an extracted frame (nearest: "
                    + (frame == null ? "none" : frame.time() + " s") + "; within " + props.maxVideoFrameOffsetSeconds() + " s is required): "
                    + "re-mark it at an extracted frame's time";
            }
        }
        Triangulator.View view = views.get(frame.name());
        if (view == null) {
            return "frame " + frame.name() + " (marked in media " + o.mediaId() + ") was not registered by POSE_ESTIMATION";
        }
        int[] size = mediaSize.get(o.mediaId());
        if (size == null || size[0] <= 0 || size[1] <= 0) {
            return "media " + o.mediaId() + " has no decoded size, so its pixels cannot be scaled to the frame";
        }
        double sx = (double) frame.width() / size[0];
        double sy = (double) frame.height() / size[1];
        if (Math.abs(sx - sy) > 1e-3 * Math.max(sx, sy)) {
            return "media " + o.mediaId() + " (" + size[0] + "x" + size[1] + ") does not scale uniformly to its frame (" + frame.width()
                + "x" + frame.height() + "): rotated or cropped; its marks cannot be placed";
        }
        if (view.camera().width() != frame.width() || view.camera().height() != frame.height()) {
            return "frame " + frame.name() + " is " + frame.width() + "x" + frame.height() + " but its camera is " + view.camera().width()
                + "x" + view.camera().height();
        }
        usable.add(new Triangulator.Observation(view, o.u() * sx, o.v() * sy));
        return null;
    }

    private static Map<String, Triangulator.View> views(Map<String, Object> poses) {
        Map<String, Triangulator.Camera> cameras = new HashMap<>();
        for (var e : ((Map<?, ?>) poses.get("cameras")).entrySet()) {
            Map<?, ?> c = (Map<?, ?>) e.getValue();
            List<?> params = (List<?>) c.get("params");
            double[] p = params.stream().mapToDouble(v -> ((Number) v).doubleValue()).toArray();
            cameras.put(String.valueOf(e.getKey()), new Triangulator.Camera((String) c.get("model"), ((Number) c.get("width")).intValue(),
                ((Number) c.get("height")).intValue(), p));
        }
        Map<String, Triangulator.View> out = new HashMap<>();
        for (Object o : (List<?>) poses.get("poses")) {
            Map<?, ?> p = (Map<?, ?>) o;
            List<?> q = (List<?>) p.get("rotation_wxyz");
            List<?> t = (List<?>) p.get("translation");
            Triangulator.Camera camera = cameras.get(String.valueOf(p.get("camera_id")));
            if (camera == null) {
                continue;
            }
            out.put((String) p.get("name"), new Triangulator.View((String) p.get("name"), camera,
                new Quaternion(num(q, 0), num(q, 1), num(q, 2), num(q, 3)), new double[]{num(t, 0), num(t, 1), num(t, 2)}));
        }
        return out;
    }

    private static double num(List<?> v, int i) {
        return ((Number) v.get(i)).doubleValue();
    }

    private Map<String, Object> artifact(UUID runId, String stage, String kind) {
        record Stored(String key, String sha256) {}
        Stored s = jdbc.sql("""
                SELECT a.object_key, a.checksum_sha256 FROM processing_artifact a
                  JOIN pipeline_stage_run sr ON sr.id = a.stage_run_id
                  JOIN pipeline_run r ON r.id = sr.run_id
                 WHERE sr.run_id = :r AND sr.stage = :s AND sr.status = 'SUCCEEDED' AND a.kind = :k
                   AND a.organization_id = r.organization_id AND a.venue_id = r.venue_id
                 ORDER BY sr.finished_at DESC LIMIT 1
                """).param("r", runId).param("s", stage).param("k", kind)
            .query((rs, i) -> new Stored(rs.getString(1), rs.getString(2))).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "RECONSTRUCTION_FRAME_UNAVAILABLE",
                "this run has no " + kind + " from a successful " + stage + ", so the marked points cannot be located"));
        try (InputStream in = derived.openVerified(s.key(), s.sha256())) {
            return mapper.readValue(in.readAllBytes(), MAP);
        } catch (IOException | StorageException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, kind + "_UNREADABLE", "the run's " + kind + " could not be read: "
                + e.getMessage());
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.CONFLICT, kind + "_INVALID", "the run's " + kind + " is malformed: " + e.getMessage());
        }
    }
}
