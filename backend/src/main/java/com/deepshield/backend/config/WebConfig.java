package com.deepshield.backend.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
@Slf4j
public class WebConfig implements WebMvcConfigurer {

    @Value("${heatmap.storage.path:uploads/heatmaps}")
    private String heatmapStoragePath;

    @PostConstruct
    public void ensureDirectories() {
        File dir = new File(heatmapStoragePath);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), heatmapStoragePath);
        }
        if (!dir.exists()) {
            boolean ok = dir.mkdirs();
            if (ok) log.info("Created heatmap storage directory: {}", dir.getAbsolutePath());
            else log.warn("Failed to create heatmap storage directory: {}", dir.getAbsolutePath());
        } else {
            log.info("Heatmap storage directory exists: {}", dir.getAbsolutePath());
        }
        // Store resolved absolute path back so resource handler can use it
        heatmapStoragePath = dir.getAbsolutePath();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Expose uploads/ and heatmap directory so frontend can fetch assets by URL
        String uploadsDir = System.getProperty("user.dir") + File.separator + "uploads" + File.separator;
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadsDir);

        // Also expose heatmapStoragePath directly (in case it's outside uploads)
        if (heatmapStoragePath != null) {
            String path = heatmapStoragePath.endsWith(File.separator) ? heatmapStoragePath : heatmapStoragePath + File.separator;
            registry.addResourceHandler("/heatmaps/**")
                    .addResourceLocations("file:" + path);
        }
    }
}
