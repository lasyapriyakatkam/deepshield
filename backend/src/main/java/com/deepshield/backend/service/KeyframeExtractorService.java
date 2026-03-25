package com.deepshield.backend.service;

import com.deepshield.backend.exception.AnalysisException;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for extracting keyframes from video files.
 *
 * Instead of analyzing every frame (which could be thousands),
 * this service extracts one frame per second to keep processing fast
 * while still capturing meaningful content across the video.
 */
@Service
@Slf4j
public class KeyframeExtractorService {

    /** Directory where extracted frames are temporarily stored */
    private static final Path FRAMES_DIR = Paths.get(System.getProperty("user.dir"), "frames");

    /** How often to grab a frame (in seconds) */
    private static final int FRAME_INTERVAL_SECONDS = 1;

    /**
     * Extracts keyframes from a video file at regular intervals.
     *
     * @param videoFilePath absolute path to the video file
     * @return list of absolute paths to extracted frame images (JPGs)
     * @throws AnalysisException if frame extraction fails
     */
    public List<String> extractKeyframes(String videoFilePath) {
        List<String> framePaths = new ArrayList<>();

        // Create a unique subfolder for this video's frames
        String videoId = String.valueOf(System.currentTimeMillis());
        Path videoFramesDir = FRAMES_DIR.resolve(videoId);

        try {
            Files.createDirectories(videoFramesDir);
        } catch (IOException e) {
            throw new AnalysisException("Failed to create frames directory", e);
        }

        // Use FFmpegFrameGrabber to read the video
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(new File(videoFilePath))) {
            grabber.start();

            double frameRate = grabber.getVideoFrameRate();
            int totalFrames = grabber.getLengthInFrames();
            double durationSeconds = totalFrames / frameRate;

            log.info("Video info — FPS: {}, Total frames: {}, Duration: {:.1f}s",
                    frameRate, totalFrames, durationSeconds);

            // Calculate how many frames to skip between captures
            int frameSkip = (int) (frameRate * FRAME_INTERVAL_SECONDS);
            if (frameSkip < 1) frameSkip = 1;

            Java2DFrameConverter converter = new Java2DFrameConverter();
            int frameNumber = 0;
            int savedCount = 0;

            Frame frame;
            while ((frame = grabber.grabImage()) != null) {
                // Only save every Nth frame
                if (frameNumber % frameSkip == 0) {
                    BufferedImage image = converter.convert(frame);

                    if (image != null) {
                        String fileName = String.format("frame_%04d.jpg", savedCount);
                        Path framePath = videoFramesDir.resolve(fileName);

                        ImageIO.write(image, "jpg", framePath.toFile());
                        framePaths.add(framePath.toAbsolutePath().toString());
                        savedCount++;

                        log.debug("Saved frame {} at video position {:.1f}s",
                                savedCount, frameNumber / frameRate);
                    }
                }
                frameNumber++;
            }

            grabber.stop();
            log.info("Extracted {} keyframes from video", savedCount);

        } catch (Exception e) {
            throw new AnalysisException(
                    "Failed to extract keyframes from video: " + videoFilePath, e);
        }

        if (framePaths.isEmpty()) {
            throw new AnalysisException(
                    "No keyframes could be extracted from video: " + videoFilePath);
        }

        return framePaths;
    }

    /**
     * Extracts keyframes from an image file.
     * For images, this simply returns the original file path in a list
     * since there are no frames to extract.
     *
     * @param imageFilePath absolute path to the image file
     * @return list containing the single image path
     */
    public List<String> extractFromImage(String imageFilePath) {
        List<String> paths = new ArrayList<>();
        paths.add(imageFilePath);
        log.info("Image input — no keyframe extraction needed");
        return paths;
    }
}