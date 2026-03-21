package com.deepshield.backend.service;

import com.deepshield.backend.model.dto.ScanResponse;
import com.deepshield.backend.model.entity.ScanJob;
import com.deepshield.backend.model.enums.InputType;
import com.deepshield.backend.model.enums.ScanStatus;
import com.deepshield.backend.repository.ScanJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Core service handling scan job creation, file storage,
 * and result retrieval. Pipeline processing will be added later.
 */
@Service
@RequiredArgsConstructor
public class ScanService {

    private final ScanJobRepository scanJobRepository;

    /** Directory where uploaded/downloaded files are stored (absolute path) */
    private static final Path UPLOAD_DIR = Paths.get(System.getProperty("user.dir"), "uploads");

    /**
     * Creates a scan job from a social media URL.
     * TODO: Later this will trigger async pipeline processing.
     */
    public ScanResponse submitUrl(String url) {
        ScanJob job = new ScanJob();
        job.setInputType(InputType.URL);
        job.setInputSource(url);
        job.setStatus(ScanStatus.PENDING);

        ScanJob saved = scanJobRepository.save(job);

        // TODO: Trigger async analysis pipeline here
        // analysisPipelineService.runPipelineAsync(saved.getId());

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

        // TODO: Trigger async analysis pipeline here
        // analysisPipelineService.runPipelineAsync(saved.getId());

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
        return ScanResponse.builder()
                .id(job.getId())
                .status(job.getStatus())
                .verdict(job.getVerdict())
                .confidenceScore(job.getConfidenceScore())
                .explanation(job.getExplanation())
                .heatmapBase64(job.getHeatmapBase64())
                .createdAt(job.getCreatedAt())
                .completedAt(job.getCompletedAt())
                .build();
    }
}