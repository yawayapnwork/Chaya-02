package dev.chaya.api.search;

import dev.chaya.api.search.SearchDtos.SearchPosition;
import dev.chaya.api.search.SearchDtos.SearchResponse;
import dev.chaya.api.web.ApiException;
import dev.chaya.api.security.ActorAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
     * clamps to chaya.search.max-top-k). accessible: only POIs whose attributes mark them accessible. scanVersionId: only
     * the POIs of that FINALIZED scan version. position + positionFloorId: where the person searching is, for "nearest"
     * (docs/search.md). */
    @Operation(summary = "Semantic POI search",
            description = "q: natural-language query (required), e.g. 'find the nearest staircase'. floorId: restrict to one "
                + "floor. topK: result count (clamped to chaya.search.max-top-k). accessible: only POIs marked accessible (a "
                + "query asking for an accessible place sets it too). scanVersionId: only the POIs of that FINALIZED scan "
                + "version. position (x,y,z canonical metres) and positionFloorId: the searcher's position on that floor, in "
                + "its current coordinate frame (or the scan version's); a query asking for the nearest one is then ordered "
                + "by walking-route distance from it. Without a position 'nearest' is reported as not applied. Rate limited "
                + "per caller (chaya.rate-limit.search-per-minute).")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public SearchResponse search(@PathVariable UUID venueId, @RequestParam String q, @RequestParam(required = false) UUID floorId,
                                 @RequestParam(required = false) Integer topK, @RequestParam(required = false) Boolean accessible,
                                 @RequestParam(required = false) UUID scanVersionId,
                                 @RequestParam(required = false) List<Double> position,
                                 @RequestParam(required = false) UUID positionFloorId) {
        SearchPosition at = null;
        if (position != null || positionFloorId != null) {
            if (position == null || position.size() != 3 || position.contains(null) || positionFloorId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_POSITION",
                    "position needs three coordinates (x,y,z) together with positionFloorId");
            }
            at = new SearchPosition(positionFloorId, position.get(0), position.get(1), position.get(2));
        }
        return search.search(ActorAuthentication.currentActor(), venueId,
            new SemanticSearchService.SearchQuery(q, floorId, topK, accessible, scanVersionId, at));
    }
}
