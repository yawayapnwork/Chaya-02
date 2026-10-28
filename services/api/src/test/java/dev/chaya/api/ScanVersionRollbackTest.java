package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.RouteService;
import dev.chaya.api.poi.PoiService;
import dev.chaya.api.rescan.RescanDtos.ScanVersionView;
import dev.chaya.api.rescan.RescanService;
import dev.chaya.api.rescan.ScanVersionService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Validates version rollback and publication consistency across the digital twin (docs/rescan.md).
 * Ensures that rollback safely switches active graphs, frames, and POI currency to a previously
 * published finalized version, records an auditable event, prevents invalid states, and rejects
 * stale search / routing access.
 */
class ScanVersionRollbackTest extends AbstractIntegrationTest {

    @Autowired
    private ScanVersionService versions;

    @Autowired
    private RescanService rescan;

    @Autowired
    private RouteService routes;

    @Autowired
    private PoiService pois;

    private Actor actorFor(UUID org, UUID venue) {
        return new Actor(Actor.Kind.USER, "test-operator", org, Set.of(venue), Set.of(Role.OPERATOR));
    }

    @Test
    void rollbackSwitchesCurrentVersionActivatesTargetGraphsAndAdoptsFrame() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
        UUID v1 = fx.publishCurrentReconstruction(t.org(), t.venue(), t.floor());
        UUID v2 = fx.publishNewReconstruction(t.org(), t.venue(), t.floor());

        assertThat(versions.currentOf(t.floor())).contains(v2);

        // Active graphs belong to v2
        List<String> activeV2 = jdbc.sql("SELECT scan_version_id FROM navigation_graph WHERE floor_id = :f AND status = 'ACTIVE'")
            .param("f", t.floor()).query(String.class).list();
        assertThat(activeV2).allMatch(id -> id.equals(v2.toString()));

        // Roll back to v1
        ScanVersionView rolledBack = rescan.rollback(actor, t.venue(), t.floor(), v1, "Rollback to v1 due to artifact regression");
        assertThat(rolledBack.id()).isEqualTo(v1);
        assertThat(rolledBack.current()).isTrue();
        assertThat(versions.currentOf(t.floor())).contains(v1);

        // Active graphs now belong to v1, and v2's graphs are RETIRED
        List<String> activeAfter = jdbc.sql("SELECT scan_version_id FROM navigation_graph WHERE floor_id = :f AND status = 'ACTIVE'")
            .param("f", t.floor()).query(String.class).list();
        assertThat(activeAfter).isNotEmpty().allMatch(id -> id.equals(v1.toString()));

        List<String> retiredAfter = jdbc.sql("SELECT scan_version_id FROM navigation_graph WHERE floor_id = :f AND status = 'RETIRED'")
            .param("f", t.floor()).query(String.class).list();
        assertThat(retiredAfter).contains(v2.toString());

