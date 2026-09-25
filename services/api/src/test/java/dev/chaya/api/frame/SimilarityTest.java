package dev.chaya.api.frame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * X_canonical = s R X_reconstruction + t, its algebra and its estimation. Uses the synthetic MATHEMATICAL fixture shared
 * with the worker and web tests (packages/contracts/fixtures/synthetic-calibration.json) -- not venue data.
 */
class SimilarityTest {

    private static JsonNode fixture;

    @BeforeAll
    static void load() throws IOException {
        fixture = new ObjectMapper().readTree(Path.of("../../packages/contracts/fixtures/synthetic-calibration.json").toFile());
    }

    private static double[] vec(JsonNode n) {
        return new double[]{n.get(0).asDouble(), n.get(1).asDouble(), n.get(2).asDouble()};
    }

    private static Similarity truth() {
        JsonNode t = fixture.get("truth");
        JsonNode r = t.get("rotation");
        JsonNode tr = t.get("translation");
        return new Similarity(t.get("scale").asDouble(), new Quaternion(r.get("w").asDouble(), r.get("x").asDouble(),
            r.get("y").asDouble(), r.get("z").asDouble()), new double[]{tr.get("x").asDouble(), tr.get("y").asDouble(), tr.get("z").asDouble()});
    }

    private static Quaternion rotZ(double deg) {
        double h = Math.toRadians(deg) / 2;
        return new Quaternion(Math.cos(h), 0, 0, Math.sin(h));
    }

    private static void assertClose(double[] actual, double[] expected, double tol) {
        for (int i = 0; i < 3; i++) {
            assertThat(actual[i]).isCloseTo(expected[i], within(tol));
        }
    }

    private static final double[] P = {1.0, 2.0, 3.0};

    @Test
    void identityLeavesPointsUnchanged() {
        assertClose(Similarity.identity().apply(P), P, 0);
    }

    @Test
    void pureTranslation() {
        assertClose(new Similarity(1, Quaternion.IDENTITY, new double[]{1, -2, 0.5}).apply(P), new double[]{2, 0, 3.5}, 1e-15);
    }

    @Test
    void pureRotation() {
        assertClose(new Similarity(1, rotZ(90), new double[]{0, 0, 0}).apply(new double[]{1, 0, 0}), new double[]{0, 1, 0}, 1e-15);
    }

    @Test
    void pureScale() {
        assertClose(new Similarity(2.5, Quaternion.IDENTITY, new double[]{0, 0, 0}).apply(P), new double[]{2.5, 5, 7.5}, 1e-15);
    }

    @Test
    void combinedSimilarityMatchesTheFormula() {
        Similarity t = new Similarity(0.37, rotZ(33), new double[]{12.5, -4.25, 1.75});
        double[] r = rotZ(33).rotate(P);
        assertClose(t.apply(P), new double[]{0.37 * r[0] + 12.5, 0.37 * r[1] - 4.25, 0.37 * r[2] + 1.75}, 1e-12);
    }

    @Test
    void inverseAndRoundTripAreAtDoublePrecision() {
        Similarity t = truth();
        Similarity inv = t.inverse();
        Random rng = new Random(7);
        double worst = 0;
        for (int i = 0; i < 1000; i++) {
            double[] p = {rng.nextDouble() * 1000 - 500, rng.nextDouble() * 1000 - 500, rng.nextDouble() * 1000 - 500};
            double[] back = inv.apply(t.apply(p));
            worst = Math.max(worst, Vectors.distance(back, p));
        }
        assertThat(worst).isLessThan(1e-9);
        Similarity id = t.compose(inv);
        assertThat(id.scale()).isCloseTo(1.0, within(1e-12));
        assertClose(id.translation(), new double[]{0, 0, 0}, 1e-9);
    }

    @Test
    void composeAppliesInnerFirst() {
        Similarity a = new Similarity(2, rotZ(90), new double[]{1, 0, 0});
        Similarity b = new Similarity(0.5, rotZ(-30), new double[]{0, 3, 0});
        assertClose(a.compose(b).apply(P), a.apply(b.apply(P)), 1e-12);
    }

