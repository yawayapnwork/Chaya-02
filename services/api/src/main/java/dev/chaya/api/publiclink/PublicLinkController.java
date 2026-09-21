package dev.chaya.api.publiclink;

import dev.chaya.api.publiclink.PublicViewerService.CreatedLink;
import dev.chaya.api.publiclink.PublicViewerService.LinkInfo;
import dev.chaya.api.publiclink.PublicViewerService.ViewerToken;
import dev.chaya.api.security.ActorAuthentication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PublicLinkController {

    public record CreateLink(String label, @NotNull Duration ttl) {}

    public record Exchange(@NotBlank String secret) {}

    private final PublicViewerService links;

    public PublicLinkController(PublicViewerService links) {
        this.links = links;
    }

    @PostMapping("/venues/{venueId}/public-links")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public CreatedLink create(@PathVariable UUID venueId, @Valid @RequestBody CreateLink body) {
        return links.createLink(ActorAuthentication.currentActor(), venueId, body.label(), body.ttl());
    }

    @GetMapping("/venues/{venueId}/public-links")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public List<LinkInfo> list(@PathVariable UUID venueId) {
        return links.listLinks(ActorAuthentication.currentActor(), venueId);
    }

    @DeleteMapping("/venues/{venueId}/public-links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public void revoke(@PathVariable UUID venueId, @PathVariable UUID linkId) {
        links.revoke(ActorAuthentication.currentActor(), venueId, linkId);
    }

    /** Unauthenticated by design: the link secret is the credential. */
    @PostMapping("/public/viewer-token")
    public ViewerToken exchange(@Valid @RequestBody Exchange body) {
        return links.exchange(body.secret());
    }
}