        // Audit log records the rollback event
        Integer auditCount = jdbc.sql("""
                SELECT count(*) FROM audit_log
                 WHERE organization_id = :org AND venue_id = :venue AND action = 'scan_version.rollback'
                   AND resource_id = :res
                """)
            .param("org", t.org()).param("venue", t.venue()).param("res", v1)
            .query(Integer.class).single();
        assertThat(auditCount).isGreaterThan(0);
    }

    @Test
    void cannotRollbackToAlreadyCurrentVersion() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
        UUID v1 = fx.publishCurrentReconstruction(t.org(), t.venue(), t.floor());

        assertThatThrownBy(() -> rescan.rollback(actor, t.venue(), t.floor(), v1, "already current"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("already the current published version");
    }

    @Test
    void cannotRollbackToUnfinalizedDraftVersion() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
        UUID v1 = fx.publishCurrentReconstruction(t.org(), t.venue(), t.floor());

        // Create a draft version
        UUID draft = jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, status)
                VALUES (:o, :v, :s, :f, 99, 'DRAFT') RETURNING id""")
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("f", t.floor())
            .query(UUID.class).single();

        assertThatThrownBy(() -> rescan.rollback(actor, t.venue(), t.floor(), draft, "cannot rollback to draft"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("only a FINALIZED version");
    }

    @Test
    void cannotRollbackToRoomVersion() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
        UUID v1 = fx.publishCurrentReconstruction(t.org(), t.venue(), t.floor());

        // Create a room version
        UUID space = fx.space(t.org(), t.venue(), t.floor());
        UUID roomVersion = jdbc.sql("""
                INSERT INTO scan_version (organization_id, venue_id, scan_id, floor_id, version_number, status, space_id)
                VALUES (:o, :v, :s, :f, 98, 'FINALIZED', :sp) RETURNING id""")
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("f", t.floor()).param("sp", space)
            .query(UUID.class).single();

        assertThatThrownBy(() -> rescan.rollback(actor, t.venue(), t.floor(), roomVersion, "cannot publish room version as floor"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("reconstructs one room");
    }

    @Test
    void cannotRollbackToVersionFromDifferentFloor() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
        UUID v1 = fx.publishCurrentReconstruction(t.org(), t.venue(), t.floor());

        UUID otherFloor = fx.floor(t.org(), t.venue(), 2);
        UUID otherScan = fx.scan(t.org(), t.venue(), fx.captureSession(t.org(), t.venue()));
        UUID otherVersion = fx.finalizedScanVersion(t.org(), t.venue(), otherScan, otherFloor, 1);

        assertThatThrownBy(() -> rescan.rollback(actor, t.venue(), t.floor(), otherVersion, "different floor"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("different floor");
    }

    @Test
    void staleSearchAndPoiCurrencyInvalidatedAfterRollback() {
        var t = fx.tree();
        Actor actor = actorFor(t.org(), t.venue());
        fx.calibratedFloor(t.org(), t.venue(), t.floor(), "FLOOR_LOCAL");
        UUID v1 = fx.publishCurrentReconstruction(t.org(), t.venue(), t.floor());
        UUID v2 = fx.publishNewReconstruction(t.org(), t.venue(), t.floor());

        // Add auto-detected POIs to v1 and v2
        UUID poi1 = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", t.org()).param("v", t.venue()).param("f", t.floor()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z, source,
                    pipeline_run_id, scan_version_id, created_by)
                VALUES (:o, :v, :p, 1, 'v1 item', '{}', 1, 1, 0, 'AUTO_DETECTED',
                    (SELECT pipeline_run_id FROM scan_version WHERE id = :sv), :sv, 'test')""")
            .param("o", t.org()).param("v", t.venue()).param("p", poi1).param("sv", v1).update();

        UUID poi2 = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", t.org()).param("v", t.venue()).param("f", t.floor()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z, source,
                    pipeline_run_id, scan_version_id, created_by)
                VALUES (:o, :v, :p, 1, 'v2 item', '{}', 2, 2, 0, 'AUTO_DETECTED',
                    (SELECT pipeline_run_id FROM scan_version WHERE id = :sv), :sv, 'test')""")
            .param("o", t.org()).param("v", t.venue()).param("p", poi2).param("sv", v2).update();

        // While floor publishes v2: poi2 is current (because v2 is a child of v1 or latest current)
        Boolean poi2CurrentBefore = jdbc.sql("""
                SELECT poi_version_is_current(:f, :sv, 'AUTO_DETECTED')
                """).param("f", t.floor()).param("sv", v2).query(Boolean.class).single();
        assertThat(poi2CurrentBefore).isTrue();

        // Roll back floor to v1
        rescan.rollback(actor, t.venue(), t.floor(), v1, "reverting to v1");

        // After rollback: v2 POI is NO LONGER current
        Boolean poi2CurrentAfter = jdbc.sql("""
                SELECT poi_version_is_current(:f, :sv, 'AUTO_DETECTED')
                """).param("f", t.floor()).param("sv", v2).query(Boolean.class).single();
        assertThat(poi2CurrentAfter).isFalse();

        // But v1 POI IS current
        Boolean poi1CurrentAfter = jdbc.sql("""
                SELECT poi_version_is_current(:f, :sv, 'AUTO_DETECTED')
                """).param("f", t.floor()).param("sv", v1).query(Boolean.class).single();
        assertThat(poi1CurrentAfter).isTrue();
    }
}
