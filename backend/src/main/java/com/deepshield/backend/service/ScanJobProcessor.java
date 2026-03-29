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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
    private final PythonGradCamService pythonGradCamService;

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
            // produce per-frame scores and per-face heatmaps
            try {
                ObjectMapper om = new ObjectMapper();
                // compute per-frame average fakeConfidence using mlPredictions and face source paths
        List<Double> frameScores = computeFrameScores(scanContext);
        int framesCount = scanContext.getFramePaths() == null ? 0 : scanContext.getFramePaths().size();
        // Defensive: ensure frameScores is non-null and has at least one element matching framesCount
        if (frameScores == null || frameScores.isEmpty()) {
            int sz = framesCount == 0 ? 1 : framesCount;
            Double[] fallback = new Double[sz];
            Arrays.fill(fallback, 0.5);
            frameScores = Arrays.asList(fallback);
            log.info("Job id={} — computed frameScores was empty; persisting neutral fallback of size={}", job.getId(), frameScores.size());
        }
        log.info("Job id={} — framesCount={}, predsCount={}, computed frameScores size={}", job.getId(), framesCount, (predictions == null ? 0 : predictions.size()), frameScores == null ? 0 : frameScores.size());
        List<Long> frameTimestamps = IntStream.range(0, framesCount == 0 ? Math.max(1, frameScores.size()) : framesCount)
            .mapToObj(i -> i * 1000L)
            .collect(Collectors.toList());
                job.setFrameScoresJson(om.writeValueAsString(frameScores));
                job.setFrameTimestampsJson(om.writeValueAsString(frameTimestamps));

                String heatmapDirPath = Paths.get(System.getProperty("user.dir"), "uploads", "heatmaps").toString();
                Files.createDirectories(Paths.get(heatmapDirPath));

                ArrayNode fhArray = om.createArrayNode();
                // For each face prediction, request per-face Grad-CAM from python service when available
                for (int i = 0; i < predictions.size(); i++) {
                    MLPredictionResult p = predictions.get(i);
                    String facePath = p.getSourceImagePath();
                    String heatmapFileName = "face_heatmap_job_" + job.getId() + "_face_" + i + ".png";
                    File outFile = new File(heatmapDirPath, heatmapFileName);
                    Optional<String> urlOpt = pythonGradCamService.generateAndSaveHeatmap(facePath, outFile);
                    ObjectNode fh = om.createObjectNode();
                    fh.put("faceIndex", i);
                    fh.put("frameIndex", findFrameIndexForFace(scanContext.getFramePaths(), facePath));
                    fh.put("timestamp", 0L);
                    if (urlOpt.isPresent()) {
                        fh.put("heatmapUrl", urlOpt.get());
                        fh.putNull("heatmapBase64");
                    } else if (p.getHeatmapBase64() != null) {
                        // save inline heatmap to file
                        byte[] bytes = java.util.Base64.getDecoder().decode(p.getHeatmapBase64());
                        Files.write(outFile.toPath(), bytes);
                        fh.put("heatmapUrl", "/uploads/heatmaps/" + heatmapFileName);
                        fh.putNull("heatmapBase64");
                    } else {
                        fh.putNull("heatmapUrl");
                        fh.putNull("heatmapBase64");
                    }
                    fhArray.add(fh);
                }
                job.setFaceHeatmapsJson(om.writeValueAsString(fhArray));
                // Persist analysis breakdown so frontend can show per-check details
                try {
                    job.setBreakdownJson(om.writeValueAsString(aggregated.getBreakdown()));
                } catch (Exception ex) {
                    log.warn("Failed to serialize breakdown for job {}: {}", job.getId(), ex.getMessage());
                }
            } catch (Exception e) {
                log.warn("Failed to produce dev heatmap/frameScores (async): {}", e.getMessage());
                // Ensure we persist empty/default values so API always returns predictable fields
                try {
                    ObjectMapper om2 = new ObjectMapper();
                    if (job.getFrameScoresJson() == null) {
                        List<Double> fallbackScores = computeFrameScores(scanContext);
                        job.setFrameScoresJson(om2.writeValueAsString(fallbackScores));
                    }
                    if (job.getFrameTimestampsJson() == null) {
                        // generate timestamps to match the fallbackScores length
                        List<Double> fs = (job.getFrameScoresJson() == null) ? computeFrameScores(scanContext)
                                : null;
                        int n = fs == null ? (scanContext.getFramePaths() == null ? 1 : scanContext.getFramePaths().size()) : fs.size();
                        List<Long> fallbackTs = IntStream.range(0, n).mapToObj(i -> i * 1000L).collect(Collectors.toList());
                        job.setFrameTimestampsJson(om2.writeValueAsString(fallbackTs));
                    }
                    if (job.getFaceHeatmapsJson() == null) {
                        job.setFaceHeatmapsJson(om2.createArrayNode().toString());
                    }
                } catch (Exception ex) {
                    log.warn("Failed to write fallback heatmap/frameScores JSON: {}", ex.getMessage());
                }
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
     * Compute per-frame average fakeConfidence. We map each prediction's sourceImagePath
     * back to the frame it was extracted from (by filename contains match) and average
     * fakeConfidence across faces for each frame.
     */
    private List<Double> computeFrameScores(ScanContext context) {
        List<String> frames = context.getFramePaths();
        List<MLPredictionResult> preds = context.getMlPredictions();
        if (frames == null || frames.isEmpty()) {
            // no frames — single neutral score
            return Arrays.asList(0.5);
        }
        if (preds == null || preds.isEmpty()) {
            // no predictions — return neutral score for each frame so lengths match
            Double[] fallback = new Double[frames.size()];
            for (int i = 0; i < frames.size(); i++) fallback[i] = 0.5;
            return Arrays.asList(fallback);
        }
        // If there is exactly one frame (image input) but multiple face predictions,
        // map all predictions to the single frame to produce meaningful per-frame avg.
        if (frames.size() == 1) {
            double sum = 0.0;
            int count = 0;
            for (MLPredictionResult p : preds) {
                if (p == null) continue;
                Double fc = p.getFakeConfidence();
                if (fc != null) {
                    sum += fc;
                    count++;
                }
            }
            double avg = count == 0 ? 0.5 : (sum / count);
            return Arrays.asList(avg);
        }
        double[] sums = new double[frames.size()];
        int[] counts = new int[frames.size()];
        for (MLPredictionResult p : preds) {
            String src = p.getSourceImagePath();
            if (src == null) continue;
            for (int i = 0; i < frames.size(); i++) {
                if (src.contains(new File(frames.get(i)).getName())) {
                    sums[i] += p.getFakeConfidence() == null ? 0.0 : p.getFakeConfidence();
                    counts[i]++;
                    break;
                }
            }
        }
        Double[] out = new Double[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            out[i] = counts[i] == 0 ? 0.5 : sums[i] / counts[i];
        }
        return Arrays.asList(out);
    }

    private int findFrameIndexForFace(List<String> frames, String facePath) {
        if (frames == null || frames.isEmpty() || facePath == null) return -1;
        String faceName = new File(facePath).getName();
        for (int i = 0; i < frames.size(); i++) {
            if (faceName.contains(new File(frames.get(i)).getName())) return i;
        }
        return -1;
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

            // helper methods are below

            Optional<Double> ensemble = pythonEnsembleService.callEnsemble(scanContext, aggregated);
            ensemble.ifPresent(aggregated::setConfidenceScore);

            String explanation = explanationGeneratorService.generate(aggregated, scanContext);
            aggregated.setExplanation(explanation);

            job.setVerdict(aggregated.getVerdict());
            job.setConfidenceScore(aggregated.getConfidenceScore());
            job.setExplanation(aggregated.getExplanation());

            // produce per-frame scores and per-face heatmaps (same as scheduled path)
            try {
                ObjectMapper om = new ObjectMapper();
        List<Double> frameScores = computeFrameScores(scanContext);
        int framesCount = scanContext.getFramePaths() == null ? 0 : scanContext.getFramePaths().size();
        if (frameScores == null || frameScores.isEmpty()) {
            int sz = framesCount == 0 ? 1 : framesCount;
            Double[] fallback = new Double[sz];
            Arrays.fill(fallback, 0.5);
            frameScores = Arrays.asList(fallback);
            log.info("(async) Job id={} — computed frameScores was empty; persisting neutral fallback of size={}", job.getId(), frameScores.size());
        }
        log.info("(async) Job id={} — framesCount={}, predsCount={}, computed frameScores size={}", job.getId(), framesCount, (predictions == null ? 0 : predictions.size()), frameScores == null ? 0 : frameScores.size());
        List<Long> frameTimestamps = IntStream.range(0, framesCount == 0 ? Math.max(1, frameScores.size()) : framesCount)
            .mapToObj(i -> i * 1000L)
            .collect(Collectors.toList());
                job.setFrameScoresJson(om.writeValueAsString(frameScores));
                job.setFrameTimestampsJson(om.writeValueAsString(frameTimestamps));

                String heatmapDirPath = Paths.get(System.getProperty("user.dir"), "uploads", "heatmaps").toString();
                Files.createDirectories(Paths.get(heatmapDirPath));

                ArrayNode fhArray = om.createArrayNode();
                for (int i = 0; i < predictions.size(); i++) {
                    MLPredictionResult p = predictions.get(i);
                    String facePath = p.getSourceImagePath();
                    String heatmapFileName = "face_heatmap_job_" + job.getId() + "_face_" + i + ".png";
                    File outFile = new File(heatmapDirPath, heatmapFileName);
                    Optional<String> urlOpt = pythonGradCamService.generateAndSaveHeatmap(facePath, outFile);
                    ObjectNode fh = om.createObjectNode();
                    fh.put("faceIndex", i);
                    fh.put("frameIndex", findFrameIndexForFace(scanContext.getFramePaths(), facePath));
                    fh.put("timestamp", 0L);
                    if (urlOpt.isPresent()) {
                        fh.put("heatmapUrl", urlOpt.get());
                        fh.putNull("heatmapBase64");
                    } else if (p.getHeatmapBase64() != null) {
                        byte[] bytes = java.util.Base64.getDecoder().decode(p.getHeatmapBase64());
                        Files.write(outFile.toPath(), bytes);
                        fh.put("heatmapUrl", "/uploads/heatmaps/" + heatmapFileName);
                        fh.putNull("heatmapBase64");
                    } else {
                        fh.putNull("heatmapUrl");
                        fh.putNull("heatmapBase64");
                    }
                    fhArray.add(fh);
                }
                job.setFaceHeatmapsJson(om.writeValueAsString(fhArray));
                try {
                    job.setBreakdownJson(om.writeValueAsString(aggregated.getBreakdown()));
                } catch (Exception ex) {
                    log.warn("Failed to serialize breakdown for job {} (async): {}", job.getId(), ex.getMessage());
                }
            } catch (Exception e) {
                log.warn("Failed to produce dev heatmap/frameScores (async): {}", e.getMessage());
                // Ensure API fields are present even if heatmap generation failed
                try {
                    ObjectMapper om2 = new ObjectMapper();
                    if (job.getFrameScoresJson() == null) {
                        List<Double> fallbackScores = computeFrameScores(scanContext);
                        job.setFrameScoresJson(om2.writeValueAsString(fallbackScores));
                    }
                    if (job.getFrameTimestampsJson() == null) {
                        List<Double> fs = (job.getFrameScoresJson() == null) ? computeFrameScores(scanContext)
                                : null;
                        int n = fs == null ? (scanContext.getFramePaths() == null ? 1 : scanContext.getFramePaths().size()) : fs.size();
                        List<Long> fallbackTs = IntStream.range(0, n).mapToObj(i -> i * 1000L).collect(Collectors.toList());
                        job.setFrameTimestampsJson(om2.writeValueAsString(fallbackTs));
                    }
                    if (job.getFaceHeatmapsJson() == null) {
                        job.setFaceHeatmapsJson(om2.createArrayNode().toString());
                    }
                } catch (Exception ex) {
                    log.warn("Failed to write fallback heatmap/frameScores JSON: {}", ex.getMessage());
                }
            }

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
