
package com.deepshield.backend.exception;

/**
 * Thrown when communication with the Python ML microservice fails.
 */
public class MLServiceException extends DeepfakeDetectionException {

    public MLServiceException(String message) {
        super(message);
    }

    public MLServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}