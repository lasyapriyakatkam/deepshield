package com.deepshield.backend.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * DTO representing a heatmap for a detected face.
 * Includes both URL and base64 options for frontend flexibility.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaceHeatmapDto {

    private Integer faceIndex;
    private Integer frameIndex;

    /** Milliseconds timestamp of the frame */
    private Long timestamp;

    /** Preferred: public or served URL to the heatmap image */
    private String heatmapUrl;

    /** Inline base64 PNG for the heatmap overlay */
    private String heatmapBase64;

    /** Fake confidence score for this face */
    private Double fakeConfidence;

    /** Classification label (FAKE/REAL) */
    private String label;
}