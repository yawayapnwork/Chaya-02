package dev.chaya.api.rescan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RescanDtos {

    private RescanDtos() {}

    /** A simple polygon in canonical venue metres (x, y horizontal; docs/coordinate-frames.md): {@code points} are
     * [x, y] pairs, at least 3, in order. */
    public record RegionGeometry(@NotNull @Size(min = 3) List<@Size(min = 2, max = 2) List<Double>> points) {}

    /** Steps 1-2 of the incremental re-scan flow: select an existing (FINALIZED) venue version and the
     * changed region to recapture. */
    public record RescanRequest(@NotNull UUID parentVersionId, @NotNull @Valid RegionGeometry region, Map<String, Object> device) {}

    /** What the operator gets back to drive the rest of the flow: upload media to {@code captureId}
     * exactly like any other capture (docs/rescan.md), then start processing as usual -- the control
     * plane recognizes this capture as a re-scan from its recorded parent version and region.
     * {@code scanVersionId} is null here (the DRAFT ScanVersion is only created once processing actually
     * starts) and appears in the processing status response once it does. */
    public record RescanInitiated(UUID captureId, UUID scanVersionId, UUID parentVersionId,
                                  boolean navigationRebuildRequired, double regionAreaSquareMeters) {}

    /** docs/rescan.md "VERSIONING": every field here is written once and never changes after that --
     * region/alignment/processing fields are null for a version that was never an incremental re-scan
     * (e.g. a bootstrapped initial version, see RescanService#finalizeCurrent). */
    public record ScanVersionView(UUID id, UUID floorId, UUID scanId, int versionNumber, UUID parentVersionId,
                                  String status, Map<String, Object> regionGeometry, String alignmentMethod,
                                  Double alignmentConfidence, Double alignmentResidualM, List<String> changedArtifactKinds,
                                  Map<String, Object> processingConfig, Instant finalizedAt, Instant createdAt,
                                  Map<String, Object> alignmentReport, Map<String, Object> spliceReport, String createdBy,
                                  Instant rejectedAt) {}
}
