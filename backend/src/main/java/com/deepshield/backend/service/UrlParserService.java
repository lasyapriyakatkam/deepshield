package com.deepshield.backend.service;

import com.deepshield.backend.model.enums.Platform;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Service for parsing and validating social media URLs.
 * Detects the platform (YouTube, Instagram, TikTok) from the URL
 * and validates that the URL format is correct.
 */
@Service
public class UrlParserService {

    /**
     * Detects which social media platform a URL belongs to.
     *
     * @param url the social media URL to analyze
     * @return the detected Platform enum value
     */
    public Platform detectPlatform(String url) {
        if (url == null || url.isBlank()) {
            return Platform.UNKNOWN;
        }

        String lower = url.toLowerCase();

        if (isYouTube(lower)) return Platform.YOUTUBE;
        if (isInstagram(lower)) return Platform.INSTAGRAM;
        if (isTikTok(lower)) return Platform.TIKTOK;

        return Platform.UNKNOWN;
    }

    /**
     * Validates that the given string is a properly formatted URL
     * from a supported platform.
     *
     * @param url the URL string to validate
     * @return true if the URL is valid and from a supported platform
     */
    public boolean isValidUrl(String url) {
        try {
            new URL(url);
            return detectPlatform(url) != Platform.UNKNOWN;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    /**
     * Checks if the URL is a YouTube link.
     * Covers: youtube.com/watch, youtu.be, youtube.com/shorts
     */
    private boolean isYouTube(String url) {
        return url.contains("youtube.com/watch")
                || url.contains("youtu.be/")
                || url.contains("youtube.com/shorts/")
                || url.contains("youtube.com/embed/");
    }

    /**
     * Checks if the URL is an Instagram link.
     * Covers: instagram.com/reel, instagram.com/p, instagram.com/stories
     */
    private boolean isInstagram(String url) {
        return url.contains("instagram.com/reel")
                || url.contains("instagram.com/p/")
                || url.contains("instagram.com/stories/");
    }

    /**
     * Checks if the URL is a TikTok link.
     * Covers: tiktok.com/@user/video, vm.tiktok.com (short links)
     */
    private boolean isTikTok(String url) {
        return url.contains("tiktok.com/")
                || url.contains("vm.tiktok.com/");
    }
}