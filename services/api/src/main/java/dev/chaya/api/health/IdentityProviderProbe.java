package dev.chaya.api.health;

/** Whether the token issuer's signing keys can be fetched right now; without them no bearer token can be verified. */
@FunctionalInterface
public interface IdentityProviderProbe {
    boolean reachable();
}
