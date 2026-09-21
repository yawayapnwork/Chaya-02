package dev.chaya.api.poi;

import dev.chaya.api.poi.PoiService.Poi;
import dev.chaya.api.poi.PoiService.PoiData;
import dev.chaya.api.security.ActorAuthentication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/venues/{venueId}/pois")
public class PoiController {

    public record PoiRequest(UUID floorId, UUID spaceId, @NotBlank String label, String category,
                             String description, List<String> tags, @NotNull Double x, @NotNull Double y,
                             @NotNull Double z) {
        PoiData toData() {
            return new PoiData(floorId, spaceId, label, category, description, tags, x, y, z);
        }
    }

    private final PoiService pois;

    public PoiController(PoiService pois) {
        this.pois = pois;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public List<Poi> list(@PathVariable UUID venueId) {
        return pois.list(ActorAuthentication.currentActor(), venueId);
    }

    @GetMapping("/{poiId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public Poi get(@PathVariable UUID venueId, @PathVariable UUID poiId) {
        return pois.get(ActorAuthentication.currentActor(), venueId, poiId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public Poi create(@PathVariable UUID venueId, @Valid @RequestBody PoiRequest body) {
        return pois.create(ActorAuthentication.currentActor(), venueId, body.toData());
    }

    @PutMapping("/{poiId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public Poi update(@PathVariable UUID venueId, @PathVariable UUID poiId, @Valid @RequestBody PoiRequest body) {
        return pois.update(ActorAuthentication.currentActor(), venueId, poiId, body.toData());
    }

    @DeleteMapping("/{poiId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER')")
    public void delete(@PathVariable UUID venueId, @PathVariable UUID poiId) {
        pois.delete(ActorAuthentication.currentActor(), venueId, poiId);
    }
}
