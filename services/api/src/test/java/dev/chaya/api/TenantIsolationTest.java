package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class TenantIsolationTest extends AbstractIntegrationTest {

    @Test
    void venueSlugsAreUniquePerOrganizationOnly() {
        UUID a = fx.organization();
        UUID b = fx.organization();
        jdbc.sql("INSERT INTO venue (organization_id, slug, name) VALUES (:o, 'main', 'Main')").param("o", a).update();
        jdbc.sql("INSERT INTO venue (organization_id, slug, name) VALUES (:o, 'main', 'Main')").param("o", b).update();

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO venue (organization_id, slug, name) VALUES (:o, 'main', 'Dup')")
            .param("o", a).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void floorCannotBeAttachedToAVenueOfAnotherOrganization() {
        UUID orgA = fx.organization();
        UUID orgB = fx.organization();
        UUID venueOfB = fx.venue(orgB);

        assertThatThrownBy(() -> fx.floor(orgA, venueOfB, 0))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void scanCannotUseACaptureSessionFromAnotherVenue() {
        UUID org = fx.organization();
        UUID venue1 = fx.venue(org);
        UUID venue2 = fx.venue(org);
        UUID sessionOfVenue2 = fx.captureSession(org, venue2);

        assertThatThrownBy(() -> fx.scan(org, venue1, sessionOfVenue2))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void scanVersionCannotUseAFloorFromAnotherVenue() {
        var t = fx.tree();
        UUID otherVenue = fx.venue(t.org());
        UUID floorOfOtherVenue = fx.floor(t.org(), otherVenue, 0);

        assertThatThrownBy(() -> fx.draftScanVersion(t.org(), t.venue(), t.scan(), floorOfOtherVenue, 2))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void organizationWithVenuesCannotBeHardDeleted() {
        UUID org = fx.organization();
        fx.venue(org);

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM organization WHERE id = :id").param("id", org).update())
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM organization WHERE id = :id").param("id", org)
            .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void updatedAtAdvancesOnUpdate() {
        UUID org = fx.organization();
        var before = jdbc.sql("SELECT updated_at FROM organization WHERE id = :id").param("id", org)
            .query(java.sql.Timestamp.class).single();
        jdbc.sql("UPDATE organization SET name = 'Renamed' WHERE id = :id").param("id", org).update();
        var after = jdbc.sql("SELECT updated_at FROM organization WHERE id = :id").param("id", org)
            .query(java.sql.Timestamp.class).single();
        assertThat(after).isAfter(before);
    }
}
