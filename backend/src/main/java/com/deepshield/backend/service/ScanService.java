package com.deepshield.backend.service;

import com.deepshield.backend.model.dto.*;
import com.deepshield.backend.model.entity.ScanJob;
import com.deepshield.backend.model.enums.InputType;
import com.deepshield.backend.model.enums.ScanStatus;
import com.deepshield.backend.repository.ScanJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import com.deepshield.backend.model.dto.ScanResponse;
import com.deepshield.backend.model.dto.AggregatedResult;
import com.deepshield.backend.model.dto.MetadataResult;
import com.deepshield.backend.model.dto.MLPredictionResult;
import com.deepshield.backend.model.dto.ScanContext;

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
    private final KeyframeExtractorService keyframeExtractorService;
    private final FaceDetectionService faceDetectionService;
    private final MetadataAnalysisService metadataAnalysisService;
    private final DeepfakeClassifierService deepfakeClassifierService;
    private final ResultAggregator resultAggregator;
    private final ExplanationGeneratorService explanationGeneratorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Directory where uploaded/downloaded files are stored (absolute path) */
    private static final Path UPLOAD_DIR = Paths.get(System.getProperty("user.dir"), "uploads");

    /**
     * Creates a scan job from a social media URL.
     * Downloads the video, then runs the full analysis pipeline.
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

        // Download
        saved.setStatus(ScanStatus.DOWNLOADING);
        scanJobRepository.save(saved);

        try {
            log.info("Downloading video from URL: {}", url);
            String filePath = videoDownloadService.download(url);
            saved.setLocalFilePath(filePath);
            scanJobRepository.save(saved);
            log.info("Video downloaded successfully: {}", filePath);

            // Run the full analysis pipeline
            runAnalysisPipeline(saved);

        } catch (Exception e) {
            log.error("Failed processing URL: {}", url, e);
            saved.setStatus(ScanStatus.FAILED);
            saved.setErrorMessage(e.getMessage());
            scanJobRepository.save(saved);
        }

        return mapToResponse(saved);
    }

    /**
     * Creates a scan job from an uploaded file (image or video).
     * Saves the file locally, then runs the full analysis pipeline.
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

        try {
            // Run the full analysis pipeline
            runAnalysisPipeline(saved);
        } catch (Exception e) {
            log.error("Failed processing upload: {}", file.getOriginalFilename(), e);
            saved.setStatus(ScanStatus.FAILED);
            saved.setErrorMessage(e.getMessage());
            scanJobRepository.save(saved);
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
     * Runs the full analysis pipeline on a scan job.
     * Extracts frames, detects faces, runs metadata analysis,
     * CNN classification, aggregation, and explanation generation.
     */
    private void runAnalysisPipeline(ScanJob job) {
        String filePath = job.getLocalFilePath();

        // Step 1: Update status to PROCESSING
        job.setStatus(ScanStatus.PROCESSING);
        scanJobRepository.save(job);

        // Step 2: Extract keyframes (or use image directly)
        log.info("Step 1: Extracting keyframes...");
        List<String> frames;
        if (job.getInputType() == InputType.VIDEO || job.getInputType() == InputType.URL) {
            frames = keyframeExtractorService.extractKeyframes(filePath);
        } else {
            frames = keyframeExtractorService.extractFromImage(filePath);
        }

        // Step 3: Detect and crop faces
        log.info("Step 2: Detecting faces...");
        List<String> faces = faceDetectionService.detectAndCropFaces(frames);

        // Step 4: Run metadata analysis
        log.info("Step 3: Analyzing metadata...");
        MetadataResult metadataResult = metadataAnalysisService.analyze(filePath);

        // Step 5: Update status to ANALYZING
        job.setStatus(ScanStatus.ANALYZING);
        scanJobRepository.save(job);

        // Step 6: Run ML classification
        log.info("Step 4: Running CNN classification...");
        List<MLPredictionResult> predictions = deepfakeClassifierService.predictBatch(faces);

        // Step 7: Build context and aggregate
        log.info("Step 5: Aggregating results...");
        ScanContext context = ScanContext.builder()
                .job(job)
                .originalFilePath(filePath)
                .framePaths(frames)
                .facePaths(faces)
                .metadataResult(metadataResult)
                .mlPredictions(predictions)
                .build();

        AggregatedResult aggregated = resultAggregator.aggregate(context);

        // Step 8: Generate explanation
        String explanation = explanationGeneratorService.generate(aggregated, context);

        // Step 9: Build face heatmaps list for frontend
        List<FaceHeatmapDto> faceHeatmaps = predictions.stream()
                .filter(p -> p.getHeatmapBase64() != null)
                .map(p -> FaceHeatmapDto.builder()
                        .heatmapBase64(p.getHeatmapBase64())
                        .fakeConfidence(p.getFakeConfidence())
                        .label(p.getLabel())
                        .build())
                .collect(Collectors.toList());

        // Step 10: Build frame scores for timeline chart
        List<Double> frameScores = predictions.stream()
                .map(MLPredictionResult::getFakeConfidence)
                .collect(Collectors.toList());

        // Step 11: Get first heatmap if available
        String heatmap = predictions.stream()
                .filter(p -> p.getHeatmapBase64() != null)
                .map(MLPredictionResult::getHeatmapBase64)
                .findFirst()
                .orElse(null);

        // Step 12: Update job with results
        job.setVerdict(aggregated.getVerdict());
        job.setConfidenceScore(aggregated.getConfidenceScore());
        job.setExplanation(explanation);
        job.setHeatmapBase64(heatmap);
        job.setStatus(ScanStatus.COMPLETE);
        job.setCompletedAt(LocalDateTime.now());

        // Store breakdown, heatmaps, and frame scores as JSON
        try {
            job.setBreakdownJson(objectMapper.writeValueAsString(aggregated.getBreakdown()));
            job.setFaceHeatmapsJson(objectMapper.writeValueAsString(faceHeatmaps));
            job.setFrameScoresJson(objectMapper.writeValueAsString(frameScores));
        } catch (Exception e) {
            log.warn("Failed to serialize analysis data to JSON", e);
        }

        scanJobRepository.save(job);

        log.info("Pipeline complete — Verdict: {}, Confidence: {}%",
                job.getVerdict(), Math.round(job.getConfidenceScore() * 100));
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
     * Deserializes JSON fields back into lists.
     */
    private ScanResponse mapToResponse(ScanJob job) {
        List<AnalysisDetail> breakdown = null;
        List<FaceHeatmapDto> faceHeatmaps = null;
        List<Double> frameScores = null;

        try {
            if (job.getBreakdownJson() != null) {
                breakdown = objectMapper.readValue(job.getBreakdownJson(),
                        new TypeReference<List<AnalysisDetail>>() {});
            }
            if (job.getFaceHeatmapsJson() != null) {
                faceHeatmaps = objectMapper.readValue(job.getFaceHeatmapsJson(),
                        new TypeReference<List<FaceHeatmapDto>>() {});
            }
            if (job.getFrameScoresJson() != null) {
                frameScores = objectMapper.readValue(job.getFrameScoresJson(),
                        new TypeReference<List<Double>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize analysis data from JSON", e);
        }

        return ScanResponse.builder()
                .id(job.getId())
                .status(job.getStatus())
                .verdict(job.getVerdict())
                .confidenceScore(job.getConfidenceScore())
                .explanation(job.getExplanation())
                .heatmapBase64(job.getHeatmapBase64())
                .inputSource(job.getInputSource())
                .createdAt(job.getCreatedAt())
                .completedAt(job.getCompletedAt())
                .errorMessage(job.getErrorMessage())
                .breakdown(breakdown)
                .faceHeatmaps(faceHeatmaps)
                .frameScores(frameScores)
                .build();
    }
}
