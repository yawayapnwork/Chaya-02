package dev.chaya.api.capture;

/** Determines the real content type from the bytes, ignoring what the client claimed. */
public interface ContentTypeDetector {
    /** @param head the first bytes of the file (up to 64 KiB) */
    String detect(byte[] head);
}
