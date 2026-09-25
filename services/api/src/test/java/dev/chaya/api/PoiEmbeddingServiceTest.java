package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.chaya.api.poi.PoiService;
import dev.chaya.api.poi.PoiService.PoiData;
import dev.chaya.api.search.PoiEmbeddingService;
import dev.chaya.api.search.SearchDtos.SearchResponse;
import dev.chaya.api.search.SemanticSearchService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The POI embedding backfill against real PostgreSQL/pgvector and the real poi_version immutability trigger, with the
 * deterministic TestEmbeddingConfig vectors standing in for CLIP. The database is shared with other test classes, so
 * every assertion is about this test's own rows, never about global counts.
 */
class PoiEmbeddingServiceTest extends AbstractIntegrationTest {

    @Autowired private PoiEmbeddingService embeddings;
    @Autowired private PoiService pois;
    @Autowired private SemanticSearchService search;

    @AfterEach
    void visionBack() {
        TestEmbeddingConfig.UNAVAILABLE.set(false);
    }

    private Actor manager(UUID org, UUID venue) {
        return new Actor(Actor.Kind.USER, "manager", org, Set.of(venue), Set.of(Role.VENUE_MANAGER));
    }

    private record Row(boolean hasEmbedding, String model, String embedding) {}

    private boolean embeddingIs(UUID poi, int number, float[] expected) {
        return jdbc.sql("SELECT embedding = CAST(:e AS vector) FROM poi_version WHERE poi_id = :p AND version_number = :n")
            .param("e", TestEmbeddingConfig.vectorLiteral(expected)).param("p", poi).param("n", number)
            .query(Boolean.class).single();
    }

    private Row version(UUID poi, int number) {
        return jdbc.sql("SELECT embedding IS NOT NULL AS has, embedding_model, CAST(embedding AS text) AS e "
                + "FROM poi_version WHERE poi_id = :p AND version_number = :n")
            .param("p", poi).param("n", number)
            .query((rs, i) -> new Row(rs.getBoolean("has"), rs.getString("embedding_model"), rs.getString("e"))).single();
    }

    @Test
    void aManuallyCreatedPoiBecomesFindableBySemanticSearchOnceEmbedded() {
        var t = fx.tree();
        Actor a = manager(t.org(), t.venue());
        var restroom = pois.create(a, t.venue(), new PoiData(t.floor(), null, "Accessible restroom", "restroom", null,
            List.of("toilet", "wc"), 8.5, 0, 3));
        pois.create(a, t.venue(), new PoiData(t.floor(), null, "Cafe", "food", null, List.of("coffee"), 12, 0, -4));

        assertThat(version(restroom.id(), 1).hasEmbedding()).isFalse();
        SearchResponse before = search.search(a, t.venue(), "Accessible restroom, restroom, toilet, wc", null, null, null);
        assertThat(before.matchType()).isEqualTo("embedding");
        assertThat(before.results()).as("not searchable before the backfill").isEmpty();

        PoiEmbeddingService.Result r = embeddings.embedPending(10_000);
        assertThat(r.stoppedBecause()).isNull();

        Row row = version(restroom.id(), 1);
        assertThat(row.hasEmbedding()).isTrue();
        assertThat(row.model()).isEqualTo(TestEmbeddingConfig.MODEL);
        // The exact text that was embedded: label, category, tags (deterministic test vectors make this checkable).
        assertThat(embeddingIs(restroom.id(), 1, TestEmbeddingConfig.embedFor("Accessible restroom, restroom, toilet, wc"))).isTrue();

        SearchResponse after = search.search(a, t.venue(), "Accessible restroom, restroom, toilet, wc", null, null, null);
        assertThat(after.matchType()).isEqualTo("embedding");
        assertThat(after.results()).hasSize(2);
        assertThat(after.results().get(0).label()).isEqualTo("Accessible restroom");
    }

