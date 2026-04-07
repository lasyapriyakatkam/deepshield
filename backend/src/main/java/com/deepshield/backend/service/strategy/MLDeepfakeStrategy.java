package com.deepshield.backend.service.strategy;

import com.deepshield.backend.model.dto.AnalysisDetail;
import com.deepshield.backend.model.dto.MLPredictionResult;
import com.deepshield.backend.model.dto.ScanContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Strategy that evaluates deepfake likelihood based on CNN classification results.
 *
 * Takes the average fake confidence across all detected faces
 * and produces a score from 0.0 (all faces look real) to 1.0 (all faces look fake).
 *
 * This is typically the strongest signal in the detection pipeline.
 * Weighted at 60% in the final aggregation.
 */
@Component
public class MLDeepfakeStrategy implements AnalysisStrategy {

    @Override
    public String getName() {
        return "CNN Face Classification";
    }

    @Override
    public AnalysisDetail analyze(ScanContext context) {
        List<MLPredictionResult> predictions = context.getMlPredictions();

        // If no predictions available, return uncertain
        if (predictions == null || predictions.isEmpty()) {
            return AnalysisDetail.builder()
                    .checkName(getName())
                    .status("N/A")
                    .score(0.5)
                    .description("No face images available for CNN analysis")
                    .build();
        }

        // Calculate average fake confidence across all faces
        double avgFakeConfidence = predictions.stream()
                .mapToDouble(MLPredictionResult::getFakeConfidence)
                .average()
                .orElse(0.5);

        // Find the maximum fake confidence (worst case face)
        double maxFakeConfidence = predictions.stream()
                .mapToDouble(MLPredictionResult::getFakeConfidence)
                .max()
                .orElse(0.5);

        // Count how many faces were classified as FAKE
        long fakeCount = predictions.stream()
                .filter(p -> "FAKE".equals(p.getLabel()))
                .count();

        // Use average fake confidence as the single score
        double score = avgFakeConfidence;

        // Determine status tag
        String status;
        String description;

        if (score > 0.75) {
            status = "FAKE";
            description = String.format(
                    "%d of %d faces classified as fake. Average confidence: %.1f%%",
                    fakeCount, predictions.size(), score * 100);
        } else if (score > 0.40) {
            status = "WARN";
            description = String.format(
                    "Inconclusive results across %d faces. Average confidence: %.1f%%",
                    predictions.size(), score * 100);
        } else {
            status = "PASS";
            description = String.format(
                    "All %d faces appear authentic. Average confidence: %.1f%%",
                    predictions.size(), score * 100);
        }

        return AnalysisDetail.builder()
                .checkName(getName())
                .status(status)
                .score(Math.round(score * 10000.0) / 10000.0)
                .description(description)
                .build();
    }
}