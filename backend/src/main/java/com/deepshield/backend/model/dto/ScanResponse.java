package com.deepshield.backend.model.dto;

import com.deepshield.backend.model.enums.ScanStatus;
import com.deepshield.backend.model.enums.Verdict;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for returning scan results to the frontend.
 * Includes all fields that the React ResultsDashboard expects.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanResponse {

    private Long id;
    private ScanStatus status;
    private Verdict verdict;
    private Double confidenceScore;
    private String explanation;
    private String heatmapBase64;
    private String inputSource;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    /** Error message if scan failed */
    private String errorMessage;

    /** Analysis breakdown — list of individual check results */
    private List<AnalysisDetail> breakdown;

    /** Per-face heatmap data for the frontend thumbnails */
    private List<FaceHeatmapDto> faceHeatmaps;

    /** Per-frame confidence scores for the timeline chart */
    private List<Double> frameScores;
}