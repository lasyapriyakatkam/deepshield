package com.deepshield.backend.exception;

/**
 * Base exception for all DeepShield application errors.
 */
public class DeepfakeDetectionException extends RuntimeException {

    public DeepfakeDetectionException(String message) {
        super(message);
    }

    public DeepfakeDetectionException(String message, Throwable cause) {
        super(message, cause);
    }
}