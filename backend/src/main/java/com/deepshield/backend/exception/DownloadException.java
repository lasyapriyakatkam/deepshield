package com.deepshield.backend.exception;

/**
 * Thrown when video/image download from a URL fails.
 */
public class DownloadException extends DeepfakeDetectionException {

    public DownloadException(String message) {
        super(message);
    }

    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
