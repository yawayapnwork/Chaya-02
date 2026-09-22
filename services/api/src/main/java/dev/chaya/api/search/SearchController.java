package dev.chaya.api.search;

import dev.chaya.api.search.SearchDtos.SearchResponse;
import dev.chaya.api.security.ActorAuthentication;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/venues/{venueId}/search")
public class SearchController {

    private final SemanticSearchService search;

    public SearchController(SemanticSearchService search) {
        this.search = search;
    }

    /** q: natural-language query (required). floorId: restrict to one floor. topK: result count (server
     * clamps to chaya.search.max-top-k). accessible: only POIs whose attributes mark them accessible. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public SearchResponse search(@PathVariable UUID venueId, @RequestParam String q, @RequestParam(required = false) UUID floorId,
                                 @RequestParam(required = false) Integer topK, @RequestParam(required = false) Boolean accessible) {
        return search.search(ActorAuthentication.currentActor(), venueId, q, floorId, topK, accessible);
    }
}
