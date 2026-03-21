package com.deepshield.backend.model.enums;

/**
 * Represents the final classification verdict for a scan.
 */
public enum Verdict {
    LIKELY_REAL,    // Confidence below 0.40 — probably authentic
    UNCERTAIN,      // Confidence between 0.40 and 0.75 — inconclusive
    LIKELY_FAKE     // Confidence above 0.75 — probably manipulated
}