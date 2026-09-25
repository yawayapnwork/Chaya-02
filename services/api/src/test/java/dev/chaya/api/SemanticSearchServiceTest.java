package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.search.SearchDtos.SearchResponse;
import dev.chaya.api.search.SemanticSearchService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Real pgvector cosine search, exercised against the TestEmbeddingConfig fake embedding function (a
 * deterministic, real unit-vector geometry -- see that class's docstring for why that is not "fake
 * embeddings" in the sense the pipeline itself must never produce them). Skipped, not failed, without
 * Docker (AbstractIntegrationTest).
 */
class SemanticSearchServiceTest extends AbstractIntegrationTest {

    @Autowired
    private SemanticSearchService search;

    @AfterEach
    void resetEmbeddingAvailability() {
        TestEmbeddingConfig.UNAVAILABLE.set(false);
    }

    private Actor actorFor(UUID org, UUID venue) {
        return new Actor(Actor.Kind.USER, "test-user", org, Set.of(venue), Set.of(Role.ADMIN));
    }

    private UUID insertPoi(UUID org, UUID venue, UUID floor, String label, double x, double y, double z, float[] embedding) {
        UUID poiId = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", org).param("v", venue).param("f", floor).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z,
                                         embedding, embedding_model, source, created_by)
                VALUES (:o, :v, :p, 1, :label, '{}', :x, :y, :z, CAST(:emb AS vector), 'test-model', 'MANUAL', 'test')
                """)
            .param("o", org).param("v", venue).param("p", poiId).param("label", label)
            .param("x", x).param("y", y).param("z", z).param("emb", TestEmbeddingConfig.vectorLiteral(embedding))
            .update();
        return poiId;
    }

    // ---- embedding storage / vector similarity ---------------------------------------------------

    @Test
    void anEmbeddingRoundTripsThroughPgvectorExactlyEnoughToRankItselfFirst() {
        var t = fx.tree();
        float[] chairEmbedding = TestEmbeddingConfig.embedFor("chair");
        insertPoi(t.org(), t.venue(), t.floor(), "Reception chair", 1, 2, 0, chairEmbedding);

        SearchResponse response = search.search(actorFor(t.org(), t.venue()), t.venue(), "chair", null, null, null);

        assertThat(response.matchType()).isEqualTo("embedding");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).similarity()).isCloseTo(1.0, within(1e-4));
    }

    @Test
    void queryRankingPutsTheClosestEmbeddingFirst() {
        var t = fx.tree();
        insertPoi(t.org(), t.venue(), t.floor(), "Lobby sofa", 0, 0, 0, TestEmbeddingConfig.embedFor("chair"));
        insertPoi(t.org(), t.venue(), t.floor(), "Fire extinguisher", 5, 5, 0, TestEmbeddingConfig.embedFor("fire extinguisher"));

        SearchResponse response = search.search(actorFor(t.org(), t.venue()), t.venue(), "chair", null, null, null);

        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).label()).isEqualTo("Lobby sofa");
        assertThat(response.results().get(0).similarity()).isGreaterThan(response.results().get(1).similarity());
    }

    @Test
    void topKClampsToTheConfiguredMaximumAndAtLeastOne() {
        var t = fx.tree();
        for (int i = 0; i < 3; i++) {
            insertPoi(t.org(), t.venue(), t.floor(), "chair " + i, i, 0, 0, TestEmbeddingConfig.embedFor("chair " + i));
        }
        SearchResponse response = search.search(actorFor(t.org(), t.venue()), t.venue(), "chair", null, 2, null);
        assertThat(response.results()).hasSize(2);
    }

    // ---- venue isolation --------------------------------------------------------------------------

    @Test
    void aSearchNeverReturnsAnotherVenuesPois() {
        var t1 = fx.tree();
        var t2 = fx.tree();
        float[] embedding = TestEmbeddingConfig.embedFor("chair");
        insertPoi(t1.org(), t1.venue(), t1.floor(), "Venue 1 chair", 0, 0, 0, embedding);
        insertPoi(t2.org(), t2.venue(), t2.floor(), "Venue 2 chair", 0, 0, 0, embedding);

        SearchResponse response = search.search(actorFor(t1.org(), t1.venue()), t1.venue(), "chair", null, null, null);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).label()).isEqualTo("Venue 1 chair");
    }

    // ---- empty results ------------------------------------------------------------------------------

    @Test
    void aVenueWithNoMatchingPoisReturnsAnEmptyListNotAnError() {
        var t = fx.tree();
        SearchResponse response = search.search(actorFor(t.org(), t.venue()), t.venue(), "anything", null, null, null);
        assertThat(response.results()).isEmpty();
    }

    @Test
    void floorFilterExcludesPoisOnOtherFloors() {
        var t = fx.tree();
        UUID otherFloor = fx.floor(t.org(), t.venue(), 1);
        float[] embedding = TestEmbeddingConfig.embedFor("chair");
        insertPoi(t.org(), t.venue(), otherFloor, "Other floor chair", 0, 0, 0, embedding);

        SearchResponse response = search.search(actorFor(t.org(), t.venue()), t.venue(), "chair", t.floor(), null, null);

        assertThat(response.results()).isEmpty();
    }

    // ---- relevance (chaya.search.relevance-min-margin = 0.3 in tests: see AbstractIntegrationTest) -----------------

    private static final String[] VENUE = {"Reception desk", "Cafe", "Cloakroom", "Elevator", "Stairs", "Restroom",
        "Auditorium", "Cash machine"};

    private void furnish(Fixtures.Tree t) {
        for (int i = 0; i < VENUE.length; i++) {
            insertPoi(t.org(), t.venue(), t.floor(), VENUE[i], i, 0, 0, TestEmbeddingConfig.embedFor(VENUE[i]));
        }
    }

    @Test
    void aQueryForSomethingTheVenueDoesNotHaveReturnsNoResultsButTheClosestCandidates() {
        var t = fx.tree();
        furnish(t);

        SearchResponse response = search.search(actorFor(t.org(), t.venue()), t.venue(), "swimming pool", null, null, null);

        assertThat(response.matchType()).isEqualTo("embedding");
        assertThat(response.relevance()).isEqualTo("FILTERED");
        assertThat(response.results()).as("nothing stands out from the rest of the venue").isEmpty();
        assertThat(response.closestMatches()).hasSize(3);
        assertThat(response.closestMatches()).allSatisfy(r -> assertThat(r.relevanceMargin()).isLessThan(0.3));
        assertThat(response.closestMatches().get(0).similarity()).isGreaterThanOrEqualTo(response.closestMatches().get(1).similarity());
        Integer logged = jdbc.sql("SELECT result_count FROM search_query WHERE venue_id = :v ORDER BY created_at DESC LIMIT 1")
            .param("v", t.venue()).query(Integer.class).single();
        assertThat(logged).as("counted as a zero-result query in search analytics").isZero();
    }

    @Test
    void aRealMatchStandsOutFromTheVenueAndIsTheOnlyResult() {
        var t = fx.tree();
        furnish(t);

        SearchResponse response = search.search(actorFor(t.org(), t.venue()), t.venue(), "cafe", null, null, null);

        assertThat(response.relevance()).isEqualTo("FILTERED");
        assertThat(response.results()).extracting(r -> r.label()).containsExactly("Cafe");
        assertThat(response.results().get(0).relevanceMargin()).isGreaterThan(0.3);
        assertThat(response.closestMatches()).as("only given when nothing matched").isEmpty();
    }

    @Test
    void withTooFewPoisToCompareAgainstResultsAreNotFiltered() {
        var t = fx.tree();
        insertPoi(t.org(), t.venue(), t.floor(), "Cafe", 0, 0, 0, TestEmbeddingConfig.embedFor("Cafe"));
        insertPoi(t.org(), t.venue(), t.floor(), "Cloakroom", 1, 0, 0, TestEmbeddingConfig.embedFor("Cloakroom"));

        SearchResponse response = search.search(actorFor(t.org(), t.venue()), t.venue(), "swimming pool", null, null, null);

        assertThat(response.relevance()).isEqualTo("UNFILTERED");
        assertThat(response.results()).hasSize(2);
        assertThat(response.closestMatches()).isEmpty();
    }

    // ---- unavailable embedding model ------------------------------------------------------------------

    @Test
    void whenTheEmbeddingModelIsUnavailableSearchFallsBackToLexicalMatchingInsteadOfFailing() {
        var t = fx.tree();
        insertPoi(t.org(), t.venue(), t.floor(), "Reception chair", 0, 0, 0, TestEmbeddingConfig.embedFor("chair"));
        TestEmbeddingConfig.UNAVAILABLE.set(true);

        SearchResponse response = search.search(actorFor(t.org(), t.venue()), t.venue(), "chair", null, null, null);

        assertThat(response.matchType()).isEqualTo("lexical_fallback");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).label()).isEqualTo("Reception chair");
    }

    @Test
    void theLexicalFallbackFindsNothingForAnUnrelatedWordJustLikeVectorSearchWould() {
        var t = fx.tree();
        insertPoi(t.org(), t.venue(), t.floor(), "Reception chair", 0, 0, 0, TestEmbeddingConfig.embedFor("chair"));
        TestEmbeddingConfig.UNAVAILABLE.set(true);

        SearchResponse response = search.search(actorFor(t.org(), t.venue()), t.venue(), "zzzznotarealword", null, null, null);

        assertThat(response.results()).isEmpty();
    }
}