    @Test
    void onlyTheLatestVersionIsEmbeddedAndAnEditIsEmbeddedAgain() {
        var t = fx.tree();
        Actor a = manager(t.org(), t.venue());
        var poi = pois.create(a, t.venue(), new PoiData(t.floor(), null, "Cafe", "food", null, List.of(), 1, 0, 1));
        pois.update(a, t.venue(), poi.id(), new PoiData(t.floor(), null, "Cafe & bakery", "food", null, List.of(), 1, 0, 1));

        embeddings.embedPending(10_000);

        assertThat(version(poi.id(), 1).hasEmbedding()).as("superseded: never searched, never embedded").isFalse();
        assertThat(version(poi.id(), 2).hasEmbedding()).isTrue();

        pois.update(a, t.venue(), poi.id(), new PoiData(t.floor(), null, "Bakery", "food", null, List.of(), 1, 0, 1));
        embeddings.embedPending(10_000);
        assertThat(version(poi.id(), 3).hasEmbedding()).isTrue();
    }

    @Test
    void whileTheModelIsUnavailableNothingIsWrittenAndTheBacklogIsWorkedOffLater() {
        var t = fx.tree();
        Actor a = manager(t.org(), t.venue());
        var poi = pois.create(a, t.venue(), new PoiData(t.floor(), null, "Elevator A", "elevator", null, List.of("lift"), 5, 0, 6));

        TestEmbeddingConfig.UNAVAILABLE.set(true);
        PoiEmbeddingService.Result down = embeddings.embedPending(10_000);
        assertThat(down.stoppedBecause()).contains("down");
        assertThat(version(poi.id(), 1).hasEmbedding()).isFalse();
        assertThat(embeddings.pendingCount()).isPositive();

        TestEmbeddingConfig.UNAVAILABLE.set(false);
        assertThat(embeddings.embedPending(10_000).stoppedBecause()).isNull();
        assertThat(version(poi.id(), 1).hasEmbedding()).isTrue();
    }

    @Test
    void deletedPoisAreNotEmbeddedAndASecondPassChangesNothing() {
        var t = fx.tree();
        Actor a = manager(t.org(), t.venue());
        var gone = pois.create(a, t.venue(), new PoiData(t.floor(), null, "Old kiosk", null, null, List.of(), 0, 0, 0));
        pois.delete(a, t.venue(), gone.id());
        var kept = pois.create(a, t.venue(), new PoiData(t.floor(), null, "Reception desk", "service", null, List.of(), 2, 0, 1));

        embeddings.embedPending(10_000);
        Row first = version(kept.id(), 1);
        assertThat(version(gone.id(), 1).hasEmbedding()).isFalse();
        assertThat(first.hasEmbedding()).isTrue();

        embeddings.embedPending(10_000);
        assertThat(version(kept.id(), 1)).as("an embedding is set once, never rewritten").isEqualTo(first);
    }

    @Test
    void anExistingEmbeddingIsNeverOverwritten() {
        var t = fx.tree();
        UUID poi = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", t.org()).param("v", t.venue()).param("f", t.floor()).query(UUID.class).single();
        float[] image = TestEmbeddingConfig.embedFor("an image of a sofa");
        String detected = TestEmbeddingConfig.vectorLiteral(image);
        jdbc.sql("INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z, "
                + "embedding, embedding_model, source, created_by) "
                + "VALUES (:o, :v, :p, 1, 'Sofa', '{}', 0, 0, 0, CAST(:e AS vector), 'clip-image', 'MANUAL', 'test')")
            .param("o", t.org()).param("v", t.venue()).param("p", poi).param("e", detected).update();

        embeddings.embedPending(10_000);

        Row row = version(poi, 1);
        assertThat(row.model()).isEqualTo("clip-image");
        assertThat(embeddingIs(poi, 1, image)).isTrue();
    }

    @Test
    void embeddingTextIsTheUserEnteredMetadataDeduplicated() {
        assertThat(PoiEmbeddingService.embeddingText("Accessible restroom", "restroom", List.of("toilet", "WC", "Toilet")))
            .isEqualTo("Accessible restroom, restroom, toilet, WC");
        assertThat(PoiEmbeddingService.embeddingText("Cafe", null, List.of())).isEqualTo("Cafe");
        assertThat(PoiEmbeddingService.embeddingText("Lift", " lift ", null)).isEqualTo("Lift");
    }
}
