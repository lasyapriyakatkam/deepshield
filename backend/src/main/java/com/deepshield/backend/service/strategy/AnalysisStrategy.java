package com.deepshield.backend.service.strategy;

import com.deepshield.backend.model.dto.AnalysisDetail;
import com.deepshield.backend.model.dto.ScanContext;

/**
 * Strategy interface for pluggable analysis modules.
 *
 * Each implementation represents a different type of deepfake check.
 * The Strategy Pattern allows us to easily add, remove, or swap
 * analysis methods without modifying the aggregation logic.
 *
 * This is a key OOP design pattern demonstrated in this project.
 */
public interface AnalysisStrategy {

    /**
     * Returns the human-readable name of this analysis check.
     * Example: "CNN Face Classification", "EXIF Metadata Check"
     */
    String getName();

    /**
     * Runs this analysis check on the given scan context.
     *
     * @param context contains all data needed for analysis
     *                (file paths, metadata results, ML predictions, etc.)
     * @return an AnalysisDetail with the check name, status, score, and description
     */
    AnalysisDetail analyze(ScanContext context);
}