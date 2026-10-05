package dev.chaya.api.security;

/**
 * Who counts as the public: an anonymous public-link session (docs/security.md, "Public viewer links"). Such a viewer is
 * shown only what a floor explicitly publishes -- FINALIZED versions, the current version's POIs -- and never anything
 * from a run whose frames were not anonymised (privacy disabled: its reconstruction, artifacts and detections; review
 * S-6). Every read path that filters for that asks here.
 */
public final class PublicExposure {

    private PublicExposure() {}

    public static boolean isAnonymous(Actor actor) {
        return actor.kind() == Actor.Kind.PUBLIC_VIEWER || actor.roles().contains(Role.PUBLIC_VIEWER);
    }
}
