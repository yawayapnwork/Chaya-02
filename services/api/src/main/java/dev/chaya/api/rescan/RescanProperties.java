package dev.chaya.api.rescan;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param minAlignmentConfidence     the control plane's own copy of the alignment quality gate; see
 *                                   RescanService and chaya_worker.region_alignment.alignment_confidence
 * @param minRegionAreaSquareMeters  a selected region smaller than this is almost certainly a mistake
 * @param maxRegionAreaSquareMeters  a region this large is not "incremental" -- use a full reconstruction
 */
@ConfigurationProperties("chaya.rescan")
public record RescanProperties(double minAlignmentConfidence, double minRegionAreaSquareMeters, double maxRegionAreaSquareMeters) {

    public RescanProperties {
        if (minAlignmentConfidence < 0 || minAlignmentConfidence > 1 || minRegionAreaSquareMeters <= 0
            || maxRegionAreaSquareMeters <= minRegionAreaSquareMeters) {
            throw new IllegalStateException("invalid chaya.rescan.* configuration");
        }
    }
}
