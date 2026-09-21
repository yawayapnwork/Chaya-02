package dev.chaya.api.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Appends to the audit log. The table is append-only at the database level. */
@Repository
public class AuditLogWriter {

    public enum ActorType { USER, SERVICE, PUBLIC_VIEWER }

    public enum Outcome { SUCCESS, DENIED, FAILURE }

    public record AuditEvent(
        UUID organizationId,
        UUID venueId,
        String actorId,
        ActorType actorType,
        String action,
        String resourceType,
        UUID resourceId,
        Outcome outcome,
        Map<String, Object> metadata) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AuditLogWriter(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public UUID record(AuditEvent e) {
        return jdbc.sql("""
                INSERT INTO audit_log (organization_id, venue_id, actor_id, actor_type, action,
                                       resource_type, resource_id, outcome, metadata)
                VALUES (:org, :venue, :actor, :actorType, :action, :resourceType, :resourceId, :outcome,
                        CAST(:metadata AS jsonb))
                RETURNING id""")
            .param("org", e.organizationId())
            .param("venue", e.venueId())
            .param("actor", e.actorId())
            .param("actorType", e.actorType().name())
            .param("action", e.action())
            .param("resourceType", e.resourceType())
            .param("resourceId", e.resourceId())
            .param("outcome", e.outcome().name())
            .param("metadata", toJson(e.metadata()))
            .query(UUID.class)
            .single();
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return mapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("audit metadata is not serializable", ex);
        }
    }
}
