package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.chaya.api.search.PoiEmbeddingService;
import dev.chaya.api.search.SearchDtos.SearchResponse;
import dev.chaya.api.search.SearchDtos.SearchResult;
import dev.chaya.api.search.SemanticSearchService;
import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * B3 (docs/BENCHMARKS.md, benchmarks/b3_semantic_search) run in process: the real SemanticSearchService and its SQL on a
 * Testcontainers Postgres with pgvector, every vector a REAL CLIP ViT-B/32 vector computed by the production encoders
 * (packages/contracts/fixtures/search/b3-clip-vit-b32-openai.json for texts, benchmarks/b3_semantic_search/
 * detected_vectors.json for the crops). No vision service, no HTTP, no rate limit; otherwise the same data and the same
 * scoring as benchmarks/b3_semantic_search/run.py (top-1 / top-5 per query type over the first 10 results, correct
 * rejection = an empty result list).
 *
 * <p>Conditions: {@code manual} (dataset.json's 30 POIs, 59 queries), {@code phrasing} (the same venue and expected answers,
 * queries derived mechanically by phrasing.json: sentence templates and one deleted character), {@code mixed} (plus
 * detected_objects.json: 2 more manual POIs, 15 detected objects, 22 more queries). Results are written to
 * target/search-benchmark/b3-inprocess.json and printed. The data is small and author-built: a regression check, not a
 * measure of real-venue quality.
 *
 * <p>The assertions are floors, not targets: each is the value this harness measured with the current code, so a change
 * that loses retrieval quality on B3 fails here. The relevance margin is production's (the tests' default is for random
 * vectors), as in SemanticSearchRankingTest.
 */
@TestPropertySource(properties = "chaya.search.relevance-min-margin=0.078")
class SearchBenchmarkTest extends AbstractIntegrationTest {

    private static final Path B3 = Path.of("../../benchmarks/b3_semantic_search");
    private static final Path TEXTS = Path.of("../../packages/contracts/fixtures/search/b3-clip-vit-b32-openai.json");
    private static final Path OUT = Path.of("target/search-benchmark/b3-inprocess.json");
    private static final int TOPK = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private SemanticSearchService search;

    private JsonNode dataset;
    private JsonNode detected;
    private JsonNode phrasing;
    private JsonNode crops;
    private String model;

    @BeforeEach
    void realClip() throws IOException {
        JsonNode texts = JSON.readTree(TEXTS.toFile());
        TestEmbeddingConfig.TEXT_FIXTURE.set(texts);
        TestEmbeddingConfig.MISSING.clear();
        model = texts.get("text_model").asText();
        dataset = JSON.readTree(B3.resolve("dataset.json").toFile());
        detected = JSON.readTree(B3.resolve("detected_objects.json").toFile());
        phrasing = JSON.readTree(B3.resolve("phrasing.json").toFile());
        crops = JSON.readTree(B3.resolve("detected_vectors.json").toFile());
    }

    @AfterEach
    void fakeEmbeddingsAgain() {
        TestEmbeddingConfig.TEXT_FIXTURE.set(null);
    }

    record Query(String q, String type, List<String> expected, String source) {}

    /** One venue: its tree, and how a result is identified in `expected` (a manual POI's label, a detection's crop id). */
    private record Venue(Fixtures.Tree tree, Actor actor, Map<UUID, String> ident, UUID runId) {}

    @Test
    @SuppressWarnings("unchecked")
    void b3InProcess() throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("provenance", "SearchBenchmarkTest: B3 through the real SemanticSearchService, real CLIP vectors, "
            + "Testcontainers Postgres. Author-built data; regression check only.");

        Venue manual = venue(false);
        List<Query> b3 = queries(dataset.get("queries"), "b3");
        // Warm-up (JIT, connection pool, plan cache): one pass over the manual queries, not timed.
        condition = "warm-up";
        run(manual, b3);
        latencies.clear();
        condition = "manual";
        Map<String, Object> manualScores = score(run(manual, b3), b3);
        report.put("manual", manualScores);

        List<Query> phrased = phrasedQueries();
        condition = "phrasing";
        Map<String, List<String>> phrasedRanked = run(manual, phrased);
        Map<String, Object> phrasedScores = new LinkedHashMap<>();
        for (String source : phrased.stream().map(Query::source).distinct().toList()) {
            List<Query> subset = phrased.stream().filter(q -> q.source().equals(source)).toList();
            phrasedScores.put(source, score(phrasedRanked, subset));
        }
        report.put("phrasing", phrasedScores);

