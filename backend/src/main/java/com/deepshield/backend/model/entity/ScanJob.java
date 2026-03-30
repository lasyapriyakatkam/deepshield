package com.deepshield.backend.model.entity;

import com.deepshield.backend.model.enums.InputType;
import com.deepshield.backend.model.enums.ScanStatus;
import com.deepshield.backend.model.enums.Verdict;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * JPA entity representing a single deepfake scan job.
 * Tracks the full lifecycle from submission to result.
 */
@Entity
@Table(name = "scan_jobs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScanJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Type of input: URL, IMAGE, or VIDEO */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InputType inputType;

    /** Original input source — the URL string or uploaded filename */
    @Column(nullable = false)
    private String inputSource;

    /** Local file path where the downloaded/uploaded file is stored */
    private String localFilePath;

    /** Current status of this scan job */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanStatus status;

    /** Final verdict after analysis */
    @Enumerated(EnumType.STRING)
    private Verdict verdict;

    /** Overall confidence score (0.0 to 1.0) */
    private Double confidenceScore;

    /** Plain-English explanation of the result */
    @Column(length = 2000)
    private String explanation;

    /** Base64-encoded Grad-CAM heatmap image */
    @Column(columnDefinition = "CLOB")
    private String heatmapBase64;

    /** JSON-encoded per-keyframe confidence scores (array of doubles) */
    @Column(columnDefinition = "CLOB")
    private String frameScoresJson;

    /** JSON-encoded per-keyframe timestamps (array of longs, ms) */
    @Column(columnDefinition = "CLOB")
    private String frameTimestampsJson;

    /** JSON-encoded list of face heatmap descriptors (contains heatmapUrl, faceIndex, frameIndex, timestamp) */
    @Column(columnDefinition = "CLOB")
    private String faceHeatmapsJson;

    /** JSON-encoded analysis breakdown (list of AnalysisDetail entries) */
    @Column(columnDefinition = "CLOB")
    private String breakdownJson;

    /** Timestamp when the scan was submitted */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** Timestamp when the scan finished */
    private LocalDateTime completedAt;

    /**
     * Auto-set createdAt and status before persisting.
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = ScanStatus.PENDING;
        }
    }
}