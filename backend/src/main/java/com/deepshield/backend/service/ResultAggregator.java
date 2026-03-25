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
     */
    private double calculateWeightedScore(List<AnalysisDetail> breakdown) {
        double totalScore = 0.0;
        double totalWeight = 0.0;

        for (AnalysisDetail detail : breakdown) {
            // Skip N/A results (don't count them in the weighted average)
            if ("N/A".equals(detail.getStatus())) continue;

            double weight = getWeight(detail.getCheckName());
            totalScore += detail.getScore() * weight;
            totalWeight += weight;
        }

        // Normalize by actual weight used (in case some checks were N/A)
        if (totalWeight > 0) {
            return totalScore / totalWeight;
        }
        return 0.5; // Default to uncertain if no checks ran
    }

    /**
     * Returns the weight for a given check name.
     */
    private double getWeight(String checkName) {
        return switch (checkName) {
            case "CNN Face Classification" -> CNN_WEIGHT;
            case "EXIF / Metadata Check" -> METADATA_WEIGHT;
            case "Temporal Consistency" -> TEMPORAL_WEIGHT;
            default -> 0.10; // Default weight for unknown strategies
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