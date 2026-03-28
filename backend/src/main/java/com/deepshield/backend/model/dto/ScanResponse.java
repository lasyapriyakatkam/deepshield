package com.deepshield.backend.model.dto;

import com.deepshield.backend.model.enums.ScanStatus;
import com.deepshield.backend.model.enums.Verdict;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Collections;

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
    /** Per-keyframe confidence scores (0.0-1.0) corresponding to `frameTimestamps` */
    private List<Double> frameScores;
    /** Millisecond timestamps for each keyframe */
    private List<Long> frameTimestamps;
    /** URLs or inline base64s for per-face heatmaps */
    private List<FaceHeatmapDto> faceHeatmaps;
    /** Optional publicly-accessible URL to an overall heatmap image (preferred for large images) */
    private String overallHeatmapUrl;
    private List<AnalysisDetail> breakdown;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public List<Double> getFrameScores() {
        return frameScores == null ? Collections.emptyList() : frameScores;
    }

    public List<Long> getFrameTimestamps() {
        return frameTimestamps == null ? Collections.emptyList() : frameTimestamps;
    }

    public List<FaceHeatmapDto> getFaceHeatmaps() {
        return faceHeatmaps == null ? Collections.emptyList() : faceHeatmaps;
    }
}