package com.deepshield.backend.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a heatmap for a detected face in a frame.
 * The backend should prefer exposing a URL in `heatmapUrl` and
 * may optionally include a `heatmapBase64` string for local/dev use.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FaceHeatmapDto {
    private Integer faceIndex;
    private Integer frameIndex;
    /** Milliseconds timestamp of the frame */
    private Long timestamp;
    /** Preferred: public or served URL to the heatmap image */
    private String heatmapUrl;
    /** Optional: inline base64 PNG for local/dev convenience (may be large) */
    private String heatmapBase64;
}
