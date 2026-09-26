package dev.chaya.api.rescan;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param minAlignmentConfidence     the control plane's own copy of the alignment quality gate; see
 *                                   RescanService and chaya_worker.region_alignment.alignment_confidence
 * @param minRegionAreaSquareMeters  a selected region smaller than this is almost certainly a mistake
 * @param maxRegionAreaSquareMeters  a region this large is not "incremental" -- use a full reconstruction
 * @param maxScaleCorrection         the control plane's own bound on |alignment scale - 1|: beyond it the re-scan's metric
 *                                   calibration and the venue's disagree, and the alignment is rejected (default 0.1)
 */
@ConfigurationProperties("chaya.rescan")
public record RescanProperties(double minAlignmentConfidence, double minRegionAreaSquareMeters, double maxRegionAreaSquareMeters,
                               double maxScaleCorrection) {

    public RescanProperties {
        if (maxScaleCorrection == 0) {
            maxScaleCorrection = 0.1;
        }
        if (minAlignmentConfidence < 0 || minAlignmentConfidence > 1 || minRegionAreaSquareMeters <= 0
            || maxRegionAreaSquareMeters <= minRegionAreaSquareMeters || maxScaleCorrection <= 0 || maxScaleCorrection >= 1) {
            throw new IllegalStateException("invalid chaya.rescan.* configuration");
        }
    }
}
