package dev.chaya.api.capture;

import dev.chaya.api.capture.CaptureService.ProcessingStatus;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes a capture's {@link ProcessingStatus} over Server-Sent Events while someone watches it (docs/pipeline.md, "Live
 * progress"). The same pattern as the HUD stream (CaptureHudBroadcaster): GET .../processing stays the durable snapshot,
 * and this only saves a subscriber from polling it.
 *
 * <ul>
 *   <li>Every event is a full status, never a delta, so a missed event loses nothing: the next one (or the snapshot
 *       endpoint) carries the whole state. Its SSE id is the status revision (V40), which strictly increases with every
 *       committed change; a reconnecting client sends it back as Last-Event-ID and is sent the current status only if it
 *       differs.</li>
 *   <li>Each subscriber remembers what it was last sent (the revision and the number of expired worker leases, which
 *       change with time, not with a write), so it is never sent the same state twice. A tick with no change sends only a
 *       comment heartbeat now and then.</li>
 *   <li>A subscriber was authorized for one capture of one venue and organization when it subscribed, and only ever
 *       receives that capture's status. A stream never outlives the credential it was opened with: at its expiry the
 *       subscriber is sent a session-expired event and the stream ends; the client reconnects with a valid token. A
 *       capture that disappears (erased) ends the stream with a gone event.</li>
 * </ul>
 */
