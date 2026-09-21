package dev.chaya.api.web;

/** Resource missing OR not visible to the caller; the two are deliberately indistinguishable. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