        Venue mixed = venue(true);
        List<Query> all = new ArrayList<>(b3);
        all.addAll(queries(detected.get("queries"), "detected"));
        condition = "mixed";
        Map<String, List<String>> mixedRanked = run(mixed, all);
        report.put("mixed", score(mixedRanked, all));
        report.put("by_category", scoreByCategory(mixedRanked, all));
        report.put("adversarial_suite", evaluateAdversarialSuite(mixed, mixedRanked, all));
        Map<String, Object> top5 = new LinkedHashMap<>();
        all.forEach(q -> top5.put(q.q(), mixedRanked.get(q.q()).subList(0, Math.min(5, mixedRanked.get(q.q()).size()))));
        report.put("mixed_top5", top5);
        Map<String, Object> phrasedTop5 = new LinkedHashMap<>();
        phrased.forEach(q -> phrasedTop5.put(q.q(), phrasedRanked.get(q.q()).subList(0, Math.min(5, phrasedRanked.get(q.q()).size()))));
        report.put("phrasing_top5", phrasedTop5);
        Map<String, Object> latency = new LinkedHashMap<>();
        latencies.forEach((c, ms) -> {
            List<Double> sorted = ms.stream().sorted().toList();
            latency.put(c, Map.of("n", sorted.size(), "p50_ms", round(percentile(sorted, 50)), "p95_ms", round(percentile(sorted, 95)),
                "max_ms", round(sorted.get(sorted.size() - 1))));
        });
        report.put("latency_in_process", latency);

        assertThat(TestEmbeddingConfig.MISSING).as("texts without a real CLIP vector: add them with "
            + "benchmarks/b3_semantic_search/inprocess_texts.py").isEmpty();

