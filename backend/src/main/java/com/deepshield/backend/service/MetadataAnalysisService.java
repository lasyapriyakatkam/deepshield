package com.deepshield.backend.service;

import com.deepshield.backend.exception.AnalysisException;
import com.deepshield.backend.model.dto.MetadataResult;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.Directory;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Service for extracting and analyzing EXIF/metadata from images and videos.
 *
 * Checks for signs of AI generation or manipulation including:
 * - AI tool names in the software field
 * - Missing camera information
 * - Suspicious image dimensions common to AI generators
 * - Timestamp inconsistencies
 *
 * Returns a MetadataResult with a risk score (0.0 - 1.0) and detailed flags.
 */
@Service
@Slf4j
public class MetadataAnalysisService {

    /**
     * Known AI generation and manipulation tool names.
     * If any of these appear in the software/creator metadata,
     * it's a strong indicator the image was AI-generated or heavily edited.
     */
    private static final List<String> AI_TOOLS = Arrays.asList(
            "dall-e", "dall·e", "midjourney", "stable diffusion",
            "runway", "firefly", "adobe firefly",
            "synthesia", "deepfacelab", "faceswap", "faceapp",
            "artbreeder", "nightcafe", "leonardo ai", "leonardo.ai",
            "bing image creator", "copilot", "chatgpt",
            "comfyui", "automatic1111", "invoke ai",
            "topaz", "remini", "lensa", "wombo", "dream"
    );

    /**
     * Known image/video editing software names.
     * These aren't necessarily AI tools but indicate the file has been edited.
     */
    private static final List<String> EDITING_TOOLS = Arrays.asList(
            "photoshop", "lightroom", "gimp", "affinity",
            "after effects", "premiere", "davinci resolve",
            "final cut", "capcut", "canva", "figma",
            "snapseed", "vsco", "picsart"
    );

    /**
     * Common AI-generated image dimensions.
     * Many AI generators produce images at these specific sizes.
     */
    private static final List<String> AI_DIMENSIONS = Arrays.asList(
            "256x256", "512x512", "768x768", "1024x1024",
            "1024x576", "576x1024",   // 16:9 and 9:16 at common AI sizes
            "1536x1536", "2048x2048",
            "1344x768", "768x1344",   // SDXL common sizes
            "1152x896", "896x1152"    // SDXL common sizes
    );

    /**
     * Analyzes the metadata of a file and returns a risk assessment.
     *
     * @param filePath absolute path to the image or video file
     * @return MetadataResult containing risk score and detailed findings
     */
    public MetadataResult analyze(String filePath) {
        File file = new File(filePath);
        List<String> flags = new ArrayList<>();
        double riskScore = 0.0;

        // Initialize result with defaults
        MetadataResult result = MetadataResult.builder()
                .flags(flags)
                .build();

        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file);

            // Extract basic fields
            extractBasicFields(metadata, result);

            // Log all metadata for debugging
            logAllMetadata(metadata);

            // === CHECK 1: AI Software Detection ===
            riskScore += checkForAISoftware(result, flags);

            // === CHECK 2: Editing Software Detection ===
            riskScore += checkForEditingSoftware(result, flags);

            // === CHECK 3: Missing Camera Information ===
            riskScore += checkMissingCameraInfo(result, flags);

            // === CHECK 4: Suspicious Dimensions ===
            riskScore += checkSuspiciousDimensions(result, flags);

            // === CHECK 5: Timestamp Analysis ===
            riskScore += checkTimestamps(result, flags);

