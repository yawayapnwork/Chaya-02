package dev.chaya.api.security;

import java.util.Optional;

/** Resolves a raw public-viewer token to an actor, or empty if unknown, expired or revoked. */
public interface PublicViewerAuthenticator {
    Optional<Actor> authenticate(String rawToken);
}
