package dev.chaya.api.hud;

import dev.chaya.api.hud.CaptureHudDtos.HudStatus;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes {@link HudStatus} over Server-Sent Events to whoever is watching a capture. REST (CaptureHudController)
 * remains the durable, poll-friendly source of truth; this only saves a subscriber from polling it. A capture with
 * no subscribers costs nothing: the tick skips it.
 */
@Component
public class CaptureHudBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(CaptureHudBroadcaster.class);

    private record Subscriber(SseEmitter emitter, UUID venueId, UUID organizationId) {}

    private final Map<UUID, List<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSentVersion = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSentAt = new ConcurrentHashMap<>();

    private final CaptureHudService hud;
    private final HudProperties props;

    public CaptureHudBroadcaster(CaptureHudService hud, HudProperties props) {
        this.hud = hud;
        this.props = props;
    }

    public SseEmitter subscribe(UUID venueId, UUID organizationId, UUID captureId) {
        SseEmitter emitter = new SseEmitter(props.emitterTimeoutMs());
        Subscriber sub = new Subscriber(emitter, venueId, organizationId);
        subscribers.computeIfAbsent(captureId, k -> new CopyOnWriteArrayList<>()).add(sub);
        Runnable cleanup = () -> remove(captureId, sub);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("status").data(hud.statusForBroadcast(venueId, organizationId, captureId)));
        } catch (IOException e) {
            cleanup.run();
        }
        return emitter;
    }

    private void remove(UUID captureId, Subscriber sub) {
        List<Subscriber> list = subscribers.get(captureId);
        if (list != null) {
            list.remove(sub);
            if (list.isEmpty()) {
                subscribers.remove(captureId, list);
                lastSentVersion.remove(captureId);
                lastSentAt.remove(captureId);
            }
        }
    }

    @Scheduled(fixedDelayString = "${chaya.hud.broadcast-interval-ms:750}")
    void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, List<Subscriber>> e : subscribers.entrySet()) {
            UUID captureId = e.getKey();
            List<Subscriber> subs = e.getValue();
            if (subs.isEmpty()) {
                continue;
            }
            long version = hud.version(captureId);
            boolean dirty = lastSentVersion.getOrDefault(captureId, -1L) != version;
            boolean heartbeatDue = now - lastSentAt.getOrDefault(captureId, 0L) >= props.heartbeatIntervalMs();
            if (!dirty && !heartbeatDue) {
                continue;
            }
            Subscriber any = subs.get(0);
            HudStatus status = dirty ? hud.statusForBroadcast(any.venueId(), any.organizationId(), captureId) : null;
            for (Subscriber sub : subs) {
                try {
                    if (status != null) {
                        sub.emitter().send(SseEmitter.event().name("status").data(status));
                    } else {
                        sub.emitter().send(SseEmitter.event().comment("keep-alive"));
                    }
                } catch (IOException ex) {
                    log.debug("HUD subscriber for capture {} disconnected", captureId);
                    remove(captureId, sub);
                }
            }
            lastSentVersion.put(captureId, version);
            lastSentAt.put(captureId, now);
        }
    }
}
