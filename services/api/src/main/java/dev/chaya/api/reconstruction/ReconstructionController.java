package dev.chaya.api.reconstruction;

import dev.chaya.api.reconstruction.ReconstructionService.Reconstruction;
import dev.chaya.api.reconstruction.ReconstructionService.ReconstructionVersion;
import dev.chaya.api.reconstruction.ReconstructionService.StoredArtifact;
import dev.chaya.api.security.ActorAuthentication;
import dev.chaya.api.storage.ObjectStore;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Read side of the reconstruction pipeline's output, for the digital twin viewer: which reconstructions
 * exist for a floor, and the actual artifact bytes (.ksplat, manifest, plane model). The viewer never
 * talks to object storage directly -- it gets a URL back from here, exactly as the security/performance
 * requirements ask, and every request is venue- and organization-scoped through TenantGuard, so a public
 * viewer link can only ever reach the one venue it was issued for.
 */
@RestController
@RequestMapping("/api/v1")
public class ReconstructionController {

    private final ReconstructionService reconstructions;
    private final ObjectStore derivedStore;

    public ReconstructionController(ReconstructionService reconstructions, @Qualifier("derived") ObjectStore derivedStore) {
        this.reconstructions = reconstructions;
        this.derivedStore = derivedStore;
    }

    @GetMapping("/venues/{venueId}/floors/{floorId}/reconstructions")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public List<ReconstructionVersion> list(@PathVariable UUID venueId, @PathVariable UUID floorId) {
        return reconstructions.listForFloor(ActorAuthentication.currentActor(), venueId, floorId);
    }

    @GetMapping("/venues/{venueId}/floors/{floorId}/reconstructions/latest")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public Reconstruction latest(@PathVariable UUID venueId, @PathVariable UUID floorId) {
        return reconstructions.latestForFloor(ActorAuthentication.currentActor(), venueId, floorId);
    }

    @GetMapping("/venues/{venueId}/reconstructions/{runId}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public Reconstruction get(@PathVariable UUID venueId, @PathVariable UUID runId) {
        return reconstructions.get(ActorAuthentication.currentActor(), venueId, runId);
    }

    /** Streams the artifact bytes through the backend rather than a presigned S3 URL, so venue/org scope and
     * the contains_pii check are enforced on every read, including for a short-lived public viewer token. */
    @GetMapping("/venues/{venueId}/reconstructions/{runId}/artifacts/{kind}")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public ResponseEntity<StreamingResponseBody> artifact(@PathVariable UUID venueId, @PathVariable UUID runId, @PathVariable String kind) {
        StoredArtifact a = reconstructions.artifactBytes(ActorAuthentication.currentActor(), venueId, runId, kind);
        StreamingResponseBody body = out -> {
            try (InputStream in = derivedStore.open(a.objectKey())) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(a.contentType()))
            .contentLength(a.sizeBytes())
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .body(body);
    }
}
