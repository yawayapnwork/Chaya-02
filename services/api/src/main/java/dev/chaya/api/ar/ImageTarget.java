package dev.chaya.api.ar;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * The target image of an IMAGE_TARGET anchor: generated deterministically from the anchor's id, so the image an operator
 * prints and the image the WebXR client registers for tracking are the same image, and a tracked image identifies exactly
 * one registered anchor (docs/ar.md, "Android: WebXR").
 *
 * <p>Image trackers (ARCore's augmented images, behind WebXR image tracking) need dense, non-repetitive, high-contrast
 * texture. The image is layered random rectangles and discs of every size, in grey levels from black to white, over a
 * mid-grey ground, with a black border. No text, and no symmetry: random placement at every scale gives the tracker
 * distinct corners everywhere. Whether a given image is trackable is reported by the platform itself
 * (XRSession.getTrackedImageScores), and the client excludes any it calls "untrackable".
 *
 * <p>Pixels are written directly, without fonts or anti-aliasing, so the bytes depend only on the anchor id and the
 * JDK's PNG encoder.
 */
public final class ImageTarget {

    public static final int SIZE_PX = 1024;
    private static final int BORDER_PX = 24;

    private ImageTarget() {}

    public static byte[] png(UUID anchorId) {
        BufferedImage image = render(anchorId);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static BufferedImage render(UUID anchorId) {
        Random rng = new Random(anchorId.getMostSignificantBits() ^ Long.rotateLeft(anchorId.getLeastSignificantBits(), 17));
        int[] px = new int[SIZE_PX * SIZE_PX];
        java.util.Arrays.fill(px, 128);
        // coarse to fine: large shapes first, then ever smaller ones on top
        int[][] scales = {{220, 40}, {120, 120}, {60, 400}, {28, 1400}, {12, 3000}};
        for (int[] scale : scales) {
            int maxSize = scale[0];
            for (int n = 0; n < scale[1]; n++) {
                int w = 4 + rng.nextInt(maxSize);
                int h = 4 + rng.nextInt(maxSize);
                int x0 = rng.nextInt(SIZE_PX);
                int y0 = rng.nextInt(SIZE_PX);
                int grey = rng.nextBoolean() ? rng.nextInt(64) : 191 + rng.nextInt(65); // mostly near black or white
                boolean disc = rng.nextInt(3) == 0;
                for (int y = Math.max(0, y0); y < Math.min(SIZE_PX, y0 + h); y++) {
                    for (int x = Math.max(0, x0); x < Math.min(SIZE_PX, x0 + w); x++) {
                        if (disc) {
                            double dx = (x - x0 - w / 2.0) / (w / 2.0);
                            double dy = (y - y0 - h / 2.0) / (h / 2.0);
                            if (dx * dx + dy * dy > 1) {
                                continue;
                            }
                        }
                        px[y * SIZE_PX + x] = grey;
                    }
                }
            }
        }
        BufferedImage image = new BufferedImage(SIZE_PX, SIZE_PX, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE_PX; y++) {
            for (int x = 0; x < SIZE_PX; x++) {
                boolean border = x < BORDER_PX || y < BORDER_PX || x >= SIZE_PX - BORDER_PX || y >= SIZE_PX - BORDER_PX;
                int g = border ? 0 : px[y * SIZE_PX + x];
                image.setRGB(x, y, (g << 16) | (g << 8) | g);
            }
        }
        return image;
    }
}
