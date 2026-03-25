package com.deepshield.backend.service.strategy;

import com.deepshield.backend.model.dto.AnalysisDetail;
import com.deepshield.backend.model.dto.MLPredictionResult;
import com.deepshield.backend.model.dto.ScanContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Strategy that evaluates temporal consistency across video frames.
 *
 * For videos, if the CNN confidence scores fluctuate wildly between frames,
 * it suggests manipulation was applied to specific portions of the video.
 * Real videos tend to have consistent scores across frames.
 *
 * For single images, this check is not applicable.
 *
 * Weighted at 20% in the final aggregation.
 */
@Component
public class TemporalConsistencyStrategy implements AnalysisStrategy {

    /** Threshold for standard deviation to consider scores inconsistent */
    private static final double HIGH_VARIANCE_THRESHOLD = 0.25;
    private static final double MEDIUM_VARIANCE_THRESHOLD = 0.15;

    @Override
    public String getName() {
        return "Temporal Consistency";
    }

    @Override
    public AnalysisDetail analyze(ScanContext context) {
        List<MLPredictionResult> predictions = context.getMlPredictions();

        // Not applicable for single images
        if (predictions == null || predictions.size() < 3) {
            return AnalysisDetail.builder()
                    .checkName(getName())
                    .status("N/A")
                    .score(0.0)
                    .description("Not enough frames for temporal analysis (single image or short video)")
                    .build();
        }

        // Calculate mean fake confidence
        double mean = predictions.stream()
                .mapToDouble(MLPredictionResult::getFakeConfidence)
                .average()
                .orElse(0.5);

        // Calculate standard deviation
        double variance = predictions.stream()
                .mapToDouble(p -> Math.pow(p.getFakeConfidence() - mean, 2))
                .average()
                .orElse(0.0);
        double stdDev = Math.sqrt(variance);

        // Find spikes — frames where confidence jumps significantly above mean
        long spikeCount = predictions.stream()
                .filter(p -> p.getFakeConfidence() > mean + (2 * stdDev) && p.getFakeConfidence() > 0.5)
                .count();

        // Determine status and score
        String status;
        String description;
        double score;

        if (stdDev > HIGH_VARIANCE_THRESHOLD) {
            status = "FAIL";
            score = Math.min(1.0, stdDev * 2 + (spikeCount * 0.1));
            description = String.format(
                    "High inconsistency detected across %d frames (std dev: %.3f). %d suspicious spikes found.",
                    predictions.size(), stdDev, spikeCount);
        } else if (stdDev > MEDIUM_VARIANCE_THRESHOLD || spikeCount > 0) {
            status = "WARN";
            score = stdDev + (spikeCount * 0.05);
            description = String.format(
                    "Moderate variation across %d frames (std dev: %.3f). %d potential spikes.",
                    predictions.size(), stdDev, spikeCount);
        } else {
            status = "PASS";
            score = stdDev;
            description = String.format(
                    "Consistent scores across %d frames (std dev: %.3f). No suspicious spikes.",
                    predictions.size(), stdDev);
        }

        return AnalysisDetail.builder()
                .checkName(getName())
                .status(status)
                .score(Math.round(Math.min(1.0, score) * 10000.0) / 10000.0)
                .description(description)
                .build();
    }
}