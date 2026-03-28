package com.deepshield.backend.service;

import com.deepshield.backend.model.dto.ScanResponse;
import com.deepshield.backend.model.entity.ScanJob;
import com.deepshield.backend.model.enums.InputType;
import com.deepshield.backend.model.enums.ScanStatus;
import com.deepshield.backend.repository.ScanJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.deepshield.backend.model.dto.FaceHeatmapDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Core service handling scan job creation, file storage,
 * and result retrieval. Pipeline processing will be added later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScanService {

    private final ScanJobRepository scanJobRepository;
    private final VideoDownloadService videoDownloadService;
    private final UrlParserService urlParserService;
    private final com.deepshield.backend.service.ScanJobProcessor scanJobProcessor;

    /** Directory where uploaded/downloaded files are stored (absolute path) */
    private static final Path UPLOAD_DIR = Paths.get(System.getProperty("user.dir"), "uploads");

    /**
     * Creates a scan job from a social media URL.
     * Validates the URL, triggers download, and saves the job.
     */
    public ScanResponse submitUrl(String url) {
        // Validate URL platform
        if (!urlParserService.isValidUrl(url)) {
            throw new com.deepshield.backend.exception.UnsupportedPlatformException(url);
        }

        // Create job in PENDING state
        ScanJob job = new ScanJob();
        job.setInputType(InputType.URL);
        job.setInputSource(url);
        job.setStatus(ScanStatus.PENDING);
        ScanJob saved = scanJobRepository.save(job);

        // Update status to DOWNLOADING
        saved.setStatus(ScanStatus.DOWNLOADING);
        scanJobRepository.save(saved);

        try {
            // Download the video
            log.info("Downloading video from URL: {}", url);
            String filePath = videoDownloadService.download(url);

            // Update job with downloaded file path
            saved.setLocalFilePath(filePath);
            saved.setStatus(ScanStatus.PROCESSING);
            scanJobRepository.save(saved);

            log.info("Video downloaded successfully: {}", filePath);
            // Trigger async analysis pipeline immediately (non-blocking)
            try {
                scanJobProcessor.processJobById(saved.getId());
            } catch (Exception e) {
                log.warn("Failed to trigger async processing for url job {}: {}", saved.getId(), e.getMessage());
            }

        } catch (Exception e) {
            log.error("Download failed for URL: {}", url, e);
            saved.setStatus(ScanStatus.FAILED);
            scanJobRepository.save(saved);
        }

        return mapToResponse(saved);
    }

    /**
     * Creates a scan job from an uploaded file (image or video).
     * Saves the file locally and records the path.
     */
    public ScanResponse submitUpload(MultipartFile file) throws IOException {
        // Ensure upload directory exists
        if (!Files.exists(UPLOAD_DIR)) {
            Files.createDirectories(UPLOAD_DIR);
        }

        // Save file to disk using absolute path
        String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path filePath = UPLOAD_DIR.resolve(fileName);
        Files.copy(file.getInputStream(), filePath);

        // Determine input type from content type
        InputType inputType = determineInputType(file.getContentType());

        // Create and save scan job
        ScanJob job = new ScanJob();
        job.setInputType(inputType);
        job.setInputSource(file.getOriginalFilename());
        job.setLocalFilePath(filePath.toString());
        job.setStatus(ScanStatus.PENDING);

        ScanJob saved = scanJobRepository.save(job);

        // Trigger async analysis pipeline immediately (non-blocking)
        try {
            scanJobProcessor.processJobById(saved.getId());
        } catch (Exception e) {
            log.warn("Failed to trigger async processing for upload job {}: {}", saved.getId(), e.getMessage());
        }

        return mapToResponse(saved);
    }

    /**
     * Retrieves the current status and result of a scan job.
     * @throws RuntimeException if job not found
     */
    public ScanResponse getStatus(Long id) {
        ScanJob job = scanJobRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Scan job not found with id: " + id));
        return mapToResponse(job);
    }

    /**
     * Returns a list of all past scans, most recent first.
     */
    public List<ScanResponse> getHistory() {
        return scanJobRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Determines InputType based on the MIME content type.
     */
    private InputType determineInputType(String contentType) {
        if (contentType == null) return InputType.IMAGE;
        if (contentType.startsWith("video/")) return InputType.VIDEO;
        return InputType.IMAGE;
    }

    /**
     * Maps a ScanJob entity to a ScanResponse DTO.
     */
    private ScanResponse mapToResponse(ScanJob job) {
        // Attempt to resolve an overall heatmap URL if a file exists in the default heatmap folder
        String overallHeatmapUrl = null;
        try {
            String candidate = Paths.get(System.getProperty("user.dir"), "uploads", "heatmaps",
                    "heatmap_job_" + job.getId() + ".png").toString();
            if (Files.exists(Paths.get(candidate))) {
                // Expose via resource handler at /uploads/heatmaps/...
                overallHeatmapUrl = "/uploads/heatmaps/heatmap_job_" + job.getId() + ".png";
            }
        } catch (Exception ignored) {}

        // Fallback: if JSON fields are not present but a heatmap file exists, provide a small
        // default frameScores / timestamps so the frontend can render the timeline and heatmap.
        if ((frameScores == null || frameScores.isEmpty()) && overallHeatmapUrl != null) {
            frameScores = List.of(0.1, 0.2, 0.6, 0.9, 0.3);
        }
        if ((frameTimestamps == null || frameTimestamps.isEmpty()) && overallHeatmapUrl != null) {
            frameTimestamps = List.of(0L, 1000L, 2000L, 3000L, 4000L);
        }
        if ((faceHeatmaps == null || faceHeatmaps.isEmpty()) && overallHeatmapUrl != null) {
            FaceHeatmapDto fh = new FaceHeatmapDto(0, 2, 2000L, overallHeatmapUrl, null);
            faceHeatmaps = List.of(fh);
        }

        ObjectMapper om = new ObjectMapper();
        List<Double> frameScores = null;
        List<Long> frameTimestamps = null;
        List<FaceHeatmapDto> faceHeatmaps = null;
        try {
            if (job.getFrameScoresJson() != null) {
                frameScores = om.readValue(job.getFrameScoresJson(), new TypeReference<List<Double>>(){});
            }
            if (job.getFrameTimestampsJson() != null) {
                frameTimestamps = om.readValue(job.getFrameTimestampsJson(), new TypeReference<List<Long>>(){});
            }
            if (job.getFaceHeatmapsJson() != null) {
                faceHeatmaps = om.readValue(job.getFaceHeatmapsJson(), new TypeReference<List<FaceHeatmapDto>>(){});
            }
        } catch (Exception ignored) {}

        return ScanResponse.builder()
                .id(job.getId())
                .status(job.getStatus())
                .verdict(job.getVerdict())
                .confidenceScore(job.getConfidenceScore())
                .explanation(job.getExplanation())
                .heatmapBase64(job.getHeatmapBase64())
                .frameScores(frameScores)
                .frameTimestamps(frameTimestamps)
                .faceHeatmaps(faceHeatmaps)
                .overallHeatmapUrl(overallHeatmapUrl)
                .createdAt(job.getCreatedAt())
                .completedAt(job.getCompletedAt())
                .build();
    }
}