    @Test
    void rejectsNonPositiveScaleAndNonFiniteInput() {
        assertThatThrownBy(() -> new Similarity(0, Quaternion.IDENTITY, new double[]{0, 0, 0})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Similarity(Double.NaN, Quaternion.IDENTITY, new double[]{0, 0, 0})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Quaternion(0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reconstructionToMetricAndBackWithTheSyntheticFixture() {
        Similarity t = truth();
        for (JsonNode cp : fixture.get("controlPoints")) {
            assertClose(t.apply(vec(cp.get("reconstruction"))), vec(cp.get("venue")), 1e-9);
            assertClose(t.inverse().apply(vec(cp.get("venue"))), vec(cp.get("reconstruction")), 1e-9);
        }
    }

    @Test
    void controlPointEstimationRecoversTheTruthExactly() {
        List<double[]> src = new ArrayList<>();
        List<double[]> dst = new ArrayList<>();
        for (JsonNode cp : fixture.get("controlPoints")) {
            src.add(vec(cp.get("reconstruction")));
            dst.add(vec(cp.get("venue")));
        }
        SimilarityEstimator.Fit fit = SimilarityEstimator.fromCorrespondences(src, dst);
        assertThat(fit.transform().scale()).isCloseTo(truth().scale(), within(1e-12));
        assertThat(fit.transform().rotation().angleToDegrees(truth().rotation())).isLessThan(1e-6);
        assertClose(fit.transform().translation(), truth().translation(), 1e-9);
        assertThat(fit.rmsResidual()).isLessThan(1e-9);
    }

    @Test
    void controlPointEstimationRejectsCollinearPoints() {
        List<double[]> line = List.of(new double[]{0, 0, 0}, new double[]{1, 0, 0}, new double[]{2, 0, 0});
        assertThatThrownBy(() -> SimilarityEstimator.fromCorrespondences(line, line)).hasMessageContaining("collinear");
    }

    @Test
    void measuredDistancesGiveTheScaleAndReconstructionUnitsAreNotMetres() {
        List<double[]> lm = new ArrayList<>();
        for (JsonNode ref : fixture.get("distanceReferences")) {
            double l = Vectors.distance(vec(ref.get("a")), vec(ref.get("b")));
            double m = ref.get("measuredMetres").asDouble();
            assertThat(Math.abs(l - m) / m).as("the fixture's reconstruction units are not metres").isGreaterThan(0.5);
            lm.add(new double[]{l, m});
        }
        SimilarityEstimator.ScaleFit fit = SimilarityEstimator.scaleFromDistances(lm);
        assertThat(fit.scale()).isCloseTo(truth().scale(), within(1e-12));
        assertThat(fit.relativeSpread()).isLessThan(1e-12);
    }

    @Test
    void inconsistentDistancesShowTheirSpread() {
        SimilarityEstimator.ScaleFit fit = SimilarityEstimator.scaleFromDistances(List.of(new double[]{1, 1}, new double[]{1, 1.1}));
        assertThat(fit.relativeSpread()).isCloseTo(0.0476, within(1e-3));
    }

    @Test
    void gravityAlignmentLevelsTheReconstructionUpOntoCanonicalZ() {
        double[] up = vec(fixture.get("gravity").get("upReconstruction"));
        Quaternion level = SimilarityEstimator.levelling(up);
        assertClose(level.rotate(up), CanonicalFrame.up(), 1e-12);
        // The truth's own rotation also maps up to +Z; the two differ only by a rotation about the vertical.
        assertClose(truth().applyDirection(up), CanonicalFrame.up(), 1e-12);
    }

    @Test
    void floorLocalFramePutsTheFloorAtZeroAndTheReferencePointAtTheOriginInMetres() {
        JsonNode g = fixture.get("gravity");
        double[] up = vec(g.get("upReconstruction"));
        double[] floor = vec(g.get("floorPointReconstruction"));
        double[] origin = SimilarityEstimator.projectOntoPlane(vec(g.get("cameraCentroidReconstruction")), floor, up);
        Similarity local = SimilarityEstimator.floorLocal(truth().scale(), up, origin);
        assertClose(local.apply(origin), new double[]{0, 0, 0}, 1e-9);
        assertThat(local.apply(floor)[2]).isCloseTo(0.0, within(1e-9));
        // Metric: distances in the local frame equal the fixture's canonical distances.
        JsonNode cps = fixture.get("controlPoints");
        double canonical = Vectors.distance(vec(cps.get(0).get("venue")), vec(cps.get(4).get("venue")));
        double local04 = Vectors.distance(local.apply(vec(cps.get(0).get("reconstruction"))), local.apply(vec(cps.get(4).get("reconstruction"))));
        assertThat(local04).isCloseTo(canonical, within(1e-9));
        // Heights above the floor are preserved too (up is +Z in the local frame).
        assertThat(local.apply(vec(cps.get(4).get("reconstruction")))[2]).isCloseTo(2.4, within(1e-9));
    }

    @Test
    void floorPlaneFromOperatorPointsIsOrientedTowardsThePointAbove() {
        List<double[]> floor = List.of(new double[]{0, 0, 5}, new double[]{1, 0, 5}, new double[]{0, 1, 5}, new double[]{1, 1, 5});
        SimilarityEstimator.FittedPlane below = SimilarityEstimator.plane(floor, new double[]{0, 0, 0});
        assertClose(below.normal(), new double[]{0, 0, -1}, 1e-12);
        assertThat(below.rmsDistance()).isLessThan(1e-12);
        SimilarityEstimator.FittedPlane above = SimilarityEstimator.plane(floor, new double[]{0, 0, 9});
        assertClose(above.normal(), new double[]{0, 0, 1}, 1e-12);
    }

    @Test
    void symmetricEigenSolvesAKnownMatrix() {
        SymmetricEigen e = SymmetricEigen.of(new double[][]{{2, 1, 0}, {1, 2, 0}, {0, 0, 5}});
        assertThat(e.values()[0]).isCloseTo(5, within(1e-12));
        assertThat(e.values()[1]).isCloseTo(3, within(1e-12));
        assertThat(e.values()[2]).isCloseTo(1, within(1e-12));
    }
}
