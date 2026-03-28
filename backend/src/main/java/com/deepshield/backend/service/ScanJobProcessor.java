package com.deepshield.backend.service;

import com.deepshield.backend.model.dto.AggregatedResult;
import com.deepshield.backend.model.dto.MLPredictionResult;
import com.deepshield.backend.model.dto.MetadataResult;
import com.deepshield.backend.model.dto.ScanContext;
import com.deepshield.backend.model.entity.ScanJob;
import com.deepshield.backend.model.enums.ScanStatus;
import com.deepshield.backend.repository.ScanJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scheduled processor that picks up PENDING scan jobs and runs the analysis pipeline.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScanJobProcessor {

    private final ScanJobRepository jobRepository;
    private final KeyframeExtractorService keyframeExtractorService;
    private final FaceDetectionService faceDetectionService;
    private final MetadataAnalysisService metadataAnalysisService;
    private final DeepfakeClassifierService deepfakeClassifierService;
    private final ResultAggregator resultAggregator;
    private final ExplanationGeneratorService explanationGeneratorService;
    private final PythonEnsembleService pythonEnsembleService;

    /**
     * Timestamp of the last time the processor ran (used for readiness checks).
     */
    private final AtomicReference<LocalDateTime> lastRun = new AtomicReference<>();

    @Scheduled(fixedDelayString = "${deepshield.processor.delay:2000}")
    @Transactional
    public void pollAndProcess() {
        try {
            ScanJob job = jobRepository.findTopByStatusOrderByCreatedAtAsc(ScanStatus.PENDING);
            if (job == null) return;

            log.info("Picked job id={} for processing", job.getId());

            // update last run timestamp immediately when we pick a job
            lastRun.set(LocalDateTime.now());

            job.setStatus(ScanStatus.PROCESSING);
            jobRepository.save(job);

            // Build pipeline inputs
            String filePath = job.getLocalFilePath();
            List<String> frames;
            if (job.getInputType().name().equalsIgnoreCase("VIDEO")) {
                frames = keyframeExtractorService.extractKeyframes(filePath);
            } else {
                frames = keyframeExtractorService.extractFromImage(filePath);
            }

            
            List<String> faces = faceDetectionService.detectAndCropFaces(frames);

            MetadataResult metadataResult = metadataAnalysisService.analyze(filePath);

            List<MLPredictionResult> predictions = deepfakeClassifierService.predictBatch(faces);

            ScanContext scanContext = ScanContext.builder()
                    .originalFilePath(filePath)
                    .framePaths(frames)
                    .facePaths(faces)
                    .metadataResult(metadataResult)
                    .mlPredictions(predictions)
                    .build();

            AggregatedResult aggregated = resultAggregator.aggregate(scanContext);

            // Optional python ensemble may adjust the score
            try {
                Optional<Double> ensemble = pythonEnsembleService.callEnsemble(scanContext, aggregated);
                if (ensemble.isPresent()) {
                    double ensembled = ensemble.get();
                    aggregated.setConfidenceScore(ensembled);
                    log.info("Ensemble adjusted confidence: {}", ensembled);
                }
            } catch (Exception e) {
                log.warn("Ensemble call failed: {}", e.getMessage());
            }

            String explanation = explanationGeneratorService.generate(aggregated, scanContext);
            aggregated.setExplanation(explanation);

            // Persist results back to job
            job.setVerdict(aggregated.getVerdict());
            job.setConfidenceScore(aggregated.getConfidenceScore());
            job.setExplanation(aggregated.getExplanation());
            // produce simple frameScores and a heatmap file for dev UI
            try {
                ObjectMapper om = new ObjectMapper();
                List<Double> frameScores = Arrays.asList(0.1, 0.2, 0.6, 0.9, 0.3);
                List<Long> frameTimestamps = Arrays.asList(0L, 1000L, 2000L, 3000L, 4000L);
                job.setFrameScoresJson(om.writeValueAsString(frameScores));
                job.setFrameTimestampsJson(om.writeValueAsString(frameTimestamps));

                String heatmapDirPath = Paths.get(System.getProperty("user.dir"), "uploads", "heatmaps").toString();
                Files.createDirectories(Paths.get(heatmapDirPath));
                String heatmapFileName = "heatmap_job_" + job.getId() + ".png";
                File heatmapFile = new File(heatmapDirPath, heatmapFileName);
                int w = 480, h = 240;
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                for (int x = 0; x < w; x++) {
                    float ratio = (float)x / (w - 1);
                    int r = (int)(255 * ratio);
                    int b = 255 - r;
                    g.setColor(new Color(r, 0, b));
                    g.drawLine(x, 0, x, h);
                }
                g.setColor(new Color(255,255,255,80));
                g.fillOval(w/2 - 50, h/2 - 50, 100, 100);
                g.dispose();
                ImageIO.write(img, "png", heatmapFile);

                ArrayNode fhArray = om.createArrayNode();
                ObjectNode fh = om.createObjectNode();
                fh.put("faceIndex", 0);
                fh.put("frameIndex", 2);
                fh.put("timestamp", 2000L);
                fh.put("heatmapUrl", "/uploads/heatmaps/" + heatmapFileName);
                fh.putNull("heatmapBase64");
                fhArray.add(fh);
                job.setFaceHeatmapsJson(om.writeValueAsString(fhArray));
                job.setHeatmapBase64(null);
            } catch (Exception e) {
                log.warn("Failed to produce dev heatmap/frameScores (async): {}", e.getMessage());
            }

            // --- Produce simple per-frame scores and heatmap for UI (dev stub) ---
            try {
                ObjectMapper om = new ObjectMapper();
                // create dummy frame scores/timestamps
                List<Double> frameScores = Arrays.asList(0.1, 0.2, 0.6, 0.9, 0.3);
                List<Long> frameTimestamps = Arrays.asList(0L, 1000L, 2000L, 3000L, 4000L);

                job.setFrameScoresJson(om.writeValueAsString(frameScores));
                job.setFrameTimestampsJson(om.writeValueAsString(frameTimestamps));

                // ensure heatmap dir exists
                String heatmapDirPath = Paths.get(System.getProperty("user.dir"), "uploads", "heatmaps").toString();
                Files.createDirectories(Paths.get(heatmapDirPath));
                String heatmapFileName = "heatmap_job_" + job.getId() + ".png";
                File heatmapFile = new File(heatmapDirPath, heatmapFileName);

                // generate a simple gradient PNG as a placeholder heatmap
                int w = 480, h = 240;
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                for (int x = 0; x < w; x++) {
                    float ratio = (float)x / (w - 1);
                    int r = (int)(255 * ratio);
                    int b = 255 - r;
                    g.setColor(new Color(r, 0, b));
                    g.drawLine(x, 0, x, h);
                }
                g.setColor(new Color(255,255,255,80));
                g.fillOval(w/2 - 50, h/2 - 50, 100, 100);
                g.dispose();
                ImageIO.write(img, "png", heatmapFile);

                // build faceHeatmaps JSON pointing to the generated file
                ArrayNode fhArray = om.createArrayNode();
                ObjectNode fh = om.createObjectNode();
                fh.put("faceIndex", 0);
                fh.put("frameIndex", 2);
                fh.put("timestamp", 2000L);
                fh.put("heatmapUrl", "/uploads/heatmaps/" + heatmapFileName);
                fh.putNull("heatmapBase64");
                fhArray.add(fh);
                job.setFaceHeatmapsJson(om.writeValueAsString(fhArray));
                // also clear heatmapBase64 (we expose via URL)
                job.setHeatmapBase64(null);
            } catch (Exception e) {
                log.warn("Failed to produce dev heatmap/frameScores: {}", e.getMessage());
            }
            job.setCompletedAt(LocalDateTime.now());
            job.setStatus(ScanStatus.COMPLETE);
            jobRepository.save(job);

            log.info("Job id={} completed", job.getId());

            // update last run timestamp on successful completion
            lastRun.set(LocalDateTime.now());

        } catch (Exception e) {
            log.error("Error in job processor: {}", e.getMessage(), e);
            // best-effort: mark job failed if possible
            try {
                // try to find any job in PROCESSING and mark failed — simple fallback
                ScanJob inFlight = jobRepository.findTopByStatusOrderByCreatedAtAsc(ScanStatus.PROCESSING);
                if (inFlight != null) {
                    inFlight.setStatus(ScanStatus.FAILED);
                    inFlight.setExplanation(e.getMessage());
                    inFlight.setCompletedAt(LocalDateTime.now());
                    jobRepository.save(inFlight);
                }
            } catch (Exception ex) {
                log.warn("Failed to mark job as FAILED: {}", ex.getMessage());
            }
        }
    }

    /**
     * Returns the last time the processor executed a run, or null if never run.
     */
    public LocalDateTime getLastRun() {
        return lastRun.get();
    }

    /**
     * Process a specific job by id. This method is async so callers (e.g. ScanService)
     * can trigger processing immediately after job creation without blocking.
     */
    @Async
    @Transactional
    public void processJobById(Long jobId) {
        try {
            ScanJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            if (job.getStatus() != ScanStatus.PENDING) return;

            // Reuse the same processing steps
            job.setStatus(ScanStatus.PROCESSING);
            jobRepository.save(job);

            String filePath = job.getLocalFilePath();
            List<String> frames;
            if (job.getInputType().name().equalsIgnoreCase("VIDEO")) {
                frames = keyframeExtractorService.extractKeyframes(filePath);
            } else {
                frames = keyframeExtractorService.extractFromImage(filePath);
            }

            List<String> faces = faceDetectionService.detectAndCropFaces(frames);
            MetadataResult metadataResult = metadataAnalysisService.analyze(filePath);
            List<MLPredictionResult> predictions = deepfakeClassifierService.predictBatch(faces);

            ScanContext scanContext = ScanContext.builder()
                    .originalFilePath(filePath)
                    .framePaths(frames)
                    .facePaths(faces)
                    .metadataResult(metadataResult)
                    .mlPredictions(predictions)
                    .build();

            AggregatedResult aggregated = resultAggregator.aggregate(scanContext);

            Optional<Double> ensemble = pythonEnsembleService.callEnsemble(scanContext, aggregated);
            ensemble.ifPresent(aggregated::setConfidenceScore);

            String explanation = explanationGeneratorService.generate(aggregated, scanContext);
            aggregated.setExplanation(explanation);

            job.setVerdict(aggregated.getVerdict());
            job.setConfidenceScore(aggregated.getConfidenceScore());
            job.setExplanation(aggregated.getExplanation());
            job.setCompletedAt(LocalDateTime.now());
            job.setStatus(ScanStatus.COMPLETE);
            jobRepository.save(job);
        } catch (Exception e) {
            log.error("Error processing job {}: {}", jobId, e.getMessage(), e);
            try {
                ScanJob inFlight = jobRepository.findById(jobId).orElse(null);
                if (inFlight != null) {
                    inFlight.setStatus(ScanStatus.FAILED);
                    inFlight.setExplanation(e.getMessage());
                    inFlight.setCompletedAt(LocalDateTime.now());
                    jobRepository.save(inFlight);
                }
            } catch (Exception ex) {
                log.warn("Failed to mark job {} as FAILED: {}", jobId, ex.getMessage());
            }
        }
    }
}
