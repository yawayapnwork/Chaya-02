package dev.chaya.api.capture;

import java.text.Normalizer;

/**
 * Makes a client-supplied file name safe to store and display. The result is never used to build
 * an object key or a filesystem path; it is descriptive metadata only.
 */
public final class FilenameSanitizer {

    private static final int MAX_LENGTH = 100;

    private FilenameSanitizer() {}

    public static String sanitize(String raw) {
        String name = raw == null ? "" : raw;
        // Drop any directory part, whichever separator style was used.
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFKD);
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_");     // also removes control chars and NULs
        name = name.replaceAll("\\.{2,}", ".");                 // no ".." sequences
        name = name.replaceAll("^[.\\s_-]+", "");               // no hidden files or leading junk
        name = name.strip();
        if (name.length() > MAX_LENGTH) {
            name = name.substring(0, MAX_LENGTH);
        }
        return name.isEmpty() ? "file" : name;
    }
}
