package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.chaya.api.audit.AuditLogWriter;
import dev.chaya.api.audit.AuditLogWriter.ActorType;
import dev.chaya.api.audit.AuditLogWriter.AuditEvent;
import dev.chaya.api.audit.AuditLogWriter.Outcome;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class AuditLogTest extends AbstractIntegrationTest {

    @Autowired AuditLogWriter audit;

    @Test
    void recordsAnEventWithAllFields() {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        UUID resource = UUID.randomUUID();

        UUID id = audit.record(new AuditEvent(org, venue, "user-sub-1", ActorType.USER, "venue.update",
            "venue", resource, Outcome.SUCCESS, Map.of("field", "name")));

        var row = jdbc.sql("SELECT organization_id, venue_id, actor_id, action, resource_id, outcome, metadata->>'field' AS f, occurred_at FROM audit_log WHERE id = :id")
            .param("id", id).query().singleRow();
        assertThat(row.get("organization_id")).isEqualTo(org);
        assertThat(row.get("venue_id")).isEqualTo(venue);
        assertThat(row.get("actor_id")).isEqualTo("user-sub-1");
        assertThat(row.get("action")).isEqualTo("venue.update");
        assertThat(row.get("resource_id")).isEqualTo(resource);
        assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        assertThat(row.get("f")).isEqualTo("name");
        assertThat(row.get("occurred_at")).isNotNull();
    }

    @Test
    void organizationLevelEventsNeedNoVenue() {
        UUID org = fx.organization();
        UUID id = audit.record(new AuditEvent(org, null, "svc-worker", ActorType.SERVICE, "org.login",
            "organization", org, Outcome.DENIED, null));
        assertThat(id).isNotNull();
    }

    @Test
    void eventCannotPointAtAVenueOfAnotherOrganization() {
        UUID orgA = fx.organization();
        UUID venueOfB = fx.venue(fx.organization());
        assertThatThrownBy(() -> audit.record(new AuditEvent(orgA, venueOfB, "u", ActorType.USER, "x", "venue",
            null, Outcome.SUCCESS, Map.of()))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditLogIsAppendOnly() {
        UUID org = fx.organization();
        UUID id = audit.record(new AuditEvent(org, null, "u", ActorType.USER, "a", "organization", org,
            Outcome.SUCCESS, Map.of()));
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_log SET action = 'tampered' WHERE id = :id").param("id", id).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_log WHERE id = :id").param("id", id).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
