package com.deepshield.backend.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * DTO for URL-based scan requests from the frontend.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScanRequest {

    /** The social media URL to analyze (YouTube, Instagram, TikTok) */
    private String url;
}