package dev.chaya.api.audit;

import dev.chaya.api.audit.AuditLogWriter.AuditEvent;
import dev.chaya.api.audit.AuditLogWriter.Outcome;
import dev.chaya.api.security.Actor;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit entry points. {@link #success} joins the caller's transaction, so an operation and its
 * audit row commit or roll back together. {@link #denied} uses its own transaction so that the
 * record survives the exception that aborts the request.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogWriter writer;

    public AuditService(AuditLogWriter writer) {
        this.writer = writer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void success(Actor actor, UUID venueId, String action, String resourceType, UUID resourceId,
                        Map<String, Object> metadata) {
        writer.record(new AuditEvent(actor.organizationId(), venueId, actor.subject(), actor.auditType(),
            action, resourceType, resourceId, Outcome.SUCCESS, metadata));
    }

    /** For service actors, which carry no organization: the organization comes from the resource acted on. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void successInOrganization(Actor actor, UUID organizationId, UUID venueId, String action,
                                      String resourceType, UUID resourceId, Map<String, Object> metadata) {
        writer.record(new AuditEvent(organizationId, venueId, actor.subject(), actor.auditType(),
            action, resourceType, resourceId, Outcome.SUCCESS, metadata));
    }

    /** Records a refused access attempt. Never throws: a failed audit must not mask the denial. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(Actor actor, String action, String resourceType, UUID attemptedResourceId) {
        if (actor.organizationId() == null) {
            log.warn("denied {} by {} {} (no organization; not audited)", action, actor.kind(), actor.subject());
            return;
        }
        try {
            // The attempted id goes into metadata, not venue_id: it may belong to another tenant.
            writer.record(new AuditEvent(actor.organizationId(), null, actor.subject(), actor.auditType(),
                action, resourceType, null, Outcome.DENIED,
                Map.of("attemptedResourceId", String.valueOf(attemptedResourceId))));
        } catch (RuntimeException e) {
            log.error("could not write denial audit for {} {}", actor.kind(), actor.subject(), e);
        }
    }
}
