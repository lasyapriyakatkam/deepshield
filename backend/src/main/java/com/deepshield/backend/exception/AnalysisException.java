package com.deepshield.backend.exception;

/**
 * Thrown when the analysis pipeline encounters an error.
 */
public class AnalysisException extends DeepfakeDetectionException {

    public AnalysisException(String message) {
        super(message);
    }

    public AnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}