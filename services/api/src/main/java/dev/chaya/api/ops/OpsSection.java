package dev.chaya.api.ops;

import dev.chaya.api.security.Actor;
import dev.chaya.api.security.Role;
import java.util.EnumSet;
import java.util.Set;

/**
 * Sections of the operations dashboard and the roles that may read each one. This single table is used both to
 * enforce access (OpsDashboardService#require) and to tell the UI which sections to render
 * (GET .../ops/access), so the two can never disagree. See docs/security.md, "Operations dashboard".
 *
 * <ul>
 *   <li>Venue state (overview, current version, freshness): every venue role, including viewer.</li>
 *   <li>Capture and processing data (coverage, jobs, failures, storage/cost, re-scans): the roles that capture and
 *       process -- the same set the capture and job endpoints admit.</li>
 *   <li>Venue management data (search analytics, audit activity): admin and venue-manager.</li>
 * </ul>
 */
public enum OpsSection {
    OVERVIEW(EnumSet.of(Role.ADMIN, Role.VENUE_MANAGER, Role.OPERATOR, Role.VIEWER)),
    COVERAGE(EnumSet.of(Role.ADMIN, Role.VENUE_MANAGER, Role.OPERATOR)),
    JOBS(EnumSet.of(Role.ADMIN, Role.VENUE_MANAGER, Role.OPERATOR)),
    FAILURES(EnumSet.of(Role.ADMIN, Role.VENUE_MANAGER, Role.OPERATOR)),
    STORAGE(EnumSet.of(Role.ADMIN, Role.VENUE_MANAGER, Role.OPERATOR)),
    RESCANS(EnumSet.of(Role.ADMIN, Role.VENUE_MANAGER, Role.OPERATOR)),
    SEARCH_ANALYTICS(EnumSet.of(Role.ADMIN, Role.VENUE_MANAGER)),
    AUDIT(EnumSet.of(Role.ADMIN, Role.VENUE_MANAGER));

    /** Roles that may retry or cancel processing (the job/processing endpoints' own rule). */
    public static final Set<Role> PROCESSING_CONTROL = EnumSet.of(Role.ADMIN, Role.VENUE_MANAGER, Role.OPERATOR);

    private final Set<Role> roles;

    OpsSection(Set<Role> roles) {
        this.roles = roles;
    }

    public boolean permits(Actor actor) {
        return actor.roles().stream().anyMatch(roles::contains);
    }

    public static boolean mayControlProcessing(Actor actor) {
        return actor.roles().stream().anyMatch(PROCESSING_CONTROL::contains);
    }
}
