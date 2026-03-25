package com.deepshield.backend.model.dto;

import com.deepshield.backend.model.entity.ScanJob;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * Holds all collected data for a single scan job.
 * Passed to each AnalysisStrategy so they can access
 * whatever data they need for their specific check.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanContext {

    /** The scan job entity */
    private ScanJob job;

    /** Path to the original uploaded/downloaded file */
    private String originalFilePath;

    /** Paths to extracted keyframes (for videos) */
    private List<String> framePaths;

    /** Paths to cropped face images */
    private List<String> facePaths;

    /** Results from EXIF/metadata analysis */
    private MetadataResult metadataResult;

    /** Results from ML classification for each face */
    private List<MLPredictionResult> mlPredictions;
}