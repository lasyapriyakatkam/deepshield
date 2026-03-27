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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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

    @Scheduled(fixedDelayString = "${deepshield.processor.delay:2000}")
    @Transactional
    public void pollAndProcess() {
        try {
            ScanJob job = jobRepository.findTopByStatusOrderByCreatedAtAsc(ScanStatus.PENDING);
            if (job == null) return;

            log.info("Picked job id={} for processing", job.getId());

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
            job.setCompletedAt(LocalDateTime.now());
            job.setStatus(ScanStatus.COMPLETE);
            jobRepository.save(job);

            log.info("Job id={} completed", job.getId());

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
