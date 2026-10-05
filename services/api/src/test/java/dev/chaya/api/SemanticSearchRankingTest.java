package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chaya.api.search.PoiEmbeddingService;
import dev.chaya.api.search.SearchDtos.SearchResponse;
import dev.chaya.api.search.SearchDtos.SearchResult;
import dev.chaya.api.search.SemanticSearchService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import dev.chaya.api.web.ApiException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Ranking of manual POIs and detected objects together, on REAL CLIP ViT-B/32 geometry: every vector here comes from the
 * production encoders (packages/contracts/fixtures/search/clip-vit-b32-openai.json), text-tower vectors for queries and
 * POI texts and image-tower vectors of real crops for detected objects. So the scale gap this class guards against is the
 * real one: a text query sits at cosine ~0.8-1.0 to POI texts but ~0.2-0.3 to even a matching crop
 * (docs/ADVERSARIAL_REVIEW.md CV-4). The relevance margin is the production value, which was calibrated for CLIP.
 *
 * <p>The venue: 9 manual POIs (the B3 conference centre's texts, plus a "Lounge" with sofas) and 4 detected objects,
 * two of them chairs that look different (chair-1 a brown armchair, chair-3 a big red chair). The queries and expected
 * answers were checked against this fixture's geometry; B3 (docs/BENCHMARKS.md) measures accuracy on a larger set.
 */
@TestPropertySource(properties = "chaya.search.relevance-min-margin=0.078")
class SemanticSearchRankingTest extends AbstractIntegrationTest {

    private static final String[][] MANUAL = {
        {"Reception desk", "service", "front desk", "check-in"}, {"Cafe", "food", "coffee", "snacks"},
        {"Cloakroom", "service", "coat check"}, {"Accessible restroom", "restroom", "toilet", "wc"},
        {"Elevator A", "elevator", "lift"}, {"Main staircase", "stairs"}, {"Fire extinguisher", "safety"},
        {"Auditorium", "venue", "main hall"}, {"Lounge", "seating area", "sofas", "armchairs"}};

    /** crop id -> detector label */
    private static final String[][] DETECTED = {{"chair-1", "chair"}, {"chair-3", "chair"}, {"couch-3", "couch"}, {"plant-1", "plant"}};

    @Autowired private SemanticSearchService search;
    @Autowired private dev.chaya.api.poi.PoiService pois;

    private String model;
    private Fixtures.Tree t;
    private UUID run;
    private final java.util.Map<String, UUID> ids = new java.util.HashMap<>();

    @BeforeEach
    void realClipVenue() {
        TestEmbeddingConfig.REAL_CLIP.set(true);
        model = TestEmbeddingConfig.clipFixture().get("text_model").asText();
        t = fx.tree();
        run = publishedRun(t);
        for (int i = 0; i < MANUAL.length; i++) {
            String[] m = MANUAL[i];
            List<String> tags = List.of(m).subList(2, m.length);
            ids.put(m[0], manual(t, m[0], m[1], tags, i, 0, model));
        }
        for (int i = 0; i < DETECTED.length; i++) {
            ids.put(DETECTED[i][0], detected(t, run, DETECTED[i][0], DETECTED[i][1], 10 + i, 1.5, 2 * i, model, model));
        }
    }

    @AfterEach
    void fakeEmbeddingsAgain() {
        TestEmbeddingConfig.REAL_CLIP.set(false);
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    private UUID pipelineRun(Fixtures.Tree tree) {
        return jdbc.sql("""
                INSERT INTO pipeline_run (organization_id, venue_id, scan_id, capture_session_id, status, quality, stages,
                    time_budget_seconds, deadline_at, finished_at, requested_by)
                VALUES (:o, :v, :s, :cs, 'SUCCEEDED', 'FINAL', '{SEMANTIC_INDEXING}', 3600, now(), now(), 'fixture') RETURNING id""")
            .param("o", tree.org()).param("v", tree.venue()).param("s", tree.scan()).param("cs", tree.session())
            .query(UUID.class).single();
    }

    /** A run of the tree's floor, published as the floor's current scan version (V28), so the POIs placed against it are
     * what a search of the floor returns. Its frame is a fixture identity frame; the POIs below carry their own frames. */
    private UUID publishedRun(Fixtures.Tree tree) {
        UUID r = pipelineRun(tree);
        UUID frame = fx.identityFrame(tree.org(), tree.venue(), tree.floor(), r, "FLOOR_LOCAL");
        fx.publish(tree.org(), tree.venue(), tree.floor(), r, tree.scan(), frame);
        return r;
    }

    /** A new calibration of the published reconstruction, made the floor's current frame (POIs are not re-projected). */
    private UUID recalibrated() {
        return fx.identityFrame(t.org(), t.venue(), t.floor(), run, "FLOOR_LOCAL");
    }

    private UUID poi(Fixtures.Tree tree) {
        return jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", tree.org()).param("v", tree.venue()).param("f", tree.floor()).query(UUID.class).single();
    }

    /** A manual POI with the text embedding the backfill would give it (PoiEmbeddingService.embeddingText, CLIP text tower). */
    private UUID manual(Fixtures.Tree tree, String label, String category, List<String> tags, double x, double z, String textModel) {
        return manual(tree, label, category, tags, x, z, textModel, null);
    }

    /** As above, placed in coordinate frame `frame` (null: none, as on an uncalibrated floor). */
    private UUID manual(Fixtures.Tree tree, String label, String category, List<String> tags, double x, double z, String textModel,
                        UUID frame) {
        UUID id = poi(tree);
        String text = PoiEmbeddingService.embeddingText(label, category, tags);
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, category, tags, x, y, z,
                                         embedding, embedding_model, source, created_by, coordinate_frame_id, scan_version_id)
                VALUES (:o, :v, :p, 1, :label, :cat, CAST(:tags AS text[]), :x, 0, :z, CAST(:e AS vector), :m, 'MANUAL', 'test',
                        :frame, (SELECT current_scan_version_id FROM floor WHERE id = :f))""")
            .param("f", tree.floor())
            .param("o", tree.org()).param("v", tree.venue()).param("p", id).param("label", label).param("cat", category)
            .param("tags", "{" + String.join(",", tags.stream().map(s -> "\"" + s + "\"").toList()) + "}")
            .param("x", x).param("z", z).param("e", TestEmbeddingConfig.vectorLiteral(TestEmbeddingConfig.clipText(text)))
            .param("m", textModel).param("frame", frame).update();
        return id;
    }

    /**
     * A detected object as ingestion stores it (image vector of a real crop in image_embedding, box with its source
     * frame), plus the text embedding of its detector label that the backfill then fills in.
     */
    private UUID detected(Fixtures.Tree tree, UUID pipelineRun, String crop, String label, double x, double y, double z,
                          String textModel, String imageModel) {
        return detected(tree, pipelineRun, crop, label, x, y, z, textModel, imageModel, null, 0.83, null, null);
    }

    /** As above, in coordinate frame `frame`, with its detector confidence and its localization (null: placed before
     * depth-tested localization existed, as every detection was before V27). */
    private UUID detected(Fixtures.Tree tree, UUID pipelineRun, String crop, String label, double x, double y, double z,
                          String textModel, String imageModel, UUID frame, double confidence, String localizationStatus,
                          Double uncertaintyM) {
        UUID id = poi(tree);
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z,
                                         embedding, embedding_model, image_embedding, image_embedding_model, source,
                                         detection_confidence, bounding_box, pipeline_run_id, created_by, coordinate_frame_id,
                                         localization_status, localization_uncertainty_m, scan_version_id)
                VALUES (:o, :v, :p, 1, :label, '{}', :x, :y, :z, CAST(:e AS vector), :tm, CAST(:img AS vector), :im,
                        'AUTO_DETECTED', :conf, CAST(:bbox AS jsonb), :run, 'system:semantic-indexing', :frame, :loc, :unc,
                        (SELECT current_scan_version_id FROM floor WHERE id = :f))""")
            .param("f", tree.floor())
            .param("o", tree.org()).param("v", tree.venue()).param("p", id).param("label", label)
            .param("x", x).param("y", y).param("z", z)
            .param("e", TestEmbeddingConfig.vectorLiteral(TestEmbeddingConfig.clipText(label))).param("tm", textModel)
            .param("img", TestEmbeddingConfig.vectorLiteral(crop(crop))).param("im", imageModel)
            .param("bbox", "{\"x\": 10, \"y\": 20, \"width\": 300, \"height\": 280, \"frameWidth\": 640, \"frameHeight\": 480, "
                + "\"sourceFrame\": \"" + crop + ".jpg\"}")
            .param("run", pipelineRun).param("conf", confidence).param("frame", frame).param("loc", localizationStatus)
            .param("unc", uncertaintyM).update();
        return id;
    }

    private static float[] crop(String id) {
        return TestEmbeddingConfig.vector(TestEmbeddingConfig.clipFixture().get("crops").get(id).get("embedding"));
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += (double) a[i] * b[i];
        }
        return s;
    }

    private Actor actor(Fixtures.Tree tree) {
        return new Actor(Actor.Kind.USER, "test-user", tree.org(), Set.of(tree.venue()), Set.of(Role.ADMIN));
    }

    private SearchResponse query(String q) {
        return search.search(actor(t), t.venue(), q, null, null, null);
    }

    private UUID id(String name) {
        return ids.get(name);
    }

    private static List<UUID> poiIds(List<SearchResult> results) {
        return results.stream().map(SearchResult::poiId).toList();
    }

    // ---- public links and privacy (review S-6) ------------------------------------------------------------------

    @Test
    void aPublicLinkNeverSeesWhatWasDetectedInFramesThatWereNotAnonymised() {
        Actor link = new Actor(Actor.Kind.PUBLIC_VIEWER, "public-link:" + UUID.randomUUID(), t.org(), Set.of(t.venue()),
            Set.of(Role.PUBLIC_VIEWER));
        // A run an administrator started with privacy preprocessing disabled detected a chair on this floor.
        UUID session = fx.captureSession(t.org(), t.venue());
        UUID unblurred = jdbc.sql("""
                INSERT INTO pipeline_run (organization_id, venue_id, scan_id, capture_session_id, status, quality, stages,
                    time_budget_seconds, deadline_at, finished_at, requested_by, privacy_enabled)
                VALUES (:o, :v, :s, :cs, 'SUCCEEDED', 'FINAL', '{SEMANTIC_INDEXING}', 3600, now(), now(), 'fixture', false) RETURNING id""")
            .param("o", t.org()).param("v", t.venue()).param("s", fx.scan(t.org(), t.venue(), session))
            .param("cs", session).query(UUID.class).single();
        UUID secret = detected(t, unblurred, "chair-3", "chair", 30, 1.5, 0, model, model);

        // Staff see every detection of the published version.
        assertThat(poiIds(search.search(actor(t), t.venue(), "chair", null, null, null).results())).contains(secret, id("chair-1"));
        assertThat(pois.list(actor(t), t.venue())).extracting(dev.chaya.api.poi.PoiService.Poi::id).contains(secret);
        assertThat(pois.get(actor(t), t.venue(), secret).id()).isEqualTo(secret);

        // The public link sees the privacy-preprocessed ones only, through every read path, by id too.
        SearchResponse r = search.search(link, t.venue(), "chair", null, null, null);
        assertThat(poiIds(r.results())).contains(id("chair-1")).doesNotContain(secret);
        assertThat(r.closestMatches() == null ? List.<UUID>of() : poiIds(r.closestMatches())).doesNotContain(secret);
        assertThat(pois.list(link, t.venue())).extracting(dev.chaya.api.poi.PoiService.Poi::id).contains(id("chair-1")).doesNotContain(secret);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pois.get(link, t.venue(), secret))
            .isInstanceOf(dev.chaya.api.web.NotFoundException.class);
        assertThat(pois.get(link, t.venue(), id("chair-1")).id()).isEqualTo(id("chair-1"));
    }

    // ---- the query types ----------------------------------------------------------------------------------------

    @Test
    void anExactQueryReturnsOnlyTheMatchingManualPoi() {
        SearchResponse r = query("cafe");

        assertThat(r.relevance()).isEqualTo("FILTERED");
        assertThat(poiIds(r.results())).containsExactly(id("Cafe"));
        assertThat(r.results().get(0).matchedBy()).isEqualTo("TEXT");
    }

    @Test
    void aSynonymOfADetectorLabelFindsTheDetectedObjectFirst() {
        // "settee" shares no word with "couch": only CLIP's text geometry relates them.
        SearchResponse r = query("settee");

        assertThat(r.results()).isNotEmpty();
        assertThat(r.results().get(0).poiId()).isEqualTo(id("couch-3"));
        assertThat(r.results().get(0).source()).isEqualTo("AUTO_DETECTED");
    }

    @Test
    void aVisualQueryOrdersSameLabelDetectionsByWhatTheirCropsShow() {
        SearchResponse r = query("red chair");

        // Both chairs have the same label, so the same text similarity; the text-to-image similarity of the crops decides.
        assertThat(poiIds(r.results())).containsExactly(id("chair-3"), id("chair-1"));
        SearchResult red = r.results().get(0);
        SearchResult brown = r.results().get(1);
        assertThat(red.similarity()).isEqualTo(brown.similarity());
        assertThat(red.imageSimilarity()).isGreaterThan(brown.imageSimilarity());

        assertThat(query("brown armchair").results().get(0).poiId()).as("and the other way round").isEqualTo(id("chair-1"));
    }

    @Test
    void anAmbiguousQueryReturnsEveryObjectItCouldMean() {
        SearchResponse r = query("chair");

        assertThat(poiIds(r.results())).containsExactlyInAnyOrder(id("chair-1"), id("chair-3"));
        assertThat(r.results()).allSatisfy(x -> assertThat(x.similarity()).isCloseTo(1.0, within(1e-4)));
    }

    @Test
    void aQueryForSomethingTheVenueDoesNotHaveReturnsNothingNotEvenADetectedObject() {
        for (String q : List.of("swimming pool", "car rental")) {
            SearchResponse r = query(q);
            assertThat(r.relevance()).isEqualTo("FILTERED");
            assertThat(r.results()).as(q).isEmpty();
            assertThat(r.closestMatches()).as(q).hasSize(3);
        }
        Integer logged = jdbc.sql("SELECT result_count FROM search_query WHERE venue_id = :v ORDER BY created_at DESC LIMIT 1")
            .param("v", t.venue()).query(Integer.class).single();
        assertThat(logged).isZero();
    }

    @Test
    void aMixedQueryReturnsManualAndDetectedPoisInOneRanking() {
        SearchResponse r = query("sofa");

        assertThat(r.results()).extracting(SearchResult::source).contains("MANUAL", "AUTO_DETECTED");
        assertThat(poiIds(r.results())).contains(id("Lounge"), id("couch-3"));
        assertThat(r.results().get(0).poiId()).isEqualTo(id("couch-3"));
        // One ordering, by one comparable score.
        List<Double> sims = r.results().stream().map(SearchResult::similarity).toList();
        assertThat(sims).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    // ---- lexical / fuzzy and synonyms -------------------------------------------------------------------------------

    @Test
    void aMisspeltNameIsFoundByItsWholeWordTrigramsWhereClipAloneDoesNotRankIt() {
        // Real CLIP puts "recepton" closer to "plant" (0.873) than to the reception desk's text (0.869): by similarity alone
        // the desk would be fourth and below the relevance margin. The whole-word trigram match admits it and ranks it.
        SearchResponse r = query("recepton");
        assertThat(r.results()).isNotEmpty();
        SearchResult desk = r.results().get(0);
        assertThat(desk.poiId()).isEqualTo(id("Reception desk"));
        assertThat(desk.matchedBy()).isEqualTo("LEXICAL");
        assertThat(desk.lexicalSimilarity()).isGreaterThanOrEqualTo(0.5);

        for (String[] typo : new String[][]{{"fire extingusher", "Fire extinguisher"}, {"elevater", "Elevator A"}}) {
            SearchResponse t2 = query(typo[0]);
            assertThat(t2.results()).as(typo[0]).isNotEmpty();
            assertThat(t2.results().get(0).poiId()).as(typo[0]).isEqualTo(id(typo[1]));
        }
    }

    @Test
    void aWordInsideAnotherWordIsNotAFuzzyMatch() {
        // pg_trgm's plain word_similarity scores "sign" in "design" 0.8; the strict, whole-word form 0.33.
        UUID studio = manual(t, "Design studio", null, List.of("studio"), 20, 0, model);
        SearchResponse r = query("sign");
        assertThat(poiIds(r.results())).doesNotContain(studio);
        SearchResult asCandidate = java.util.stream.Stream.concat(r.results().stream(), r.closestMatches().stream())
            .filter(x -> x.poiId().equals(studio)).findFirst().orElse(null);
        if (asCandidate != null) {
            assertThat(asCandidate.lexicalSimilarity()).isLessThan(0.5);
        }
    }

    @Test
    void synonymsAreFoundThroughClipNotThroughASynonymTable() {
        // "sofa" and "settee" share no trigram with "couch"; only CLIP's text geometry relates them.
        for (String q : List.of("sofa", "settee", "couch")) {
            SearchResponse r = query(q);
            assertThat(r.results().get(0).poiId()).as(q).isEqualTo(id("couch-3"));
        }
        assertThat(poiIds(query("sofa").results())).as("and the lounge, whose tags say sofas").contains(id("Lounge"));
        assertThat(query("settee").results().get(0).lexicalSimilarity()).isZero();
    }

    // ---- spatial validity (review CV-1) ------------------------------------------------------------------------------

    @Test
    void aPositionThatCannotBeTrustedNeverOutranksATrustedOneOnTextSimilarity() {
        UUID frame = recalibrated();
        // A detected couch placed before depth-tested localization (CV-1: it may sit on the wall behind the couch), in the
        // floor's frame, and a staff-placed lounge in the same frame.
        UUID unverified = detected(t, run, "couch-3", "couch", 30, 0, 0.4, model, model, frame, 0.95, null, null);
        UUID lounge = manual(t, "Lounge", "seating area", List.of("sofas", "armchairs"), 31, 0, model, frame);

        SearchResponse r = query("couch");
        SearchResult couch = r.results().stream().filter(x -> x.poiId().equals(unverified)).findFirst().orElseThrow();
        SearchResult valid = r.results().stream().filter(x -> x.poiId().equals(lounge)).findFirst().orElseThrow();
        assertThat(couch.similarity()).as("the text match alone favours the couch").isGreaterThan(valid.similarity() + 0.1);
        assertThat(couch.spatialStatus()).isEqualTo("UNVERIFIED");
        assertThat(valid.spatialStatus()).isEqualTo("VALID");
        assertThat(r.results().get(0).poiId()).as("but its position is unverified").isEqualTo(lounge);
        // Every VALID result precedes every other relevant one; the fixture's own POIs have no frame at all.
        List<String> statuses = r.results().stream().map(SearchResult::spatialStatus).toList();
        assertThat(statuses.indexOf("VALID")).isZero();
        assertThat(statuses.lastIndexOf("VALID")).isLessThan(statuses.indexOf("UNVERIFIED"));
        assertThat(r.results()).filteredOn(x -> x.poiId().equals(id("couch-3"))).extracting(SearchResult::spatialStatus)
            .containsExactly("UNBOUND");
    }

    @Test
    void aDetectionPlacedWithDepthEvidenceRanksOnItsTextAndKeepsItsUncertainty() {
        UUID frame = recalibrated();
        UUID placed = detected(t, run, "couch-3", "couch", 30, 0, 0.4, model, model, frame, 0.95, "MULTI_VIEW", 0.12);
        UUID lounge = manual(t, "Lounge", "seating area", List.of("sofas", "armchairs"), 31, 0, model, frame);

        SearchResponse r = query("couch");
        assertThat(poiIds(r.results()).subList(0, 2)).containsExactly(placed, lounge);
        SearchResult couch = r.results().get(0);
        assertThat(couch.spatialStatus()).isEqualTo("VALID");
        assertThat(couch.localizationStatus()).isEqualTo("MULTI_VIEW");
        assertThat(couch.localizationUncertaintyM()).isEqualTo(0.12);
        assertThat(couch.detectionConfidence()).isEqualTo(0.95);
        assertThat(couch.rankScore()).isGreaterThan(r.results().get(1).rankScore());
    }

    @Test
    void amongValidDetectionsOfTheSameThingConfidenceAndViewSupportDecide() {
        UUID frame = recalibrated();
        UUID weak = detected(t, run, "chair-3", "chair", 40, 0, 0.4, model, model, frame, 0.55, "SINGLE_VIEW", 0.3);
        UUID strong = detected(t, run, "chair-1", "chair", 41, 0, 0.4, model, model, frame, 0.9, "MULTI_VIEW", 0.1);

        SearchResponse r = query("chair");
        assertThat(poiIds(r.results()).subList(0, 2)).containsExactly(strong, weak);
        assertThat(r.results().get(0).similarity()).isEqualTo(r.results().get(1).similarity());
    }

    @Test
    void aPositionInAnOlderFrameIsStale() {
        UUID old = recalibrated();
        UUID cafe2 = manual(t, "Cafe", "food", List.of("coffee", "snacks"), 50, 0, model, old);
        UUID current = recalibrated();  // recalibrated: a new frame, and cafe2 was never re-placed in it
        UUID cafe3 = manual(t, "Cafe", "food", List.of("coffee", "snacks"), 51, 0, model, current);

        SearchResponse r = query("coffee");
        assertThat(r.results()).filteredOn(x -> x.poiId().equals(cafe2)).extracting(SearchResult::spatialStatus)
            .containsExactly("STALE_FRAME");
        assertThat(r.results().get(0).poiId()).isEqualTo(cafe3);
    }

    // ---- embedding spaces ------------------------------------------------------------------------------------------

    @Test
    void detectedObjectsAreScoredInTextSpaceAndTheirCropOnlyInImageSpace() {
        // CV-4: with the crop's image vector in the ranking, "couch" put the detected couch (cosine ~0.25) below every manual
        // POI (~0.75-0.86). Now its similarity is text-to-text, like everyone's, and it ranks first.
        SearchResponse r = query("couch");
        SearchResult couch = r.results().get(0);
        float[] q = TestEmbeddingConfig.clipText("couch");

        assertThat(couch.poiId()).isEqualTo(id("couch-3"));
        assertThat(couch.similarity()).isCloseTo(dot(q, TestEmbeddingConfig.clipText("couch")), within(1e-4));
        assertThat(couch.imageSimilarity()).isCloseTo(dot(q, crop("couch-3")), within(1e-4));
        assertThat(couch.imageSimilarity()).as("the real text-to-image scale").isBetween(0.15, 0.4);
        assertThat(r.results()).filteredOn(x -> x.source().equals("MANUAL"))
            .allSatisfy(m -> assertThat(m.imageSimilarity()).isNull());
    }

    @Test
    void embeddingsFromAnotherModelAreNeverCompared() {
        // CV-5: a vector from any other model lives in another space. Same vectors, different recorded model: left out.
        UUID stale = manual(t, "Cafe", "food", List.of("coffee", "snacks"), 30, 0, "open_clip:ViT-L-14:openai");
        UUID foreignImage = detected(t, run, "couch-3", "couch", 31, 0, 0, model, "open_clip:ViT-L-14:openai");

        SearchResponse cafe = query("cafe");
        assertThat(poiIds(cafe.results())).doesNotContain(stale);
        assertThat(poiIds(cafe.closestMatches())).doesNotContain(stale);

        SearchResult other = query("couch").results().stream().filter(x -> x.poiId().equals(foreignImage)).findFirst().orElseThrow();
        assertThat(other.imageSimilarity()).as("its text embedding is comparable, its image embedding is not").isNull();
    }

    @Test
    void anImageEmbeddingOnlyExistsOnDetectedObjects() {
        UUID id = poi(t);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z,
                                         image_embedding, image_embedding_model, source, created_by)
                VALUES (:o, :v, :p, 1, 'Sofa', '{}', 0, 0, 0, CAST(:img AS vector), :m, 'MANUAL', 'test')""")
            .param("o", t.org()).param("v", t.venue()).param("p", id)
            .param("img", TestEmbeddingConfig.vectorLiteral(crop("couch-3"))).param("m", model).update())
            .hasMessageContaining("poi_version_image_embedding_detected_only");
    }

    // ---- spatial metadata and isolation -----------------------------------------------------------------------------

    @Test
    void aDetectedResultKeepsItsFloorPositionBoxSourceFrameAndConfidence() {
        SearchResult couch = query("couch").results().get(0);

        assertThat(couch.floorId()).isEqualTo(t.floor());
        assertThat(List.of(couch.x(), couch.y(), couch.z())).containsExactly(12.0, 1.5, 4.0);
        assertThat(couch.detectionConfidence()).isEqualTo(0.83);
        assertThat(couch.boundingBox()).containsEntry("width", 300).containsEntry("frameWidth", 640);
        assertThat(couch.sourceFrame()).isEqualTo("couch-3.jpg");
        assertThat(couch.boundingBox()).containsEntry("sourceFrame", "couch-3.jpg");
    }

    @Test
    void aSearchNeverReturnsADetectionOfAnUnpublishedOrReplacedVersion() {
        // The same object detected by a run whose version was never published (it failed, or is still processing).
        UUID draftCouch = detectedInVersion(t.version(), "couch-3", "couch", 60);
        assertThat(poiIds(query("couch").results())).contains(id("couch-3")).doesNotContain(draftCouch);
        // Searching that version itself is refused: it is not a finalized version.
        assertThatThrownBy(() -> search.search(actor(t), t.venue(), "couch", null, null, null, t.version()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VERSION_NOT_FINALIZED"));
    }

    private UUID detectedInVersion(UUID version, String crop, String label, double x) {
        UUID id = detected(t, run, crop, label, x, 0, 0, model, model);
        // detected() places it against the published version; re-point the one poi_version is not possible (immutable),
        // so a fresh POI is written against `version` instead.
        UUID other = poi(t);
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z, embedding,
                                         embedding_model, source, pipeline_run_id, scan_version_id, created_by)
                SELECT organization_id, venue_id, :p, 1, label, tags, x, y, z, embedding, embedding_model, source, pipeline_run_id,
                       :sv, created_by
                  FROM poi_version WHERE poi_id = :src""")
            .param("p", other).param("sv", version).param("src", id).update();
        jdbc.sql("UPDATE poi SET deleted_at = now() WHERE id = :p").param("p", id).update();
        return other;
    }

    @Test
    void aSearchNeverSeesAnotherVenuesDetectedObjects() {
        Fixtures.Tree other = fx.tree();
        UUID otherCouch = detected(other, publishedRun(other), "couch-3", "couch", 0, 0, 0, model, model);

        SearchResponse here = query("couch");
        assertThat(poiIds(here.results())).doesNotContain(otherCouch);
        assertThat(poiIds(here.results())).contains(id("couch-3"));

        SearchResponse there = search.search(actor(other), other.venue(), "couch", null, null, null);
        assertThat(poiIds(there.results())).containsExactly(otherCouch);
    }

    @Test
    void theFixtureIsRealClip() {
        JsonNode f = TestEmbeddingConfig.clipFixture();
        assertThat(f.get("text_model").asText()).isEqualTo("open_clip:ViT-B-32:openai");
        assertThat(f.get("image_model").asText()).isEqualTo(f.get("text_model").asText());
        assertThat(crop("couch-3")).hasSize(512);
    }
}
