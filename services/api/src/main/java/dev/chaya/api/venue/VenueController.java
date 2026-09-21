package dev.chaya.api.venue;

import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.venue.VenueService.Venue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/venues")
public class VenueController {

    public record CreateVenue(@NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$") String slug,
                              @NotBlank String name, String timezone) {}

    public record UpdateVenue(String name, String timezone) {}

    private final VenueService venues;

    public VenueController(VenueService venues) {
        this.venues = venues;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER')")
    public List<Venue> list() {
        return venues.list(ActorAuthentication.currentActor());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Venue create(@Valid @RequestBody CreateVenue body) {
        return venues.create(ActorAuthentication.currentActor(), body.slug(), body.name(), body.timezone());
    }

    @GetMapping("/{venueId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public Venue get(@PathVariable UUID venueId) {
        return venues.get(ActorAuthentication.currentActor(), venueId);
    }

    @PatchMapping("/{venueId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public Venue update(@PathVariable UUID venueId, @RequestBody UpdateVenue body) {
        return venues.update(ActorAuthentication.currentActor(), venueId, body.name(), body.timezone());
    }
}
