package dev.chaya.api.frame;

import java.util.Arrays;

/**
 * X_out = scale * R(rotation) * X_in + translation. The transform from a reconstruction's own frame into the Chaya
 * canonical frame (see {@link CanonicalFrame}), and the composition algebra the frame lifecycle needs. All arithmetic
 * is double precision. Mirrors chaya_worker.frames.Similarity and apps/web/lib/coordinate-frame.ts; the shared
 * synthetic fixture packages/contracts/fixtures/synthetic-calibration.json checks all three agree.
 */
public record Similarity(double scale, Quaternion rotation, double[] translation) {

    public Similarity {
        if (!Double.isFinite(scale) || scale <= 0) {
            throw new IllegalArgumentException("scale must be finite and positive, got " + scale);
        }
        if (rotation == null) {
            throw new IllegalArgumentException("rotation is required");
        }
        if (translation == null || translation.length != 3 || !Arrays.stream(translation).allMatch(Double::isFinite)) {
            throw new IllegalArgumentException("translation must be three finite numbers");
        }
        translation = translation.clone();
    }

    public static Similarity identity() {
        return new Similarity(1.0, Quaternion.IDENTITY, new double[]{0, 0, 0});
    }

    @Override
    public double[] translation() {
        return translation.clone();
    }

    public double[] apply(double[] p) {
        double[] r = rotation.rotate(p);
        return new double[]{scale * r[0] + translation[0], scale * r[1] + translation[1], scale * r[2] + translation[2]};
    }

    /** Rotates a direction (no scale, no translation) and renormalises it. */
    public double[] applyDirection(double[] d) {
        return Vectors.normalize(rotation.rotate(d));
    }

    /** An orientation expressed in the input frame, expressed in the output frame: R_out = R * R_in. */
    public Quaternion applyOrientation(Quaternion q) {
        return rotation.multiply(q);
    }

    public Similarity inverse() {
        Quaternion inv = rotation.conjugate();
        double[] t = inv.rotate(translation);
        return new Similarity(1.0 / scale, inv, new double[]{-t[0] / scale, -t[1] / scale, -t[2] / scale});
    }

    /** this after inner: X -> this(inner(X)). */
    public Similarity compose(Similarity inner) {
        double[] t = rotation.rotate(inner.translation);
        return new Similarity(scale * inner.scale, rotation.multiply(inner.rotation),
            new double[]{scale * t[0] + translation[0], scale * t[1] + translation[1], scale * t[2] + translation[2]});
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Similarity s && scale == s.scale && rotation.equals(s.rotation) && Arrays.equals(translation, s.translation);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Double.hashCode(scale) + rotation.hashCode()) + Arrays.hashCode(translation);
    }

    @Override
    public String toString() {
        return "Similarity[scale=" + scale + ", rotation=" + rotation + ", translation=" + Arrays.toString(translation) + "]";
    }
}