            // === CHECK 6: Minimal Metadata (stripped) ===
            riskScore += checkMinimalMetadata(metadata, flags);

        } catch (Exception e) {
            log.warn("Could not read metadata from file: {} — {}", filePath, e.getMessage());
            flags.add("METADATA_UNREADABLE: Could not extract metadata from file");
            riskScore += 0.1;
        }

        // Cap risk score at 1.0
        result.setRiskScore(Math.min(1.0, riskScore));
        result.setFlags(flags);

        log.info("Metadata analysis complete — Risk score: {}, Flags: {}",
                String.format("%.2f", result.getRiskScore()), flags.size());

        return result;
    }

    /**
     * Extracts basic metadata fields (software, camera, dimensions, dates).
     */
    private void extractBasicFields(Metadata metadata, MetadataResult result) {
        // Try to get EXIF IFD0 directory (contains camera make, model, software)
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (ifd0 != null) {
            result.setSoftwareUsed(ifd0.getString(ExifIFD0Directory.TAG_SOFTWARE));
            result.setCameraMake(ifd0.getString(ExifIFD0Directory.TAG_MAKE));
            result.setCameraModel(ifd0.getString(ExifIFD0Directory.TAG_MODEL));
            result.setDateCreated(ifd0.getString(ExifIFD0Directory.TAG_DATETIME));
        }

        // Try to get EXIF SubIFD directory (contains original date)
        ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (subIfd != null) {
            String originalDate = subIfd.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
            if (originalDate != null && result.getDateCreated() == null) {
                result.setDateCreated(originalDate);
            }
        }

        // Try to get image dimensions from JPEG directory
        JpegDirectory jpeg = metadata.getFirstDirectoryOfType(JpegDirectory.class);
        if (jpeg != null) {
            try {
                result.setImageWidth(jpeg.getImageWidth());
                result.setImageHeight(jpeg.getImageHeight());
            } catch (Exception e) {
                log.debug("Could not read JPEG dimensions");
            }
        }

        // Try PNG directory if JPEG didn't work
        if (result.getImageWidth() == null) {
            PngDirectory png = metadata.getFirstDirectoryOfType(PngDirectory.class);
            if (png != null) {
                try {
                    result.setImageWidth(png.getInt(PngDirectory.TAG_IMAGE_WIDTH));
                    result.setImageHeight(png.getInt(PngDirectory.TAG_IMAGE_HEIGHT));
                } catch (Exception e) {
                    log.debug("Could not read PNG dimensions");
                }
            }
        }
    }

    /**
     * CHECK 1: Looks for known AI tool names in the software field.
     * This is the strongest signal — if the file says it was made by DALL-E, it's AI.
     */
    private double checkForAISoftware(MetadataResult result, List<String> flags) {
        String software = result.getSoftwareUsed();
        if (software == null) return 0.0;

        String lowerSoftware = software.toLowerCase();
        for (String aiTool : AI_TOOLS) {
            if (lowerSoftware.contains(aiTool)) {
                result.setAiSoftwareDetected(true);
                flags.add("AI_SOFTWARE_DETECTED: File metadata contains AI tool — \"" + software + "\"");
                log.info("AI software detected in metadata: {}", software);
                return 0.4;  // High risk contribution
            }
        }
        return 0.0;
    }

    /**
     * CHECK 2: Looks for known editing software in the software field.
     * Editing doesn't mean fake, but it's worth flagging.
     */
    private double checkForEditingSoftware(MetadataResult result, List<String> flags) {
        String software = result.getSoftwareUsed();
        if (software == null || result.isAiSoftwareDetected()) return 0.0;

        String lowerSoftware = software.toLowerCase();
        for (String editor : EDITING_TOOLS) {
            if (lowerSoftware.contains(editor)) {
                flags.add("EDITING_SOFTWARE: File was edited with — \"" + software + "\"");
                log.info("Editing software detected: {}", software);
                return 0.1;
            }
        }
        return 0.0;
    }

    /**
     * CHECK 3: Checks if camera make/model information is missing.
     * Real photos from phones/cameras almost always have this.
     * AI-generated images never do.
     */
    private double checkMissingCameraInfo(MetadataResult result, List<String> flags) {
        if (result.getCameraMake() == null && result.getCameraModel() == null) {
            result.setCameraInfoMissing(true);
            flags.add("NO_CAMERA_INFO: No camera make or model found in metadata");
            return 0.2;
        }
        return 0.0;
    }

    /**
     * CHECK 4: Checks if image dimensions match common AI generation sizes.
     * AI tools often produce images at exact square or standard sizes.
     */
    private double checkSuspiciousDimensions(MetadataResult result, List<String> flags) {
        if (result.getImageWidth() == null || result.getImageHeight() == null) return 0.0;

        String dims = result.getImageWidth() + "x" + result.getImageHeight();
        if (AI_DIMENSIONS.contains(dims)) {
            result.setSuspiciousDimensions(true);
            flags.add("SUSPICIOUS_DIMENSIONS: Image size " + dims + " matches common AI generation size");
            return 0.15;
        }

        // Also check if perfectly square (common for AI but rare for cameras)
        if (result.getImageWidth().equals(result.getImageHeight()) && result.getImageWidth() >= 512) {
            result.setSuspiciousDimensions(true);
            flags.add("SQUARE_IMAGE: Perfectly square image (" + dims + ") — uncommon for camera photos");
            return 0.1;
        }

        return 0.0;
    }

    /**
     * CHECK 5: Checks for timestamp inconsistencies.
     * If creation date is missing but modification date exists, that's suspicious.
     */
    private double checkTimestamps(MetadataResult result, List<String> flags) {
        if (result.getDateCreated() == null && result.getDateModified() != null) {
            flags.add("TIMESTAMP_MISMATCH: No creation date but modification date exists");
            return 0.1;
        }
        if (result.getDateCreated() == null) {
            flags.add("NO_TIMESTAMPS: No creation or modification dates found");
            return 0.05;
        }
        return 0.0;
    }

    /**
     * CHECK 6: Checks if the file has very minimal metadata.
     * Real camera photos have dozens of metadata tags.
     * AI-generated or stripped images often have very few.
     */
    private double checkMinimalMetadata(Metadata metadata, List<String> flags) {
        int totalTags = 0;
        for (Directory dir : metadata.getDirectories()) {
            totalTags += dir.getTagCount();
        }

        log.debug("Total metadata tags found: {}", totalTags);

        if (totalTags < 5) {
            flags.add("MINIMAL_METADATA: File contains very few metadata tags (" + totalTags + ") — possibly stripped or AI-generated");
            return 0.15;
        } else if (totalTags < 15) {
            flags.add("LOW_METADATA: File has limited metadata (" + totalTags + " tags)");
            return 0.05;
        }
        return 0.0;
    }

    /**
     * Logs all metadata tags for debugging purposes.
     */
    private void logAllMetadata(Metadata metadata) {
        for (Directory dir : metadata.getDirectories()) {
            log.debug("--- Directory: {} ---", dir.getName());
            for (Tag tag : dir.getTags()) {
                log.debug("  {} = {}", tag.getTagName(), tag.getDescription());
            }
        }
    }
}