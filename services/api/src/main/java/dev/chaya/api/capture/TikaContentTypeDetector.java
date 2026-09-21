package dev.chaya.api.capture;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/** Apache Tika (in-process) magic-byte detection. No file name or claimed type is passed in. */
@Component
public class TikaContentTypeDetector implements ContentTypeDetector {

    private final Tika tika = new Tika();

    @Override
    public String detect(byte[] head) {
        return tika.detect(head);
    }
}
