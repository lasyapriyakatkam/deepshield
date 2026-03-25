package com.deepshield.backend.controller;

import com.deepshield.backend.model.dto.ScanRequest;
import com.deepshield.backend.model.dto.ScanResponse;
import com.deepshield.backend.service.ScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.deepshield.backend.service.KeyframeExtractorService;
import com.deepshield.backend.service.FaceDetectionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import java.io.IOException;
import java.util.List;

/**
 * REST controller for all deepfake scan operations.
 * Provides endpoints for URL scanning, file upload, status checks, and history.
 */
@RestController
@RequestMapping("/api/scan")
@CrossOrigin(origins = "*")  // Allow React frontend to connect
@RequiredArgsConstructor
public class ScanController {

    private final ScanService scanService;
    private final KeyframeExtractorService keyframeExtractorService;
    private final FaceDetectionService faceDetectionService;

    /**
     * POST /api/scan/url
     * Submit a social media URL for deepfake analysis.
     *
     * Request body: { "url": "https://youtube.com/watch?v=..." }
     * Returns: ScanResponse with job ID and PENDING status
     */
    @PostMapping("/url")
    public ResponseEntity<ScanResponse> scanUrl(@RequestBody ScanRequest request) {
        if (request.getUrl() == null || request.getUrl().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // ScanService handles validation, download, and error states
        ScanResponse response = scanService.submitUrl(request.getUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /api/scan/upload
     * Upload an image or video file for deepfake analysis.
     *
     * Request: multipart/form-data with a "file" field
     * Returns: ScanResponse with job ID and PENDING status
     */
    @PostMapping("/upload")
    public ResponseEntity<ScanResponse> scanUpload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        ScanResponse response = scanService.submitUpload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/scan/{id}
     * Check the status and result of a scan job.
     *
     * Returns: ScanResponse with current status, and results if COMPLETE
     */
    @GetMapping("/{id}")
    public ResponseEntity<ScanResponse> getStatus(@PathVariable Long id) {
        ScanResponse response = scanService.getStatus(id);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/scan/history
     * Retrieve all past scans, most recent first.
     *
     * Returns: List of ScanResponse objects
     */
    @GetMapping("/history")
    public ResponseEntity<List<ScanResponse>> getHistory() {
        List<ScanResponse> history = scanService.getHistory();
        return ResponseEntity.ok(history);
    }

    /**
     * POST /api/scan/test-pipeline
     * Test endpoint to verify keyframe extraction and face detection.
     * Accepts a video or image file and returns paths to detected faces.
     * Remove this endpoint before production.
     */
    @PostMapping("/test-pipeline")
    public ResponseEntity<Map<String, Object>> testPipeline(
            @RequestParam("file") MultipartFile file) throws IOException {

        // Save uploaded file temporarily
        Path tempDir = Paths.get(System.getProperty("user.dir"), "uploads");
        Files.createDirectories(tempDir);
        String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path filePath = tempDir.resolve(fileName);
        Files.copy(file.getInputStream(), filePath);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("originalFile", filePath.toString());

        // Step 1: Extract keyframes (or just return the image)
        List<String> frames;
        String contentType = file.getContentType();
        if (contentType != null && contentType.startsWith("video/")) {
            frames = keyframeExtractorService.extractKeyframes(filePath.toString());
            result.put("extractedFrames", frames.size());
        } else {
            frames = keyframeExtractorService.extractFromImage(filePath.toString());
            result.put("extractedFrames", 1);
        }
        result.put("framePaths", frames);

        // Step 2: Detect and crop faces
        List<String> faces = faceDetectionService.detectAndCropFaces(frames);
        result.put("detectedFaces", faces.size());
        result.put("facePaths", faces);

        return ResponseEntity.ok(result);
    }
}