package com.deepshield.backend.controller;

import com.deepshield.backend.service.ScanJobProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Health/readiness endpoint for the scheduled ScanJobProcessor.
 */
@RestController
@RequestMapping("/internal/processor")
public class ProcessorHealthController {
    private static final Logger log = LoggerFactory.getLogger(ProcessorHealthController.class);

    private final ScanJobProcessor scanJobProcessor;

    // how recent the last run must be to consider the processor ready
    private static final Duration READY_THRESHOLD = Duration.ofMinutes(5);

    public ProcessorHealthController(ScanJobProcessor scanJobProcessor) {
        this.scanJobProcessor = scanJobProcessor;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        LocalDateTime lastRun = scanJobProcessor.getLastRun();
        Map<String, Object> body = new HashMap<>();
        if (lastRun == null) {
            body.put("status", "STARTING");
            body.put("message", "processor has not yet run");
            body.put("lastRun", null);
            return ResponseEntity.status(200).body(body);
        }

        Duration age = Duration.between(lastRun, LocalDateTime.now());
        boolean ready = !age.minus(READY_THRESHOLD).isNegative() ? false : true;
        // above: if age > READY_THRESHOLD then ready=false
        if (ready) {
            body.put("status", "UP");
        } else {
            body.put("status", "DEGRADED");
        }

        body.put("lastRun", lastRun.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        body.put("ageSeconds", age.getSeconds());
        return ResponseEntity.ok(body);
    }
}
