package com.deepshield.backend.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the results of EXIF/metadata analysis for a file.
 * Contains a risk score, individual flags, and extracted metadata fields.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetadataResult {

    /** Overall risk score from 0.0 (safe) to 1.0 (highly suspicious) */
    @Builder.Default
    private Double riskScore = 0.0;

    /** List of human-readable flags describing suspicious findings */
    @Builder.Default
    private List<String> flags = new ArrayList<>();

    /** Software field extracted from metadata (e.g., "Adobe Photoshop", "DALL-E") */
    private String softwareUsed;

    /** Camera make extracted from metadata (e.g., "Apple", "Canon") */
    private String cameraMake;

    /** Camera model extracted from metadata (e.g., "iPhone 15 Pro", "EOS R5") */
    private String cameraModel;

    /** Original creation date from metadata */
    private String dateCreated;

    /** Last modified date from metadata */
    private String dateModified;

    /** Image width in pixels */
    private Integer imageWidth;

    /** Image height in pixels */
    private Integer imageHeight;

    /** Whether AI-related software was detected in metadata */
    @Builder.Default
    private boolean aiSoftwareDetected = false;

    /** Whether camera information is completely missing */
    @Builder.Default
    private boolean cameraInfoMissing = false;

    /** Whether the image has suspicious dimensions typical of AI generation */
    @Builder.Default
    private boolean suspiciousDimensions = false;
}