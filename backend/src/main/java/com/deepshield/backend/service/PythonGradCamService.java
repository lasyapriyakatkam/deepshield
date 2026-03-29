package com.deepshield.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Client for a small Python Grad-CAM microservice.
 * The service should accept a multipart file upload (field 'image') and return JSON
 * with key `heatmapBase64` containing a base64-encoded PNG.
 */
@Service
@Slf4j
public class PythonGradCamService {

    private final RestTemplate restTemplate;

    @Value("${python.gradcam.enabled:false}")
    private boolean enabled;

    @Value("${python.gradcam.url:http://localhost:5001/gradcam}")
    private String gradcamUrl;

    public PythonGradCamService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Request the Python service to generate a heatmap for the provided image file.
     * If successful, saves the PNG to the provided destination file and returns an optional URL path.
     */
    public Optional<String> generateAndSaveHeatmap(String imagePath, File outputFile) {
        if (!enabled) return Optional.empty();
        try {
            File img = new File(imagePath);
            if (!img.exists()) {
                log.warn("Source image for gradcam not found: {}", imagePath);
                return Optional.empty();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("image", new FileSystemResource(img));

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> resp = restTemplate.postForEntity(gradcamUrl, requestEntity, Map.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return Optional.empty();
            Object b64obj = resp.getBody().get("heatmapBase64");
            if (!(b64obj instanceof String)) return Optional.empty();
            byte[] bytes = Base64.getDecoder().decode(((String) b64obj));
            Files.write(outputFile.toPath(), bytes);
            return Optional.of("/uploads/heatmaps/" + outputFile.getName());
        } catch (Exception e) {
            log.warn("Grad-CAM service call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
