package dev.chaya.api.publiclink;

import dev.chaya.api.publiclink.PublicViewerService.CreatedLink;
import dev.chaya.api.publiclink.PublicViewerService.LinkInfo;
import dev.chaya.api.publiclink.PublicViewerService.ViewerToken;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
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

    @Operation(summary = "Create a public viewer link",
            description = "The response carries the link secret once; it is never retrievable again. ttl is an "
                + "ISO-8601 duration up to chaya.security.public-link-max-ttl.")
    @ProblemResponse(status = 400, description = "BAD_REQUEST: ttl not positive or above the maximum, or a failed validation")
    @PostMapping("/venues/{venueId}/public-links")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public CreatedLink create(@PathVariable UUID venueId, @Valid @RequestBody CreateLink body) {
        return links.createLink(ActorAuthentication.currentActor(), venueId, body.label(), body.ttl());
    }

    @Operation(summary = "List the venue's public viewer links (never their secrets)")
    @GetMapping("/venues/{venueId}/public-links")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public List<LinkInfo> list(@PathVariable UUID venueId) {
        return links.listLinks(ActorAuthentication.currentActor(), venueId);
    }

    @Operation(summary = "Revoke a public viewer link")
    @DeleteMapping("/venues/{venueId}/public-links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public void revoke(@PathVariable UUID venueId, @PathVariable UUID linkId) {
        links.revoke(ActorAuthentication.currentActor(), venueId, linkId);
    }

    /** Unauthenticated by design: the link secret is the credential. */
    @Operation(summary = "Exchange a public link secret for a short-lived viewer token",
            description = "Unauthenticated by design: the secret is the credential. Send the token as "
                + "X-Chaya-Viewer-Token.")
    @ProblemResponse(status = 404, description = "NOT_FOUND: unknown, revoked or expired link")
    @PostMapping("/public/viewer-token")
    public ViewerToken exchange(@Valid @RequestBody Exchange body) {
        return links.exchange(body.secret());
    }
}
