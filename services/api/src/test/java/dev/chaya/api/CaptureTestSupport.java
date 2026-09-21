package dev.chaya.api;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Helpers to drive the real upload protocol with real file bytes. */
abstract class CaptureTestSupport extends ApiTest {

    static final int PART = 5 * 1024 * 1024; // matches chaya.uploads.part-size-bytes in the test profile

    record Ctx(UUID org, UUID venue, UUID floor, String operator) {}

    record Upload(UUID mediaId, int totalParts, byte[] bytes) {}

    protected Ctx ctx() {
        UUID org = fx.organization();
        UUID venue = fx.venue(org);
        UUID floor = fx.floor(org, venue, 0);
        return new Ctx(org, venue, floor, TestJwt.user(org, "operator").venues(venue).token());
    }

    protected UUID newCapture(Ctx c) throws Exception {
        String body = "{\"floorId\":\"" + c.floor() + "\",\"device\":{\"model\":\"test-phone\",\"os\":\"android 14\"}}";
        String json = post("/api/v1/venues/" + c.venue() + "/captures", c.operator(), body)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
    }

    protected String capUrl(Ctx c, UUID capture) {
        return "/api/v1/venues/" + c.venue() + "/captures/" + capture;
    }

    // ---- real file bytes ----------------------------------------------------------------------

    /** A real, decodable PNG. The seed varies the pixels so every image has a different checksum. */
    static byte[] png(int seed) throws Exception {
        return image("png", seed);
    }

    static byte[] jpeg(int seed) throws Exception {
        return image("jpg", seed);
    }

    private static byte[] image(String format, int seed) throws Exception {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                img.setRGB(x, y, (seed * 7919 + x * 31 + y * 17) & 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    /**
     * An MP4-signature file of the requested size: a valid ISO base media "ftyp" box followed by
     * filler. It is a container header fixture for type detection, not a playable video.
     */
    static byte[] mp4(int size) {
        byte[] ftyp = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0, 'm', 'p', '4', '2', 'i', 's', 'o', 'm'};
        byte[] data = new byte[size];
        System.arraycopy(ftyp, 0, data, 0, ftyp.length);
        for (int i = ftyp.length; i < size; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- protocol steps -----------------------------------------------------------------------

    protected String initBody(String kind, String filename, String contentType, long size, String sha) {
        return "{\"kind\":\"" + kind + "\",\"filename\":" + quote(filename) + ",\"contentType\":\"" + contentType
            + "\",\"sizeBytes\":" + size + ",\"sha256\":\"" + sha + "\"}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    protected Upload init(Ctx c, UUID capture, String kind, String filename, String type, byte[] bytes, String declaredSha) throws Exception {
        String json = post(capUrl(c, capture) + "/media", c.operator(), initBody(kind, filename, type, bytes.length, declaredSha))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.replaceAll(".*\"mediaId\":\"([^\"]+)\".*", "$1"));
        int parts = Integer.parseInt(json.replaceAll(".*\"totalParts\":(\\d+).*", "$1"));
        return new Upload(id, parts, bytes);
    }

    protected org.springframework.test.web.servlet.ResultActions sendPart(Ctx c, UUID capture, Upload u, int part,
                                                                          byte[] body, String partSha) throws Exception {
        var req = MockMvcRequestBuilders.request(HttpMethod.PUT, capUrl(c, capture) + "/media/" + u.mediaId() + "/parts/" + part)
            .header("Authorization", "Bearer " + c.operator()).contentType(MediaType.APPLICATION_OCTET_STREAM).content(body);
        if (partSha != null) {
            req.header("X-Part-Sha256", partSha);
        }
        return mvc.perform(req);
    }

    protected void sendAllParts(Ctx c, UUID capture, Upload u) throws Exception {
        for (int p = 1; p <= u.totalParts(); p++) {
            byte[] chunk = Arrays.copyOfRange(u.bytes(), (p - 1) * PART, Math.min(u.bytes().length, p * PART));
            sendPart(c, capture, u, p, chunk, sha256(chunk)).andExpect(status().isNoContent());
        }
    }

    protected void completeMedia(Ctx c, UUID capture, Upload u) throws Exception {
        post(capUrl(c, capture) + "/media/" + u.mediaId() + "/complete", c.operator(), null).andExpect(status().isAccepted());
    }

    /** Full happy-path upload with a correct checksum; returns once the file has been handed to validation. */
    protected Upload upload(Ctx c, UUID capture, String kind, String filename, String type, byte[] bytes) throws Exception {
        Upload u = init(c, capture, kind, filename, type, bytes, sha256(bytes));
        sendAllParts(c, capture, u);
        completeMedia(c, capture, u);
        return u;
    }

    /** Polls until validation leaves VALIDATING (it runs asynchronously). */
    protected String awaitSettled(Ctx c, UUID capture, UUID media) throws Exception {
        for (int i = 0; i < 100; i++) {
            String json = get(capUrl(c, capture) + "/media/" + media, c.operator()).andReturn().getResponse().getContentAsString();
            String status = json.replaceAll(".*\"status\":\"([A-Z_]+)\".*", "$1");
            if (!status.equals("VALIDATING") && !status.equals("PENDING")) {
                return json;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("media " + media + " did not finish validating in time");
    }

    protected static String field(String json, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + name + "\":(null|\"([^\"]*)\"|[^,}]+)").matcher(json);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(1)) : null;
    }
}
