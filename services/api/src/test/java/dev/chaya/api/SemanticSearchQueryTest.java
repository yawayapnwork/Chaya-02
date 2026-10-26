package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
import dev.chaya.api.navigation.RouteService;
import dev.chaya.api.poi.PoiService;
import dev.chaya.api.poi.PoiService.PoiData;
import dev.chaya.api.search.PoiEmbeddingService;
import dev.chaya.api.search.SearchDtos;
import dev.chaya.api.search.SearchDtos.SearchPosition;
import dev.chaya.api.search.SearchDtos.SearchResponse;
import dev.chaya.api.search.SearchDtos.SearchResult;
import dev.chaya.api.search.SemanticSearchService;
import dev.chaya.api.search.SemanticSearchService.SearchQuery;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.web.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Natural-language search end to end on the database: query understanding (QueryInterpreter), name and synonym matching,
 * relevance, duplicate folding, accessibility, and "nearest" by real routes (RouteService). Embeddings are the
 * deterministic test vectors (TestEmbeddingConfig), unrelated to each other, so every match here that is not the POI's own
 * text is a name match: what is tested is the reading and ranking logic, not CLIP (SemanticSearchRankingTest and
 * SearchBenchmarkTest do that with real CLIP vectors).
 *
 * <p>The navigation graphs are SYNTHETIC fixtures (as in RouteServiceTest, which is why this class opts in to them):
 * <pre>
 *   N1 (0,5) ---- N2 (3,5)          North entrance at N1 (recorded accessible), South entrance and Cafe at N2
 *    |              |
 *   O (0,0)        SA (3,0)         Staircase A at SA: 3 m away in a straight line, 13 m to walk (O-N1-N2-SA)
 *    \______________________ SB (7,0)  Staircase B and Side entrance at SB: 7 m away, 7 m to walk (O-SB)
 * </pre>
 */
@TestPropertySource(properties = "chaya.navigation.accept-synthetic-graphs=true")
class SemanticSearchQueryTest extends AbstractIntegrationTest {

    @Autowired private SemanticSearchService search;
    @Autowired private RouteService routes;
    @Autowired private PoiService pois;

    private Fixtures.Tree t;
    private Actor actor;
    private UUID run;
    private final Map<String, UUID> ids = new HashMap<>();

