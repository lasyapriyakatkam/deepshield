package com.deepshield.backend.service;

import com.deepshield.backend.model.dto.AggregatedResult;
import com.deepshield.backend.model.dto.ScanContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * Optional client to call an external Python ensemble microservice.
 * Calls are guarded and will return empty if the service is disabled or unreachable.
 */
@Service
@Slf4j
public class PythonEnsembleService {

    private final RestTemplate restTemplate;

    @Value("${python.enabled:false}")
    private boolean pythonEnabled;

    @Value("${python.service.url:http://localhost:5000/ensemble}")
    private String pythonUrl;

    public PythonEnsembleService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Call the Python ensemble with a small payload and expect a JSON response
     * containing an `ensembleScore` key (double). Returns empty on failure.
     */
    public Optional<Double> callEnsemble(ScanContext context, AggregatedResult aggregated) {
        if (!pythonEnabled) return Optional.empty();
        try {
            // Minimal payload — strategies can be extended later
            Map<String, Object> payload = Map.of(
                    "breakdown", aggregated.getBreakdown(),
                    "confidence", aggregated.getConfidenceScore()
            );
            Map resp = restTemplate.postForObject(pythonUrl, payload, Map.class);
            if (resp == null) return Optional.empty();
            Object scoreObj = resp.get("ensembleScore");
            if (scoreObj instanceof Number) {
                return Optional.of(((Number) scoreObj).doubleValue());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Python ensemble call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
