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
    private List<AnalysisDetail> breakdown;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}