package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class PoiTest extends AbstractIntegrationTest {

    private UUID poi(UUID org, UUID venue, UUID floor, UUID space) {
        return jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id, space_id) VALUES (:o, :v, :f, :s) RETURNING id")
            .param("o", org).param("v", venue).param("f", floor).param("s", space).query(UUID.class).single();
    }

    private UUID poiVersion(UUID org, UUID venue, UUID poi, int number) {
        return jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, x, y, z, created_by)
                VALUES (:o, :v, :p, :n, 'Exit', 1.0, 2.0, 0.0, 'tester') RETURNING id""")
            .param("o", org).param("v", venue).param("p", poi).param("n", number).query(UUID.class).single();
    }

    @Test
    void poiBelongsToFloorAndSpaceOfItsVenue() {
        var t = fx.tree();
        UUID space = fx.space(t.org(), t.venue(), t.floor());
        UUID poi = poi(t.org(), t.venue(), t.floor(), space);
        UUID v1 = poiVersion(t.org(), t.venue(), poi, 1);
        UUID v2 = poiVersion(t.org(), t.venue(), poi, 2);
        assertThat(v1).isNotEqualTo(v2);
        assertThat(jdbc.sql("SELECT max(version_number) FROM poi_version WHERE poi_id = :p").param("p", poi)
            .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void poiCannotReferenceAFloorOrSpaceOfAnotherVenue() {
        var t = fx.tree();
        UUID otherVenue = fx.venue(t.org());
        UUID otherFloor = fx.floor(t.org(), otherVenue, 0);
        UUID otherSpace = fx.space(t.org(), otherVenue, otherFloor);

        assertThatThrownBy(() -> poi(t.org(), t.venue(), otherFloor, null))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> poi(t.org(), t.venue(), t.floor(), otherSpace))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void poiVersionIsImmutableButEmbeddingCanBeFilledExactlyOnce() {
        var t = fx.tree();
        UUID poi = poi(t.org(), t.venue(), t.floor(), null);
        UUID version = poiVersion(t.org(), t.venue(), poi, 1);

        assertThatThrownBy(() -> jdbc.sql("UPDATE poi_version SET label = 'Changed' WHERE id = :id").param("id", version).update())
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM poi_version WHERE id = :id").param("id", version).update())
            .isInstanceOf(DataIntegrityViolationException.class);

        // Embedding without a model name is rejected; with a model it is accepted once.
        String vec = "[" + "0.1,".repeat(511) + "0.1]"; // test fixture, not a real CLIP output
        assertThatThrownBy(() -> jdbc.sql("UPDATE poi_version SET embedding = CAST(:e AS vector) WHERE id = :id")
            .param("e", vec).param("id", version).update()).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("UPDATE poi_version SET embedding = CAST(:e AS vector), embedding_model = 'clip-test' WHERE id = :id")
            .param("e", vec).param("id", version).update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE poi_version SET embedding_model = 'other' WHERE id = :id")
            .param("id", version).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void embeddingWithWrongDimensionIsRejected() {
        var t = fx.tree();
        UUID poi = poi(t.org(), t.venue(), t.floor(), null);
        UUID version = poiVersion(t.org(), t.venue(), poi, 1);
        assertThatThrownBy(() -> jdbc.sql("UPDATE poi_version SET embedding = CAST('[1,2,3]' AS vector), embedding_model = 'm' WHERE id = :id")
            .param("id", version).update()).isInstanceOf(Exception.class);
    }

    @Test
    void poiWithVersionsCannotBeHardDeleted() {
        var t = fx.tree();
        UUID poi = poi(t.org(), t.venue(), t.floor(), null);
        poiVersion(t.org(), t.venue(), poi, 1);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM poi WHERE id = :id").param("id", poi).update())
            .isInstanceOf(DataIntegrityViolationException.class);
        // Soft delete is the supported path.
        assertThat(jdbc.sql("UPDATE poi SET deleted_at = now() WHERE id = :id").param("id", poi).update()).isEqualTo(1);
    }
}
