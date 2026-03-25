package com.deepshield.backend.model.dto;

import com.deepshield.backend.model.enums.Verdict;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * The final aggregated result of all analysis strategies.
 * Contains the overall verdict, confidence score, and breakdown details.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AggregatedResult {

    /** Final classification verdict */
    private Verdict verdict;

    /** Combined confidence score (0.0 to 1.0) */
    private Double confidenceScore;

    /** Breakdown of individual analysis checks */
    private List<AnalysisDetail> breakdown;

    /** Plain-English explanation of the result */
    private String explanation;
}