package com.deepshield.backend.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Holds the result of ML deepfake inference for a single face image.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MLPredictionResult {

    /** Probability that the image is a deepfake (0.0 to 1.0) */
    private Double fakeConfidence;

    /** Probability that the image is real (0.0 to 1.0) */
    private Double realConfidence;

    /** Classification label: "FAKE" or "REAL" */
    private String label;

    /** Base64-encoded Grad-CAM heatmap PNG (nullable) */
    private String heatmapBase64;

    /** Path to the source face image that was analyzed */
    private String sourceImagePath;
}