        Files.createDirectories(OUT.getParent());
        JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(OUT.toFile(), report);
        System.out.println("B3 in-process: " + JSON.writeValueAsString(report.entrySet().stream()
            .filter(e -> !e.getKey().endsWith("top5")).collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll)));

        // Floors: what this harness measured on 2026-10-14 with query understanding in place (docs/BENCHMARKS.md, B3
        // in-process). The deterministic data and vectors make any drop a real change in behaviour.
        floor(manualScores, "all_answerable", "top1", 0.7955);
        floor(manualScores, "all_answerable", "top5", 0.8182);
        floor(manualScores, "zero_result", "correct_rejection", 0.8667);
        for (String template : List.of("template: where is the {q}?", "template: find the nearest {q}",
                "template: show me the closest {q}")) {
            floor(phrasedScores.get(template), "all_answerable", "top1", 0.963);
            floor(phrasedScores.get(template), "zero_result", "correct_rejection", 0.8667);
        }
        floor(phrasedScores.get("misspelled"), "all_answerable", "top1", 0.95);
        floor((Map<String, Object>) report.get("mixed"), "all_answerable", "top1", 0.803);
        floor((Map<String, Object>) report.get("mixed"), "all_answerable", "top5", 0.8788);
    }

    @SuppressWarnings("unchecked")
    private static void floor(Object scores, String type, String metric, double atLeast) {
        Object value = ((Map<String, Object>) ((Map<String, Object>) scores).get(type)).get(metric);
        assertThat((Double) value).as(type + " " + metric).isGreaterThanOrEqualTo(atLeast);
    }

    // ---- data ------------------------------------------------------------------------------------------------------

    private static List<Query> queries(JsonNode array, String source) {
        List<Query> out = new ArrayList<>();
        for (JsonNode q : array) {
            List<String> expected = new ArrayList<>();
            q.get("expected").forEach(e -> expected.add(e.asText()));
            out.add(new Query(q.get("q").asText(), q.get("type").asText(), expected, source));
        }
        return out;
    }

    /** phrasing.json, exactly as benchmarks/b3_semantic_search/inprocess_texts.py derives it. */
    private List<Query> phrasedQueries() {
        Set<String> templateTypes = textSet(phrasing.get("template_types"));
        Set<String> misspellTypes = textSet(phrasing.get("misspelling").get("types"));
        List<Query> out = new ArrayList<>();
        for (Query q : queries(dataset.get("queries"), "b3")) {
            if (templateTypes.contains(q.type())) {
                for (JsonNode t : phrasing.get("templates")) {
                    out.add(new Query(t.asText().replace("{q}", q.q()), q.type(), q.expected(), "template: " + t.asText()));
                }
            }
            if (misspellTypes.contains(q.type())) {
                String m = misspell(q.q());
                if (m != null) {
                    out.add(new Query(m, q.type(), q.expected(), "misspelled"));
                }
            }
        }
        return out;
    }

    static String misspell(String q) {
        String[] words = q.split(" ");
        int longest = 0;
        for (int i = 1; i < words.length; i++) {
            if (words[i].length() > words[longest].length()) {
                longest = i;
            }
        }
        String w = words[longest];
        if (w.length() < 5) {
            return null;
        }
        int i = w.length() / 2;
        words[longest] = w.substring(0, i) + w.substring(i + 1);
        return String.join(" ", words);
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> out = new java.util.HashSet<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private Venue venue(boolean withDetections) {
        Fixtures.Tree t = fx.tree();
        UUID run = jdbc.sql("""
                INSERT INTO pipeline_run (organization_id, venue_id, scan_id, capture_session_id, status, quality, stages,
                    time_budget_seconds, deadline_at, finished_at, requested_by)
                VALUES (:o, :v, :s, :cs, 'SUCCEEDED', 'FINAL', '{SEMANTIC_INDEXING}', 3600, now(), now(), 'bench-b3') RETURNING id""")
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("cs", t.session()).query(UUID.class).single();
        UUID frame = fx.identityFrame(t.org(), t.venue(), t.floor(), run, "FLOOR_LOCAL");
        fx.publish(t.org(), t.venue(), t.floor(), run, t.scan(), frame);
        Map<UUID, String> ident = new HashMap<>();
        List<JsonNode> pois = new ArrayList<>();
        dataset.get("pois").forEach(pois::add);
        if (withDetections) {
            detected.get("extra_manual_pois").forEach(pois::add);
        }
        for (JsonNode p : pois) {
            List<String> tags = new ArrayList<>();
            p.get("tags").forEach(x -> tags.add(x.asText()));
            String category = p.hasNonNull("category") ? p.get("category").asText() : null;
            String label = p.get("label").asText();
            UUID id = insert(t, label, category, tags, p.get("x").asDouble(), p.get("z").asDouble(),
                PoiEmbeddingService.embeddingText(label, category, tags), null, null, null);
            ident.put(id, label);
        }
        if (withDetections) {
            for (JsonNode d : detected.get("detected")) {
                String label = d.get("label").asText();
                UUID id = insert(t, label, null, List.of(), d.get("x").asDouble(), d.get("z").asDouble(), label,
                    run, d.get("id").asText(), crops.get("crops").get(d.get("id").asText()).get("embedding"));
                ident.put(id, d.get("id").asText());
            }
        }
        Actor actor = new Actor(Actor.Kind.USER, "bench-b3", t.org(), Set.of(t.venue()), Set.of(Role.ADMIN));
        return new Venue(t, actor, ident, run);
    }

    /** A manual POI (run == null) or a detection stored as ingestion stores one, with the text embedding the backfill
     * would give it. Detections carry no confidence (B3's boxes are COCO's human annotations, stored as NULL as the live
     * B3 does). They are stored as placed with depth evidence from one view (SINGLE_VIEW, spread 0), as every detection the
     * current worker publishes is; otherwise search would put them in its UNVERIFIED tier, below every manual POI. */
    private UUID insert(Fixtures.Tree t, String label, String category, List<String> tags, double x, double z, String text,
                        UUID run, String crop, JsonNode image) {
        UUID id = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", t.org()).param("v", t.venue()).param("f", t.floor()).query(UUID.class).single();
        JsonNode vector = TestEmbeddingConfig.TEXT_FIXTURE.get().get("texts").get(text.strip().toLowerCase(java.util.Locale.ROOT));
        if (vector == null) {
            TestEmbeddingConfig.MISSING.add(text.strip().toLowerCase(java.util.Locale.ROOT));
            throw new IllegalStateException("no real CLIP vector for POI text '" + text + "'");
        }
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, category, tags, x, y, z,
                    embedding, embedding_model, image_embedding, image_embedding_model, source, bounding_box, pipeline_run_id,
                    created_by, coordinate_frame_id, scan_version_id, localization_status, localization_uncertainty_m)
                VALUES (:o, :v, :p, 1, :label, :cat, CAST(:tags AS text[]), :x, 0, :z, CAST(:e AS vector), :m,
                    CAST(:img AS vector), :im, :src, CAST(:bbox AS jsonb), :run, 'bench-b3',
                    (SELECT current_coordinate_frame_id FROM floor WHERE id = :f),
                    (SELECT current_scan_version_id FROM floor WHERE id = :f), :loc, :unc)""")
            .param("o", t.org()).param("v", t.venue()).param("p", id).param("label", label).param("cat", category)
            .param("tags", "{" + String.join(",", tags.stream().map(s -> "\"" + s + "\"").toList()) + "}")
            .param("x", x).param("z", z).param("f", t.floor())
            .param("e", TestEmbeddingConfig.vectorLiteral(TestEmbeddingConfig.vector(vector))).param("m", model)
            .param("img", image == null ? null : TestEmbeddingConfig.vectorLiteral(TestEmbeddingConfig.vector(image)))
            .param("im", image == null ? null : crops.get("image_model").asText())
            .param("src", run == null ? "MANUAL" : "AUTO_DETECTED")
            .param("bbox", crop == null ? null : "{\"sourceFrame\": \"" + crop + ".jpg\"}").param("run", run)
            .param("loc", run == null ? null : "SINGLE_VIEW").param("unc", run == null ? null : 0.0)
            .update();
        return id;
    }

    // ---- running and scoring (run.py's score()) -------------------------------------------------------------------

    /** Wall-clock milliseconds of each search call, per condition (in process: no HTTP, no rate limit). */
    private final Map<String, List<Double>> latencies = new LinkedHashMap<>();
    private String condition = "unnamed";

    private Map<String, List<String>> run(Venue venue, List<Query> queries) {
        Map<String, List<String>> ranked = new LinkedHashMap<>();
        for (Query q : queries) {
            if (ranked.containsKey(q.q())) {
                continue;
            }
            List<String> ids = new ArrayList<>();
            try {
                long t0 = System.nanoTime();
                SearchResponse r = search.search(venue.actor(), venue.tree().venue(), q.q(), null, TOPK, null);
                latencies.computeIfAbsent(condition, c -> new ArrayList<>()).add((System.nanoTime() - t0) / 1e6);
                for (SearchResult result : r.results()) {
                    ids.add(venue.ident().getOrDefault(result.poiId(), "?"));
                }
            } catch (IllegalStateException e) {
                // a text without a vector: recorded in MISSING, reported by the assertion in b3InProcess
            }
            ranked.put(q.q(), ids);
        }
        return ranked;
    }

    private static Map<String, Object> score(Map<String, List<String>> ranked, List<Query> queries) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> types = queries.stream().map(Query::type).distinct().toList();
        List<Query> answerable = new ArrayList<>();
        for (String type : types) {
            List<Query> qs = queries.stream().filter(q -> q.type().equals(type)).toList();
            if (type.equals("zero_result")) {
                long empty = qs.stream().filter(q -> ranked.get(q.q()).isEmpty()).count();
                double cr = round((double) empty / qs.size());
                double fpr = round(1.0 - (double) empty / qs.size());
                List<Double> indicators = qs.stream().map(q -> ranked.get(q.q()).isEmpty() ? 1.0 : 0.0).toList();
                Map<String, Object> zr = new LinkedHashMap<>();
                zr.put("n", qs.size());
                zr.put("correct_rejection", cr);
                zr.put("false_positive_rate", fpr);
                zr.put("no_result_rate", cr);
                zr.put("correct_rejection_ci95", bootstrapCi95(indicators));
                out.put(type, zr);
                continue;
            }
            answerable.addAll(qs);
            out.put(type, metricsFor(ranked, qs));
        }
        if (!answerable.isEmpty()) {
            out.put("all_answerable", metricsFor(ranked, answerable));
        }
        return out;
    }

    private static Map<String, Object> metricsFor(Map<String, List<String>> ranked, List<Query> qs) {
        double t1 = top1(ranked, qs);
        double t5 = top5(ranked, qs);
        double mrr = mrr(ranked, qs);
        double map = mapAtK(ranked, qs, TOPK);
        double fprTop1 = falsePositiveTop1(ranked, qs);
        double noRes = noResultRate(ranked, qs);
        List<Double> t1Ind = qs.stream()
            .map(q -> !ranked.get(q.q()).isEmpty() && q.expected().contains(ranked.get(q.q()).get(0)) ? 1.0 : 0.0)
            .toList();
        List<Double> t5Ind = qs.stream()
            .map(q -> ranked.get(q.q()).stream().limit(5).anyMatch(q.expected()::contains) ? 1.0 : 0.0)
            .toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", qs.size());
        m.put("top1", round(t1));
        m.put("top5", round(t5));
        m.put("mrr", round(mrr));
        m.put("map_at_10", round(map));
        m.put("false_positive_rate_top1", round(fprTop1));
        m.put("no_result_rate", round(noRes));
        m.put("top1_ci95", bootstrapCi95(t1Ind));
        m.put("top5_ci95", bootstrapCi95(t5Ind));
        return m;
    }

    private Map<String, Object> scoreByCategory(Map<String, List<String>> ranked, List<Query> all) {
        Map<String, String> itemCategory = new HashMap<>();
        dataset.get("pois").forEach(p -> itemCategory.put(p.get("label").asText(), p.get("category").asText()));
        detected.get("extra_manual_pois").forEach(p -> itemCategory.put(p.get("label").asText(), p.get("category").asText()));
        detected.get("detected").forEach(d -> itemCategory.put(d.get("id").asText(), "detected:" + d.get("label").asText()));

        Map<String, List<Query>> byCat = new LinkedHashMap<>();
        for (Query q : all) {
            if (q.expected().isEmpty()) {
                continue;
            }
            Set<String> cats = new java.util.LinkedHashSet<>();
            for (String exp : q.expected()) {
                String c = itemCategory.getOrDefault(exp, "other");
                cats.add(c);
            }
            for (String c : cats) {
                byCat.computeIfAbsent(c, k -> new ArrayList<>()).add(q);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        byCat.forEach((cat, qs) -> out.put(cat, metricsFor(ranked, qs)));
        return out;
    }

    private Map<String, Object> evaluateAdversarialSuite(Venue mixed, Map<String, List<String>> mixedRanked, List<Query> all) {
        Map<String, Object> suite = new LinkedHashMap<>();

        // 1. Repeated objects (multiple instances of chairs, couches, plants, tables, benches, restrooms, elevators, rooms)
        List<Query> repeated = all.stream()
            .filter(q -> q.expected().size() >= 2 && (q.type().equals("detected_exact") || q.type().equals("ambiguous")))
            .toList();
        suite.put("repeated_objects", metricsFor(mixedRanked, repeated));

        // 2. Visually similar objects (same detector label disambiguated by crop appearance)
        List<Query> visual = all.stream().filter(q -> q.type().equals("visual_attribute")).toList();
        suite.put("visually_similar_objects", metricsFor(mixedRanked, visual));

        // 3. Occluded / cluttered objects (chair-1 with dog, couch-1 with person, couch-2 with teddy bear)
        List<Query> occluded = all.stream()
            .filter(q -> q.expected().contains("chair-1") || q.expected().contains("couch-1") || q.expected().contains("couch-2"))
            .toList();
        suite.put("occluded_objects", metricsFor(mixedRanked, occluded));

        // 4. Unseen labels (queries whose words do not appear in detector prompts or POI labels/tags)
        List<Query> unseen = all.stream()
            .filter(q -> q.type().equals("synonym") || q.type().equals("detected_synonym"))
            .toList();
        suite.put("unseen_labels", metricsFor(mixedRanked, unseen));

        // 5. Absent objects (queries for facilities/objects absent from the venue)
        List<Query> absent = all.stream().filter(q -> q.type().equals("zero_result")).toList();
        suite.put("absent_objects", score(mixedRanked, absent).get("zero_result"));

        // 6. Stale scan versions (detections on an unpublished draft scan version must have 0% leakage into search)
        UUID draftPoi = jdbc.sql("INSERT INTO poi (organization_id, venue_id, floor_id) VALUES (:o, :v, :f) RETURNING id")
            .param("o", mixed.tree().org()).param("v", mixed.tree().venue()).param("f", mixed.tree().floor())
            .query(UUID.class).single();
        JsonNode couchVec = TestEmbeddingConfig.TEXT_FIXTURE.get().get("texts").get("couch");
        jdbc.sql("""
                INSERT INTO poi_version (organization_id, venue_id, poi_id, version_number, label, tags, x, y, z,
                    embedding, embedding_model, source, detection_confidence, pipeline_run_id, created_by, scan_version_id)
                VALUES (:o, :v, :p, 1, 'couch', '{}', 99, 0, 99, CAST(:e AS vector), :m, 'AUTO_DETECTED', 0.99, :run,
                    'adversarial-stale', :draftSv)""")
            .param("o", mixed.tree().org()).param("v", mixed.tree().venue()).param("p", draftPoi)
            .param("e", TestEmbeddingConfig.vectorLiteral(TestEmbeddingConfig.vector(couchVec))).param("m", model)
            .param("run", mixed.runId()).param("draftSv", mixed.tree().version()).update();
        List<String> staleProbeQueries = List.of("couch", "sofa", "settee", "chair", "reception desk");
        int leaked = 0;
        for (String pq : staleProbeQueries) {
            SearchResponse sr = search.search(mixed.actor(), mixed.tree().venue(), pq, null, TOPK, null);
            boolean hasDraft = sr.results().stream().anyMatch(r -> r.poiId().equals(draftPoi))
                || (sr.closestMatches() != null && sr.closestMatches().stream().anyMatch(r -> r.poiId().equals(draftPoi)));
            if (hasDraft) {
                leaked++;
            }
        }
        suite.put("stale_scan_versions", Map.of(
            "n", staleProbeQueries.size(),
            "stale_leakage_rate", round((double) leaked / staleProbeQueries.size()),
            "isolation_pass_rate", round(1.0 - (double) leaked / staleProbeQueries.size())
        ));
        return suite;
    }

    private static double top1(Map<String, List<String>> ranked, List<Query> qs) {
        return (double) qs.stream().filter(q -> !ranked.get(q.q()).isEmpty() && q.expected().contains(ranked.get(q.q()).get(0)))
            .count() / qs.size();
    }

    private static double top5(Map<String, List<String>> ranked, List<Query> qs) {
        return (double) qs.stream().filter(q -> ranked.get(q.q()).stream().limit(5).anyMatch(q.expected()::contains)).count()
            / qs.size();
    }

    private static double mrr(Map<String, List<String>> ranked, List<Query> qs) {
        double sum = 0.0;
        for (Query q : qs) {
            List<String> res = ranked.get(q.q());
            for (int i = 0; i < res.size(); i++) {
                if (q.expected().contains(res.get(i))) {
                    sum += 1.0 / (i + 1);
                    break;
                }
            }
        }
        return sum / qs.size();
    }

    private static double mapAtK(Map<String, List<String>> ranked, List<Query> qs, int k) {
        double sum = 0.0;
        for (Query q : qs) {
            if (q.expected().isEmpty()) {
                continue;
            }
            List<String> res = ranked.get(q.q());
            int hits = 0;
            double ap = 0.0;
            int limit = Math.min(k, res.size());
            for (int i = 0; i < limit; i++) {
                if (q.expected().contains(res.get(i))) {
                    hits++;
                    ap += (double) hits / (i + 1);
                }
            }
            sum += ap / Math.min(q.expected().size(), k);
        }
        return sum / qs.size();
    }

    private static double falsePositiveTop1(Map<String, List<String>> ranked, List<Query> qs) {
        return (double) qs.stream().filter(q -> !ranked.get(q.q()).isEmpty() && !q.expected().contains(ranked.get(q.q()).get(0)))
            .count() / qs.size();
    }

    private static double noResultRate(Map<String, List<String>> ranked, List<Query> qs) {
        return (double) qs.stream().filter(q -> ranked.get(q.q()).isEmpty()).count() / qs.size();
    }

    private static List<Double> bootstrapCi95(List<Double> values) {
        if (values.isEmpty()) {
            return List.of(0.0, 0.0);
        }
        int n = values.size();
        int b = 2000;
        Random rng = new Random(42L);
        List<Double> means = new ArrayList<>(b);
        for (int i = 0; i < b; i++) {
            double s = 0.0;
            for (int j = 0; j < n; j++) {
                s += values.get(rng.nextInt(n));
            }
            means.add(s / n);
        }
        Collections.sort(means);
        double lo = means.get((int) Math.floor(0.025 * (b - 1)));
        double hi = means.get((int) Math.ceil(0.975 * (b - 1)));
        return List.of(round(lo), round(hi));
    }

    /** Nearest-rank percentile of a sorted list. */
    private static double percentile(List<Double> sorted, double p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
