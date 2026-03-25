package com.deepshield.backend.service;

import com.deepshield.backend.model.dto.AggregatedResult;
import com.deepshield.backend.model.dto.AnalysisDetail;
import com.deepshield.backend.model.dto.MetadataResult;
import com.deepshield.backend.model.dto.ScanContext;
import com.deepshield.backend.model.enums.Verdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Generates plain-English explanations from analysis results.
 *
 * Translates technical scores and flags into human-readable summaries
 * so non-technical users can understand why the system flagged content
 * as real or fake.
 */
@Service
@Slf4j
public class ExplanationGeneratorService {

    /**
     * Generates a plain-English explanation from the aggregated result.
     *
     * @param result  the aggregated analysis result
     * @param context the scan context with metadata and predictions
     * @return a human-readable explanation string
     */
    public String generate(AggregatedResult result, ScanContext context) {
        StringBuilder sb = new StringBuilder();

        // Opening statement based on verdict
        switch (result.getVerdict()) {
            case LIKELY_FAKE:
                sb.append("This content shows strong signs of manipulation or AI generation. ");
                break;
            case UNCERTAIN:
                sb.append("The analysis produced mixed results — some indicators suggest possible manipulation. ");
                break;
            case LIKELY_REAL:
                sb.append("This content appears to be authentic based on our analysis. ");
                break;
        }

        // Add details from each check
        for (AnalysisDetail detail : result.getBreakdown()) {
            if ("N/A".equals(detail.getStatus())) continue;

            switch (detail.getStatus()) {
                case "FAKE":
                    sb.append(detail.getDescription()).append(" ");
                    break;
                case "FAIL":
                    sb.append(detail.getDescription()).append(" ");
                    break;
                case "WARN":
                    sb.append(detail.getDescription()).append(" ");
                    break;
                case "PASS":
                    // Only mention passing checks for LIKELY_REAL verdicts
                    if (result.getVerdict() == Verdict.LIKELY_REAL) {
                        sb.append(detail.getDescription()).append(" ");
                    }
                    break;
            }
        }

        // Add metadata-specific details
        MetadataResult metadata = context.getMetadataResult();
        if (metadata != null) {
            if (metadata.isAiSoftwareDetected()) {
                sb.append(String.format(
                        "File metadata indicates the content was created or edited using AI software (\"%s\"). ",
                        metadata.getSoftwareUsed()));
            }
            if (metadata.getCameraModel() != null && result.getVerdict() == Verdict.LIKELY_REAL) {
                sb.append(String.format(
                        "The image appears to have been captured by %s %s. ",
                        metadata.getCameraMake() != null ? metadata.getCameraMake() : "",
                        metadata.getCameraModel()));
            }
        }

        // Confidence summary
        sb.append(String.format(
                "Overall confidence score: %.0f%%.",
                result.getConfidenceScore() * 100));

        String explanation = sb.toString().trim();
        log.info("Generated explanation: {}", explanation);
        return explanation;
    }
}