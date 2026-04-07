package com.deepshield.backend.service;

import com.deepshield.backend.model.dto.AggregatedResult;
import com.deepshield.backend.model.dto.AnalysisDetail;
import com.deepshield.backend.model.dto.ScanContext;
import com.deepshield.backend.model.enums.Verdict;
import com.deepshield.backend.service.strategy.AnalysisStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates results from all analysis strategies into a final verdict.
 *
 * Uses weighted scoring to combine CNN classification (60%),
 * metadata analysis (20%), and temporal consistency (20%)
 * into a single confidence score and verdict.
 *
 * Verdict thresholds:
 *   > 0.75 → LIKELY_FAKE
 *   > 0.40 → UNCERTAIN
 *   ≤ 0.40 → LIKELY_REAL
 *
 * Spring automatically injects all @Component classes that implement
 * AnalysisStrategy — this is the Strategy Pattern in action.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResultAggregator {

    /** All registered analysis strategies (auto-injected by Spring) */
    private final List<AnalysisStrategy> strategies;

    /** Weight assignments for each strategy */
    private static final double CNN_WEIGHT = 0.60;
    private static final double METADATA_WEIGHT = 0.20;
    private static final double TEMPORAL_WEIGHT = 0.20;

    /** Verdict thresholds */
    private static final double FAKE_THRESHOLD = 0.75;
    private static final double UNCERTAIN_THRESHOLD = 0.40;

    /**
     * Runs all analysis strategies and aggregates into a final result.
     *
     * @param context the scan context with all collected data
     * @return AggregatedResult with verdict, confidence, and breakdown
     */
    public AggregatedResult aggregate(ScanContext context) {
        List<AnalysisDetail> breakdown = new ArrayList<>();

        // Run each strategy and collect results
        for (AnalysisStrategy strategy : strategies) {
            try {
                AnalysisDetail detail = strategy.analyze(context);
                breakdown.add(detail);
                log.info("Strategy '{}' → status: {}, score: {}",
                        detail.getCheckName(), detail.getStatus(), detail.getScore());
            } catch (Exception e) {
                log.error("Strategy '{}' failed: {}", strategy.getName(), e.getMessage());
                breakdown.add(AnalysisDetail.builder()
                        .checkName(strategy.getName())
                        .status("ERROR")
                        .score(0.5)
                        .description("Analysis failed: " + e.getMessage())
                        .build());
            }
        }

        // Calculate weighted score
        double weightedScore = calculateWeightedScore(breakdown);

        // Determine verdict
        Verdict verdict = determineVerdict(weightedScore);

        log.info("Aggregation complete — Score: {}, Verdict: {}",
                String.format("%.4f", weightedScore), verdict);

        return AggregatedResult.builder()
                .verdict(verdict)
                .confidenceScore(Math.round(weightedScore * 10000.0) / 10000.0)
                .breakdown(breakdown)
                .build();
    }

    /**
     * Calculates the weighted score from all analysis details.
     * Maps each check name to its weight.
     * Applies a boost when multiple signals agree on suspicion.
     */
    private double calculateWeightedScore(List<AnalysisDetail> breakdown) {
        double totalScore = 0.0;
        double totalWeight = 0.0;

        for (AnalysisDetail detail : breakdown) {
            // Skip N/A results (don't count them in the weighted average)
            if ("N/A".equals(detail.getStatus())) continue;

            double weight = getWeight(detail.getCheckName(), breakdown);
            totalScore += detail.getScore() * weight;
            totalWeight += weight;
        }

        // Normalize by actual weight used
        double weightedScore = 0.5;
        if (totalWeight > 0) {
            weightedScore = totalScore / totalWeight;
        }

        return weightedScore;
    }

    /**
     * Returns the weight for a given check name.
     * Adjusts temporal weight based on CNN result — if CNN says PASS,
     * temporal variance is likely natural movement, not manipulation.
     */
    private double getWeight(String checkName, List<AnalysisDetail> allDetails) {
        if ("Temporal Consistency".equals(checkName)) {
            // Check if CNN passed — if so, temporal variance is probably harmless
            boolean cnnPassed = allDetails.stream()
                    .anyMatch(d -> "CNN Face Classification".equals(d.getCheckName())
                            && "PASS".equals(d.getStatus()));
            if (cnnPassed) {
                return 0.05; // Heavily reduce temporal weight when CNN says real
            }
            return TEMPORAL_WEIGHT;
        }
        return switch (checkName) {
            case "CNN Face Classification" -> CNN_WEIGHT;
            case "EXIF / Metadata Check" -> METADATA_WEIGHT;
            default -> 0.10;
        };
    }

    /**
     * Determines the final verdict based on the weighted score.
     */
    private Verdict determineVerdict(double score) {
        if (score > FAKE_THRESHOLD) return Verdict.LIKELY_FAKE;
        if (score > UNCERTAIN_THRESHOLD) return Verdict.UNCERTAIN;
        return Verdict.LIKELY_REAL;
    }
}