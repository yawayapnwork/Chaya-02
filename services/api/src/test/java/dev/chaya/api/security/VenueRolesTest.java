package dev.chaya.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** How an actor's roles resolve at one venue (review S-8), without HTTP (VenueRoleAuthorizationTest covers the API). */
class VenueRolesTest {

    final UUID org = UUID.randomUUID();
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();

    @Test
    void aGrantIsExactlyTheRolesAtItsVenueAndNothingElsewhere() {
        Actor split = new Actor(Actor.Kind.USER, "u", org, Set.of(), Set.of(),
            Map.of(a, Set.of(Role.VENUE_MANAGER), b, Set.of(Role.VIEWER)));
        assertThat(split.rolesAt(a)).containsExactly(Role.VENUE_MANAGER);
        assertThat(split.rolesAt(b)).containsExactly(Role.VIEWER);
        assertThat(split.rolesAt(c)).isEmpty();
        assertThat(split.mayAccessVenue(c)).isFalse();
        assertThat(split.allRoles()).containsExactlyInAnyOrder(Role.VENUE_MANAGER, Role.VIEWER);

        Actor atB = split.atVenue(b);
        assertThat(atB.roles()).containsExactly(Role.VIEWER);
        assertThat(atB.allRoles()).containsExactly(Role.VIEWER);
        assertThat(atB.venueIds()).containsExactly(b);
        assertThat(atB.mayAccessVenue(a)).as("a venue-scoped actor reaches no other venue").isFalse();
        assertThat(split.atVenue(c)).as("a non-member is left for TenantGuard to refuse").isSameAs(split);
    }

    @Test
    void realmRolesApplyAtVenueIdVenuesUnlessAGrantSaysOtherwise() {
        Actor legacy = new Actor(Actor.Kind.USER, "u", org, Set.of(a, b), Set.of(Role.VENUE_MANAGER), Map.of(b, Set.of(Role.VIEWER)));
        assertThat(legacy.rolesAt(a)).containsExactly(Role.VENUE_MANAGER);
        assertThat(legacy.rolesAt(b)).containsExactly(Role.VIEWER);
        assertThat(legacy.rolesAt(c)).isEmpty();
    }

    @Test
    void anOrganizationAdminIsUnchangedEverywhereInItsOrganization() {
        Actor admin = new Actor(Actor.Kind.USER, "u", org, Set.of(), Set.of(Role.ADMIN));
        assertThat(admin.rolesAt(c)).containsExactly(Role.ADMIN);
        assertThat(admin.atVenue(c)).isSameAs(admin);
        Actor publicViewer = new Actor(Actor.Kind.PUBLIC_VIEWER, "p", org, Set.of(a), Set.of(Role.PUBLIC_VIEWER));
        assertThat(publicViewer.atVenue(a)).isSameAs(publicViewer);
    }

    @Test
    void aGrantCannotCarryAdminServiceOrPublicViewer() {
        for (Role r : new Role[] {Role.ADMIN, Role.SERVICE, Role.PUBLIC_VIEWER}) {
            assertThatThrownBy(() -> new Actor(Actor.Kind.USER, "u", org, Set.of(), Set.of(), Map.of(a, Set.of(r))))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theVenueRolesClaimParsesOrInvalidatesTheToken() {
        assertThat(JwtActorConverter.venueRoles(List.of(a + ":venue-manager", a + ":viewer", b + ":operator")))
            .isEqualTo(Map.of(a, Set.of(Role.VENUE_MANAGER, Role.VIEWER), b, Set.of(Role.OPERATOR)));
        assertThat(JwtActorConverter.venueRoles(a + ":viewer")).isEqualTo(Map.of(a, Set.of(Role.VIEWER)));
        assertThat(JwtActorConverter.venueRoles(null)).isEmpty();
        for (Object bad : List.of(a + ":admin", a + ":service", a + ":", a.toString(), "x:viewer", List.of(a + ":viewer", 7))) {
            assertThatThrownBy(() -> JwtActorConverter.venueRoles(bad)).as(String.valueOf(bad))
                .isInstanceOf(InvalidBearerTokenException.class);
        }
    }

    @Test
    void onlyVenuePathsWithAWellFormedIdAreScoped() {
        assertThat(VenueScopeFilter.venueOf("/api/v1/venues/" + a)).contains(a);
        assertThat(VenueScopeFilter.venueOf("/api/v1/venues/" + a + "/pois/x")).contains(a);
        assertThat(VenueScopeFilter.venueOf("/api/v1/venues")).isEmpty();
        assertThat(VenueScopeFilter.venueOf("/api/v1/venues/not-a-uuid/pois")).isEmpty();
        assertThat(VenueScopeFilter.venueOf("/api/v1/navigation/routes")).isEmpty();
        assertThat(VenueScopeFilter.venueOf("/api/v1/venuesX/" + a)).isEmpty();
    }
}
