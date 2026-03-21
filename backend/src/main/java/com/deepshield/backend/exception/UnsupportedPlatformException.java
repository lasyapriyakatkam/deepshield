package com.deepshield.backend.exception;

/**
 * Thrown when a user submits a URL from an unsupported platform.
 */
public class UnsupportedPlatformException extends DeepfakeDetectionException {

    public UnsupportedPlatformException(String url) {
        super("Unsupported or unrecognized platform for URL: " + url);
    }
}
