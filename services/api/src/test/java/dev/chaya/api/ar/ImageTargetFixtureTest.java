package dev.chaya.api.ar;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The committed target image (packages/contracts/fixtures/ar) is exactly what {@link ImageTarget} renders for its anchor
 * id, pixel for pixel. The iOS app-level tests build their ARKit reference image from this file (apps/ios-ar,
 * ReferenceImageTests), so it must stay the real server artwork: if the generator changes, this test fails until the
 * fixture is regenerated. Pixels, not bytes, are compared: the PNG encoding may differ between JDKs, the image may not.
 *
 * <p>Regenerate (then commit the file and re-run the iOS app-level tests):
 * {@code mvn test -Dtest=ImageTargetFixtureTest -Dchaya.regenerateArFixture=true}
 */
class ImageTargetFixtureTest {

    static final UUID ANCHOR = UUID.fromString("5f0c1d2e-3a4b-4c5d-8e6f-7a8b9c0d1e2f");
    static final Path FIXTURE = Path.of("../../packages/contracts/fixtures/ar/image-target-" + ANCHOR + ".png");

    @Test
    void theCommittedFixtureIsTheServersTargetImageForItsAnchorId() throws Exception {
        if (Boolean.getBoolean("chaya.regenerateArFixture")) {
            Files.write(FIXTURE, ImageTarget.png(ANCHOR));
        }
        BufferedImage fixture = ImageIO.read(FIXTURE.toFile());
        BufferedImage rendered = ImageTarget.render(ANCHOR);
        assertThat(fixture.getWidth()).isEqualTo(ImageTarget.SIZE_PX);
        assertThat(fixture.getHeight()).isEqualTo(ImageTarget.SIZE_PX);
        int differing = 0;
        for (int y = 0; y < ImageTarget.SIZE_PX; y++) {
            for (int x = 0; x < ImageTarget.SIZE_PX; x++) {
                if ((fixture.getRGB(x, y) & 0xFFFFFF) != (rendered.getRGB(x, y) & 0xFFFFFF)) {
                    differing++;
                }
            }
        }
        assertThat(differing).as("pixels that differ from ImageTarget.render(%s): regenerate the fixture", ANCHOR).isZero();
    }
}
