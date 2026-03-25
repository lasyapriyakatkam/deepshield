package com.deepshield.backend.service.strategy;

import com.deepshield.backend.model.dto.AnalysisDetail;
import com.deepshield.backend.model.dto.MetadataResult;
import com.deepshield.backend.model.dto.ScanContext;
import org.springframework.stereotype.Component;

/**
 * Strategy that evaluates deepfake likelihood based on EXIF/metadata analysis.
 *
 * Checks for AI software tags, missing camera info, suspicious dimensions,
 * and other metadata anomalies.
 *
 * Weighted at 20% in the final aggregation.
 */
@Component
public class MetadataAnalysisStrategy implements AnalysisStrategy {

    @Override
    public String getName() {
        return "EXIF / Metadata Check";
    }

    @Override
    public AnalysisDetail analyze(ScanContext context) {
        MetadataResult metadata = context.getMetadataResult();

        // If no metadata available
        if (metadata == null) {
            return AnalysisDetail.builder()
                    .checkName(getName())
                    .status("N/A")
                    .score(0.5)
                    .description("Metadata could not be extracted from file")
                    .build();
        }

        double score = metadata.getRiskScore();
        int flagCount = metadata.getFlags().size();

        // Determine status tag
        String status;
        String description;

        if (metadata.isAiSoftwareDetected()) {
            status = "FAKE";
            description = String.format(
                    "AI generation software detected: \"%s\". %d total flags raised.",
                    metadata.getSoftwareUsed(), flagCount);
        } else if (score > 0.3) {
            status = "WARN";
            description = String.format(
                    "%d suspicious metadata flags found. Risk score: %.0f%%",
                    flagCount, score * 100);
        } else if (flagCount > 0) {
            status = "WARN";
            description = String.format(
                    "%d minor metadata flag(s) found: %s",
                    flagCount, String.join("; ", metadata.getFlags()));
        } else {
            status = "PASS";
            description = "Metadata appears consistent with authentic capture";
        }

        return AnalysisDetail.builder()
                .checkName(getName())
                .status(status)
                .score(Math.round(score * 10000.0) / 10000.0)
                .description(description)
                .build();
    }
}