    @BeforeEach
    void venue() {
        t = fx.tree();
        layout(t, true, ids);
        actor = publishedActor(t);
        run = jdbc.sql("SELECT cf.source_run_id FROM floor f JOIN coordinate_frame cf ON cf.id = f.current_coordinate_frame_id "
            + "WHERE f.id = :f").param("f", t.floor()).query(UUID.class).single();
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    /** The layout in the class comment on t's floor (calibrated), with its graphs when withGraph, published as the floor's
     * current version (which binds the graphs and staff POIs to it); the detections are then made in that version. */
    private void layout(Fixtures.Tree tree, boolean withGraph, Map<String, UUID> into) {
        fx.calibratedFloor(tree.org(), tree.venue(), tree.floor(), "FLOOR_LOCAL");
        UUID frameRun = jdbc.sql("SELECT cf.source_run_id FROM floor f JOIN coordinate_frame cf ON cf.id = f.current_coordinate_frame_id "
            + "WHERE f.id = :f").param("f", tree.floor()).query(UUID.class).single();
        if (withGraph) {
            for (String profile : List.of("STANDARD", "STEP_FREE")) {
                UUID g = jdbc.sql("INSERT INTO navigation_graph (organization_id, venue_id, floor_id, profile, status, coordinate_frame_id, "
                        + "source) VALUES (:o, :v, :f, :p, 'DRAFT', (SELECT current_coordinate_frame_id FROM floor WHERE id = :f), "
                        + "'SYNTHETIC') RETURNING id")
                    .param("o", tree.org()).param("v", tree.venue()).param("f", tree.floor()).param("p", profile).query(UUID.class).single();
                UUID o = node(tree, g, 0, 0);
                UUID n1 = node(tree, g, 0, 5);
                UUID n2 = node(tree, g, 3, 5);
                UUID sa = node(tree, g, 3, 0);
                UUID sb = node(tree, g, 7, 0);
                edge(tree, g, o, n1);
                edge(tree, g, n1, n2);
                edge(tree, g, n2, sa);
                edge(tree, g, o, sb);
            }
        }
        into.put("Staircase A", manual(tree, "Staircase A", "stairs", List.of(), 3, 0, null, true));
        into.put("Staircase B", manual(tree, "Staircase B", "stairs", List.of(), 7, 0, null, true));
        into.put("Staircase C", manual(tree, "Staircase C", "stairs", List.of(), 1, 0, null, false)); // no frame: UNBOUND
        into.put("North entrance", manual(tree, "North entrance", "entrance", List.of(), 0, 5, "{\"accessible\": true}", true));
        into.put("South entrance", manual(tree, "South entrance", "entrance", List.of(), 3, 5, "{\"accessible\": false}", true));
        into.put("Side entrance", manual(tree, "Side entrance", "entrance", List.of(), 7, 0, null, true));
        into.put("Cafe", manual(tree, "Cafe", "food", List.of("coffee"), 3, 5, null, true));
        into.put("Reception desk", manual(tree, "Reception desk", "service", List.of("front desk"), 0, 0, null, true));
        into.put("Elevator A", manual(tree, "Elevator A", "elevator", List.of(), 7, 0, null, true));
        into.put("Cloakroom", manual(tree, "Cloakroom", "service", List.of("coat check"), 0, 5, null, true));
        // Published here: a detection always belongs to a scan version (poi_version_detected_has_version).
        fx.publishDrafts(tree.org(), tree.venue(), tree.floor());
        into.put("sofa", detected(tree, frameRun, "sofa", 5, 1, 0.9, "MULTI_VIEW"));
        into.put("couch near the sofa", detected(tree, frameRun, "couch", 5.3, 1, 0.6, "SINGLE_VIEW"));
        into.put("couch far away", detected(tree, frameRun, "couch", 12, 1, 0.8, "MULTI_VIEW"));
        into.put("chair", detected(tree, frameRun, "chair", 2, 2, 0.36, "SINGLE_VIEW"));
    }

    private UUID node(Fixtures.Tree tree, UUID graph, double x, double y) {
        return jdbc.sql("INSERT INTO navigation_node (organization_id, venue_id, graph_id, floor_id, kind, x, y, z) "
                + "VALUES (:o, :v, :g, :f, 'WAYPOINT', :x, :y, 0) RETURNING id")
            .param("o", tree.org()).param("v", tree.venue()).param("g", graph).param("f", tree.floor()).param("x", x).param("y", y)
            .query(UUID.class).single();
    }

    private void edge(Fixtures.Tree tree, UUID graph, UUID from, UUID to) {
        jdbc.sql("INSERT INTO navigation_edge (organization_id, venue_id, graph_id, from_node_id, to_node_id, length_m, step_free, "
                + "bidirectional, min_clearance_m, max_slope_deg) VALUES (:o, :v, :g, :a, :b, 1.0, true, true, 1.2, 0.0)")
            .param("o", tree.org()).param("v", tree.venue()).param("g", graph).param("a", from).param("b", to).update();
    }

    /** A staff POI with the text embedding the backfill gives it; inFrame false leaves it in no frame (UNBOUND). */
    private UUID manual(Fixtures.Tree tree, String label, String category, List<String> tags, double x, double y, String attributes,
                        boolean inFrame) {
        UUID id = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", tree.org()).param("v", tree.venue()).param("f", tree.floor()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, category, tags, x, y, z, attributes,
                    embedding, embedding_model, source, created_by, coordinate_frame_id, scan_version_id)
                VALUES (:o, :v, :p, 1, :label, :cat, CAST(:tags AS text[]), :x, :y, 0, CAST(:attr AS jsonb), CAST(:e AS vector), :m,
                    'MANUAL', 'test', CASE WHEN :inFrame THEN (SELECT current_coordinate_frame_id FROM floor WHERE id = :f) END,
                    (SELECT current_scan_version_id FROM floor WHERE id = :f))""")
            .param("o", tree.org()).param("v", tree.venue()).param("p", id).param("label", label).param("cat", category)
            .param("tags", "{" + String.join(",", tags.stream().map(s -> "\"" + s + "\"").toList()) + "}")
            .param("x", x).param("y", y).param("attr", attributes == null ? "{}" : attributes).param("f", tree.floor())
            .param("inFrame", inFrame)
            .param("e", TestEmbeddingConfig.vectorLiteral(TestEmbeddingConfig.embedFor(PoiEmbeddingService.embeddingText(label, category, tags))))
            .param("m", TestEmbeddingConfig.MODEL).update();
        return id;
    }

    /** A detection as ingestion stores it, placed with depth evidence, with its label's text embedding, in the floor's
     * current version. */
    private UUID detected(Fixtures.Tree tree, UUID pipelineRun, String label, double x, double y, double confidence, String localization) {
        return detected(tree, pipelineRun, label, x, y, confidence, localization, null, null, null);
    }

    /** As above, in scan version `version` instead (when not null), with its DETECTED_OBJECTS artifact and provenance. */
    private UUID detected(Fixtures.Tree tree, UUID pipelineRun, String label, double x, double y, double confidence, String localization,
                          UUID version, UUID artifact, String provenance) {
        UUID id = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", tree.org()).param("v", tree.venue()).param("f", tree.floor()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z, embedding,
                    embedding_model, source, detection_confidence, pipeline_run_id, created_by, coordinate_frame_id,
                    localization_status, localization_uncertainty_m, scan_version_id, detection_artifact_id, detection_provenance)
                VALUES (:o, :v, :p, 1, :label, '{}', :x, :y, 0, CAST(:e AS vector), :m, 'AUTO_DETECTED', :c, :run,
                    'system:semantic-indexing', (SELECT current_coordinate_frame_id FROM floor WHERE id = :f), :loc, 0.1,
                    COALESCE(CAST(:sv AS uuid), (SELECT current_scan_version_id FROM floor WHERE id = :f)), :artifact,
                    CAST(:prov AS jsonb))""")
            .param("sv", version).param("artifact", artifact).param("prov", provenance)
            .param("o", tree.org()).param("v", tree.venue()).param("p", id).param("label", label).param("x", x).param("y", y)
            .param("f", tree.floor()).param("c", confidence).param("run", pipelineRun).param("loc", localization)
            .param("e", TestEmbeddingConfig.vectorLiteral(TestEmbeddingConfig.embedFor(label))).param("m", TestEmbeddingConfig.MODEL)
            .update();
        return id;
    }

    /** A caller of the venue (a visitor). */
    private Actor publishedActor(Fixtures.Tree tree) {
        return new Actor(Actor.Kind.USER, "visitor", tree.org(), Set.of(tree.venue()), Set.of(Role.VIEWER));
    }

    private SearchResponse query(String q) {
        return search.search(actor, t.venue(), new SearchQuery(q, null, null, null, null, null));
    }

    private SearchResponse queryFrom(String q, double x, double y) {
        return search.search(actor, t.venue(), new SearchQuery(q, null, null, null, null, new SearchPosition(t.floor(), x, y, 0)));
    }

    private List<UUID> poiIds(List<SearchResult> results) {
        return results.stream().map(SearchResult::poiId).toList();
    }

    private UUID id(String name) {
        return ids.get(name);
    }

    // ---- exact, synonym, misspelt, no result --------------------------------------------------------------------------

    @Test
    void aNamedPlaceIsFoundByItsNameAndReadAsOne() {
        SearchResponse r = query("Where is the reception?");
        assertThat(r.interpretation().subject()).isEqualTo("reception");
        assertThat(r.results().get(0).poiId()).isEqualTo(id("Reception desk"));
        assertThat(r.results().get(0).exactMatch()).as("its label is a phrasing of the query").isTrue();
        assertThat(r.results().get(0).matchedTerm()).as("the user's own word, before any synonym").isEqualTo("reception");
        assertThat(r.interpretation().targetKind()).isEqualTo("NAMED_PLACE");

        SearchResponse exact = query("Reception desk");
        assertThat(exact.results().get(0).poiId()).isEqualTo(id("Reception desk"));
        assertThat(exact.results().get(0).exactMatch()).isTrue();
    }

    @Test
    void aSynonymFromTheTableFindsAPlaceThatNeverUsesTheWord() {
        SearchResponse r = query("Where is the lift?");
        assertThat(r.results()).isNotEmpty();
        SearchResult elevator = r.results().get(0);
        assertThat(elevator.poiId()).isEqualTo(id("Elevator A"));
        assertThat(elevator.matchedBy()).isEqualTo("SYNONYM");
        assertThat(elevator.matchedTerm()).isEqualTo("elevator");
        assertThat(r.interpretation().synonymGroups()).containsExactly("elevator");
        assertThat(r.interpretation().targetKind()).isEqualTo("CATEGORY");
    }

    @Test
    void aMisspeltWordIsCorrectedToTheVenuesWordAndSaysSo() {
        SearchResponse r = query("find the staircse");
        assertThat(r.interpretation().corrections()).containsExactly(new SearchDtos.Correction("staircse", "staircase"));
        assertThat(poiIds(r.results())).containsExactlyInAnyOrder(id("Staircase A"), id("Staircase B"), id("Staircase C"));
    }

    @Test
    void somethingTheVenueDoesNotHaveReturnsNothingAndOnlyRealSuggestions() {
        SearchResponse r = query("swimming pool");
        assertThat(r.relevance()).isEqualTo("FILTERED");
        assertThat(r.results()).isEmpty();
        assertThat(r.closestMatches()).hasSize(3);
        assertThat(r.suggestions()).as("suggestions are only labels of places that exist here")
            .containsExactlyElementsOf(r.closestMatches().stream().map(SearchResult::label).distinct().toList());
        assertThat(r.lowConfidence()).isFalse();
    }

    // ---- nearest, by real routes (search-to-route) --------------------------------------------------------------------

    @Test
    void nearestOrdersByWalkingRouteNotByStraightLineAndTheRouteIsTheOneReported() {
        SearchResponse r = queryFrom("Find the nearest staircase", 0, 0);

        assertThat(r.interpretation().spatialModifierApplied()).isTrue();
        // Staircase A is 3 m away in a straight line but 13 m to walk; B is 7 m both ways. C is in no frame: last.
        assertThat(poiIds(r.results())).containsExactly(id("Staircase B"), id("Staircase A"), id("Staircase C"));
        SearchResult b = r.results().get(0);
        assertThat(b.distanceKind()).isEqualTo("ROUTE");
        assertThat(b.distanceMeters()).isCloseTo(7.0, within(1e-6));
        assertThat(r.results().get(1).distanceMeters()).isCloseTo(13.0, within(1e-6));
        SearchResult c = r.results().get(2);
        assertThat(c.spatialStatus()).isEqualTo("UNBOUND");
        assertThat(c.distanceMeters()).as("a position in no frame is never measured against").isNull();

        // "Navigate" on the selected result: the route the viewer then requests is the one that was measured.
        RouteResponse route = routes.route(actor, new RouteRequest(t.venue(), t.floor(), List.of(0.0, 0.0, 0.0), b.poiId(),
            "STANDARD", List.of()));
        assertThat(route.distanceMeters()).isCloseTo(b.distanceMeters(), within(1e-9));
    }

    @Test
    void nearestWithoutAPositionIsReportedAsNotAppliedNeverGuessed() {
        SearchResponse r = query("Show me the closest staircase");
        assertThat(r.interpretation().spatialModifier()).isEqualTo("NEAREST");
        assertThat(r.interpretation().spatialModifierApplied()).isFalse();
        assertThat(r.interpretation().spatialNote()).isEqualTo("NO_POSITION");
        assertThat(r.results()).hasSize(3).allSatisfy(x -> {
            assertThat(x.distanceMeters()).isNull();
            assertThat(x.routeStatus()).isNull();
        });
    }

    @Test
    void identicalInputsGiveIdenticalOrders() {
        List<UUID> first = poiIds(queryFrom("nearest staircase", 0, 0).results());
        for (int i = 0; i < 3; i++) {
            assertThat(poiIds(queryFrom("nearest staircase", 0, 0).results())).isEqualTo(first);
        }
    }

    @Test
    void withoutARoutableGraphNearestFallsBackToStraightLineAndSaysSo() {
        var other = fx.tree();
        Map<String, UUID> otherIds = new HashMap<>();
        layout(other, false, otherIds);
        Actor visitor = publishedActor(other);
        SearchResponse r = search.search(visitor, other.venue(), new SearchQuery("nearest staircase", null, null, null, null,
            new SearchPosition(other.floor(), 0, 0, 0)));

        assertThat(r.results().subList(0, 2)).extracting(SearchResult::poiId)
            .containsExactly(otherIds.get("Staircase A"), otherIds.get("Staircase B"));
        assertThat(r.results().subList(0, 2)).allSatisfy(x -> {
            assertThat(x.distanceKind()).isEqualTo("STRAIGHT_LINE");
            assertThat(x.routeStatus()).isEqualTo("NAVMESH_NOT_READY");
        });
        assertThat(r.results().get(0).distanceMeters()).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void aPositionMustBeOnAFloorOfTheVenueAndFinite() {
        var other = fx.tree();
        assertThatThrownBy(() -> search.search(actor, t.venue(), new SearchQuery("nearest staircase", null, null, null, null,
            new SearchPosition(other.floor(), 0, 0, 0))))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_POSITION"));
        assertThatThrownBy(() -> search.search(actor, t.venue(), new SearchQuery("nearest staircase", null, null, null, null,
            new SearchPosition(t.floor(), Double.NaN, 0, 0))))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_POSITION"));
    }

    // ---- accessibility: only what staff recorded ----------------------------------------------------------------------

    @Test
    void anAccessibleEntranceIsOneStaffRecordedAsAccessibleRoutedStepFree() {
        SearchResponse r = queryFrom("Find the nearest wheelchair-accessible entrance", 0, 0);
        assertThat(r.interpretation().accessibilityRequired()).isTrue();
        assertThat(poiIds(r.results())).containsExactly(id("North entrance"));
        assertThat(r.results().get(0).accessible()).isTrue();
        assertThat(r.results().get(0).distanceKind()).isEqualTo("ROUTE");
        assertThat(r.results().get(0).distanceMeters()).isCloseTo(5.0, within(1e-6));
    }

    @Test
    void withNothingRecordedAsAccessibleTheUnrecordedMatchesAreOfferedAsSuchNeverAsResults() {
        SearchResponse r = query("wheelchair accessible cafe");
        assertThat(r.results()).isEmpty();
        assertThat(poiIds(r.accessibilityUnconfirmed())).containsExactly(id("Cafe"));
        assertThat(r.accessibilityUnconfirmed().get(0).accessible()).as("nobody recorded it either way").isNull();

        SearchResponse entrances = query("accessible entrance");
        assertThat(poiIds(entrances.results())).containsExactly(id("North entrance"));
        assertThat(poiIds(entrances.accessibilityUnconfirmed())).isEmpty();
    }

    @Test
    void staffRecordAccessibilityThroughThePoiApiAndAnEditKeepsIt() {
        Actor manager = new Actor(Actor.Kind.USER, "manager", t.org(), Set.of(t.venue()), Set.of(Role.VENUE_MANAGER));
        var ramp = pois.create(manager, t.venue(), new PoiData(t.floor(), null, "Garden gate", "entrance", null, List.of(), 1, 1, 0,
            true));
        assertThat(ramp.accessible()).isTrue();
        // An edit that does not mention accessibility keeps what staff recorded.
        var renamed = pois.update(manager, t.venue(), ramp.id(), new PoiData(t.floor(), null, "Garden gate (east)", "entrance", null,
            List.of(), 1, 1, 0));
        assertThat(renamed.version()).isEqualTo(2);
        assertThat(renamed.accessible()).isTrue();

        SearchResponse r = query("accessible entrance");
        assertThat(poiIds(r.results())).contains(ramp.id(), id("North entrance"))
            .doesNotContain(id("South entrance"), id("Side entrance"));

        var notAccessible = pois.update(manager, t.venue(), ramp.id(), new PoiData(t.floor(), null, "Garden gate (east)", "entrance",
            null, List.of(), 1, 1, 0, false));
        assertThat(notAccessible.accessible()).isFalse();
        assertThat(poiIds(query("accessible entrance").results())).doesNotContain(ramp.id());
    }

    @Test
    void aPlaceRecordedAsNotAccessibleIsNeverOfferedForAnAccessibilityQuery() {
        // Only South entrance and Side entrance match "south entrance" by name; South is recorded as not accessible.
        SearchResponse r = query("accessible south entrance");
        assertThat(poiIds(r.results())).doesNotContain(id("South entrance"));
        assertThat(poiIds(r.accessibilityUnconfirmed())).doesNotContain(id("South entrance"));
    }

    // ---- detections: duplicates, confidence, provenance ---------------------------------------------------------------

    @Test
    void twoDetectionsOfOneSofaUnderTwoLabelsAreOneResultAndAFarCouchStaysItsOwn() {
        SearchResponse r = query("Where is the sofa?");
        assertThat(poiIds(r.results())).containsExactly(id("sofa"), id("couch far away"));
        assertThat(r.results().get(0).duplicateIds()).containsExactly(id("couch near the sofa"));
        assertThat(r.results().get(1).duplicateIds()).isEmpty();
    }

    @Test
    void aLowConfidenceDetectionIsShownAsSuch() {
        SearchResponse r = query("chair");
        assertThat(poiIds(r.results())).containsExactly(id("chair"));
        assertThat(r.results().get(0).lowConfidence()).isTrue();
        assertThat(r.lowConfidence()).as("every result is weak evidence").isTrue();

        SearchResponse sofa = query("sofa");
        assertThat(sofa.results().get(0).lowConfidence()).isFalse();
        assertThat(sofa.lowConfidence()).isFalse();
        assertThat(query("Reception desk").results().get(0).lowConfidence()).as("not a detection").isNull();
    }

    @Test
    void aDetectedResultNamesTheRunVersionArtifactAndFramesItCameFrom() {
        UUID scan = jdbc.sql("SELECT scan_id FROM pipeline_run WHERE id = :r").param("r", run).query(UUID.class).single();
        UUID artifact = fx.publishedArtifact(t.org(), t.venue(), scan, run, "SEMANTIC_INDEXING", "DETECTED_OBJECTS");
        // As PipelineService#insertDetectedPoi writes it (PipelineControlPlaneTest checks ingestion itself).
        UUID bench = detected(t, run, "bench", 9, 9, 0.8, "MULTI_VIEW", null, artifact,
            "{\"detectorModel\": \"IDEA-Research/grounding-dino-tiny\", \"sourceFrames\": [\"f1.jpg\", \"f7.jpg\"]}");

        SearchResult r = query("bench").results().get(0);
        assertThat(r.poiId()).isEqualTo(bench);
        assertThat(r.pipelineRunId()).isEqualTo(run);
        assertThat(r.scanVersionId()).isNotNull();
        assertThat(r.detectionArtifactId()).isEqualTo(artifact);
        assertThat(r.provenance()).containsEntry("sourceFrames", List.of("f1.jpg", "f7.jpg"));
    }

    // ---- isolation and versions ---------------------------------------------------------------------------------------

    @Test
    void anotherTenantsPlacesAreNeverResultsNeverDistancesAndItsFloorsAreNoPosition() {
        var other = fx.tree();
        Map<String, UUID> otherIds = new HashMap<>();
        layout(other, true, otherIds);
        publishedActor(other);
        UUID theirs = manual(other, "Staircase Z", "stairs", List.of(), 0.5, 0, null, true);

        SearchResponse r = queryFrom("nearest staircase", 0, 0);
        assertThat(poiIds(r.results())).doesNotContain(theirs).doesNotContainAnyElementsOf(otherIds.values());
        assertThat(poiIds(r.closestMatches())).doesNotContainAnyElementsOf(otherIds.values());
        assertThatThrownBy(() -> search.search(actor, other.venue(), new SearchQuery("staircase", null, null, null, null, null)))
            .as("and their venue is not searchable at all").isInstanceOf(RuntimeException.class);
    }

    @Test
    void aDetectionOfAnUnpublishedVersionIsNeverReturnedNextToThePublishedOnes() {
        // A newer run's detection of a sofa, its version still a draft (the tree's own draft version).
        UUID draftSofa = detected(t, run, "sofa", 1, 1, 0.99, "MULTI_VIEW", t.version(), null, null);

        SearchResponse r = query("sofa");
        assertThat(poiIds(r.results())).contains(id("sofa")).doesNotContain(draftSofa);
        assertThat(poiIds(r.closestMatches())).doesNotContain(draftSofa);
    }

    // ---- index lifecycle --------------------------------------------------------------------------------------------

    @Test
    void aPoiAwaitingItsEmbeddingIsFoundByNameAndMarkedAsSuch() {
        UUID pending = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", t.org()).param("v", t.venue()).param("f", t.floor()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, category, tags, x, y, z, source,
                    created_by, coordinate_frame_id, scan_version_id)
                VALUES (:o, :v, :p, 1, 'Prayer room', 'quiet room', '{}', 1, 1, 0, 'MANUAL', 'test',
                    (SELECT current_coordinate_frame_id FROM floor WHERE id = :f), (SELECT current_scan_version_id FROM floor WHERE id = :f))""")
            .param("o", t.org()).param("v", t.venue()).param("p", pending).param("f", t.floor()).update();

        SearchResponse r = query("prayer room");
        assertThat(poiIds(r.results())).containsExactly(pending);
        assertThat(r.results().get(0).indexStatus()).isEqualTo("PENDING_EMBEDDING");
        assertThat(r.results().get(0).rankScore()).isNull();
        assertThat(r.results().get(0).diagnostics()).isNotNull();
        assertThat(r.results().get(0).diagnostics().retrievalMode()).isEqualTo("LEXICAL_ONLY");
        assertThat(r.results().get(0).diagnostics().embeddingFailed()).isFalse();
        assertThat(r.indexDiagnostics()).isNotNull();
        assertThat(r.indexDiagnostics().indexComplete()).isFalse();
        assertThat(r.indexDiagnostics().pendingEmbeddingPois()).isEqualTo(1);
        assertThat(r.indexDiagnostics().failedEmbeddingPois()).isZero();

        UUID pendingVersion = jdbc.sql("SELECT id FROM poi_version WHERE poi_id = :p").param("p", pending).query(UUID.class).single();
        jdbc.sql("INSERT INTO poi_embedding_failure (poi_version_id, attempts, last_error, next_attempt_at) "
                + "VALUES (:id, 2, 'vision timeout', now() + interval '1 minute')")
            .param("id", pendingVersion).update();

        SearchResponse failed = query("prayer room");
        assertThat(failed.indexDiagnostics().indexComplete()).isFalse();
        assertThat(failed.indexDiagnostics().failedEmbeddingPois()).isEqualTo(1);
        assertThat(failed.results().get(0).diagnostics().embeddingFailed()).isTrue();
        assertThat(failed.results().get(0).diagnostics().explanation()).contains("embedding failed; awaiting retry");

        assertThat(query("cafe").results()).allSatisfy(x -> assertThat(x.indexStatus()).isEqualTo("INDEXED"));
    }
}
