package com.deepshield.backend.model.enums;

/**
 * Represents the lifecycle status of a scan job.
 */
public enum ScanStatus {
    PENDING,        // Job created, waiting to start
    DOWNLOADING,    // Downloading video from URL
    PROCESSING,     // Extracting keyframes, detecting faces
    ANALYZING,      // Running ML inference and metadata checks
    COMPLETE,       // Analysis finished, results available
    FAILED          // Something went wrong
}