@Component
public class ProcessingBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ProcessingBroadcaster.class);

    /** One open stream. Sends to it are serialized on it: an SseEmitter is not safe for concurrent sends. */
    private static final class Subscriber {
        final SseEmitter emitter;
        final UUID venueId;
        final UUID organizationId;
        final Instant expiresAt;
        /** The state last sent ("revision:expired leases"), so nothing is sent twice. */
        String lastKey;
        long lastSentAt;

        Subscriber(SseEmitter emitter, UUID venueId, UUID organizationId, Instant expiresAt) {
            this.emitter = emitter;
            this.venueId = venueId;
            this.organizationId = organizationId;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<UUID, List<Subscriber>> subscribers = new ConcurrentHashMap<>();

    private final CaptureService captures;
    private final long emitterTimeoutMs;
    private final long heartbeatIntervalMs;

    public ProcessingBroadcaster(CaptureService captures,
                                 @Value("${chaya.processing.stream.emitter-timeout-ms:600000}") long emitterTimeoutMs,
                                 @Value("${chaya.processing.stream.heartbeat-interval-ms:15000}") long heartbeatIntervalMs) {
        this.captures = captures;
        this.emitterTimeoutMs = emitterTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    /** Number of open streams (tests, metrics). */
    public int subscriberCount() {
        return subscribers.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Opens a stream for a subscriber already authorized for the capture. lastEventId: the revision the client holds
     * (Last-Event-ID); the current status is sent first unless it is that revision.
     */
    public SseEmitter subscribe(UUID venueId, UUID organizationId, UUID captureId, Instant credentialExpiresAt, Long lastEventId) {
        long timeout = emitterTimeoutMs;
        if (credentialExpiresAt != null) {
            // A backstop a little after expiry; the tick ends the stream at expiry with an explicit event.
            timeout = Math.max(1_000, Math.min(timeout, credentialExpiresAt.toEpochMilli() - System.currentTimeMillis() + 5_000));
        }
        SseEmitter emitter = new SseEmitter(timeout);
        Subscriber sub = new Subscriber(emitter, venueId, organizationId, credentialExpiresAt);
        subscribers.computeIfAbsent(captureId, k -> new CopyOnWriteArrayList<>()).add(sub);
        Runnable cleanup = () -> remove(captureId, sub);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        synchronized (sub) {
            try {
                String key = captures.processingStreamKey(captureId);
                Optional<ProcessingStatus> status = key.equals("gone") ? Optional.empty()
                    : captures.processingStatusForSubscriber(venueId, organizationId, captureId);
                if (status.isEmpty()) {
                    end(captureId, sub, "gone");
                } else if (lastEventId == null || lastEventId != status.get().revision()) {
                    send(sub, status.get());
                } else {
                    sub.emitter.send(SseEmitter.event().comment("up to date at revision " + lastEventId));
                    sub.lastKey = key;
                    sub.lastSentAt = System.currentTimeMillis();
                }
            } catch (IOException e) {
                cleanup.run();
            }
        }
        return emitter;
    }

    /**
     * The stream key (CaptureService.processingStreamKey) of a status. A subscriber remembers the key of the status it
     * was sent, not the key read before it: a change committed between the two reads would otherwise leave it holding
     * the older key, and the next tick would send the same status again. Revision and expired leases come from the
     * status's one snapshot.
     */
    static String keyOf(ProcessingStatus status) {
        return status.revision() + ":" + status.activeJobs().stream().filter(CaptureService.ActiveJob::leaseExpired).count();
    }

    private static void send(Subscriber sub, ProcessingStatus status) throws IOException {
        sub.emitter.send(SseEmitter.event().name("status").id(Long.toString(status.revision())).reconnectTime(2_000).data(status));
        sub.lastKey = keyOf(status);
        sub.lastSentAt = System.currentTimeMillis();
    }

    private void end(UUID captureId, Subscriber sub, String event) {
        try {
            sub.emitter.send(SseEmitter.event().name(event).data(Map.of("captureId", captureId.toString())));
            sub.emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.debug("processing subscriber for capture {} already gone", captureId);
        }
        remove(captureId, sub);
    }

    private void remove(UUID captureId, Subscriber sub) {
        List<Subscriber> list = subscribers.get(captureId);
        if (list != null) {
            list.remove(sub);
            if (list.isEmpty()) {
                subscribers.remove(captureId, list);
            }
        }
    }

    @Scheduled(fixedDelayString = "${chaya.processing.stream.interval-ms:1000}")
    public void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, List<Subscriber>> e : subscribers.entrySet()) {
            UUID captureId = e.getKey();
            List<Subscriber> subs = e.getValue();
            if (subs.isEmpty()) {
                continue;
            }
            String key;
            try {
                key = captures.processingStreamKey(captureId);
            } catch (RuntimeException ex) {
                // A failed read (the database briefly unavailable) skips this tick; subscribers keep their stream.
                log.warn("processing stream tick failed for capture {}: {}", captureId, ex.toString());
                continue;
            }
            // Subscribers are grouped by what they were authorized for (all of one capture share it; nothing relies on it).
            Map<String, Optional<ProcessingStatus>> byScope = new HashMap<>();
            for (Subscriber sub : subs) {
                synchronized (sub) {
                    try {
                        if (sub.expiresAt != null && sub.expiresAt.toEpochMilli() <= now) {
                            end(captureId, sub, "session-expired");
                        } else if (key.equals("gone")) {
                            end(captureId, sub, "gone");
                        } else if (!key.equals(sub.lastKey)) {
                            Optional<ProcessingStatus> status = byScope.computeIfAbsent(sub.venueId + "/" + sub.organizationId,
                                k -> captures.processingStatusForSubscriber(sub.venueId, sub.organizationId, captureId));
                            if (status.isEmpty()) {
                                end(captureId, sub, "gone");
                            } else {
                                send(sub, status.get());
                            }
                        } else if (now - sub.lastSentAt >= heartbeatIntervalMs) {
                            sub.emitter.send(SseEmitter.event().comment("keep-alive"));
                            sub.lastSentAt = now;
                        }
                    } catch (IOException | IllegalStateException ex) {
                        log.debug("processing subscriber for capture {} disconnected", captureId);
                        remove(captureId, sub);
                    } catch (RuntimeException ex) {
                        log.warn("processing stream tick failed for capture {}: {}", captureId, ex.toString());
                    }
                }
            }
        }
    }
}
