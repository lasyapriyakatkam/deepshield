package com.deepshield.backend.service;

import com.deepshield.backend.exception.AnalysisException;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for detecting and cropping faces from images.
 *
 * Uses OpenCV's Haar Cascade Classifier for face detection.
 * Each detected face is cropped and saved as a separate image
 * for downstream deepfake analysis.
 */
@Service
@Slf4j
public class FaceDetectionService {

    /** Directory where cropped face images are stored */
    private static final Path FACES_DIR = Paths.get(System.getProperty("user.dir"), "faces");

    /** OpenCV Haar Cascade classifier for frontal face detection */
    private CascadeClassifier faceClassifier;

    /**
     * Initializes the face classifier on application startup.
     * Loads the Haar Cascade XML from the classpath resources.
     */
    @PostConstruct
    public void init() {
        try {
            // Copy the Haar cascade XML from classpath to a temp file
            // because OpenCV needs a real file path, not a classpath resource
            InputStream cascadeStream = getClass().getResourceAsStream(
                    "/haarcascades/haarcascade_frontalface_default.xml");

            if (cascadeStream == null) {
                // Fallback: try loading from OpenCV's built-in data
                String opencvDataDir = System.getProperty("user.dir") + "/src/main/resources/haarcascades/";
                Path cascadePath = Paths.get(opencvDataDir, "haarcascade_frontalface_default.xml");

                if (Files.exists(cascadePath)) {
                    faceClassifier = new CascadeClassifier(cascadePath.toString());
                } else {
                    log.warn("Haar cascade file not found. Face detection will be disabled. " +
                            "Download it from: https://github.com/opencv/opencv/tree/master/data/haarcascades");
                    return;
                }
            } else {
                // Write to temp file for OpenCV to read
                Path tempFile = Files.createTempFile("haarcascade_", ".xml");
                Files.copy(cascadeStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                cascadeStream.close();
                faceClassifier = new CascadeClassifier(tempFile.toString());
            }

            if (faceClassifier.empty()) {
                log.error("Failed to load Haar cascade classifier");
                faceClassifier = null;
            } else {
                log.info("Face detection classifier loaded successfully");
            }

        } catch (IOException e) {
            log.error("Error loading face detection classifier", e);
            faceClassifier = null;
        }
    }

    /**
     * Detects and crops all faces from a list of image files.
     *
     * @param imagePaths list of absolute paths to images (keyframes or uploads)
     * @return list of absolute paths to cropped face images
     * @throws AnalysisException if face detection fails entirely
     */
    public List<String> detectAndCropFaces(List<String> imagePaths) {
        if (faceClassifier == null) {
            log.warn("Face classifier not loaded — returning original images as fallback");
            return imagePaths;
        }

        List<String> facePaths = new ArrayList<>();

        // Create unique subfolder for this batch of faces
        String batchId = String.valueOf(System.currentTimeMillis());
        Path batchFacesDir = FACES_DIR.resolve(batchId);

        try {
            Files.createDirectories(batchFacesDir);
        } catch (IOException e) {
            throw new AnalysisException("Failed to create faces directory", e);
        }

        int totalFaces = 0;

        for (int i = 0; i < imagePaths.size(); i++) {
            String imagePath = imagePaths.get(i);
            try {
                List<String> faces = detectFacesInImage(imagePath, batchFacesDir, i);
                facePaths.addAll(faces);
                totalFaces += faces.size();
            } catch (Exception e) {
                log.warn("Failed to detect faces in image: {} — skipping", imagePath, e);
            }
        }

        log.info("Detected {} total faces across {} images", totalFaces, imagePaths.size());

        // If no faces were found, return original images as fallback
        // (the ML model can still try to analyze them)
        if (facePaths.isEmpty()) {
            log.warn("No faces detected — returning original images for analysis");
            return imagePaths;
        }

        return facePaths;
    }

    /**
     * Detects and crops faces from a single image.
     *
     * @param imagePath absolute path to the image
     * @param outputDir directory to save cropped faces
     * @param imageIndex index of the image (for naming output files)
     * @return list of paths to cropped face images from this image
     */
    private List<String> detectFacesInImage(String imagePath, Path outputDir, int imageIndex) {
        List<String> facePaths = new ArrayList<>();

        // Load the image using OpenCV
        Mat image = opencv_imgcodecs.imread(imagePath);
        if (image.empty()) {
            log.warn("Could not read image: {}", imagePath);
            return facePaths;
        }

        // Convert to grayscale for face detection
        Mat gray = new Mat();
        opencv_imgproc.cvtColor(image, gray, opencv_imgproc.COLOR_BGR2GRAY);
        opencv_imgproc.equalizeHist(gray, gray);

        // Detect faces
        RectVector faces = new RectVector();
        faceClassifier.detectMultiScale(
                gray,
                faces,
                1.1,    // scaleFactor — how much the image size is reduced at each scale
                3,      // minNeighbors — higher = fewer detections but more reliable
                0,      // flags (not used in newer OpenCV)
                new Size(80, 80),    // minSize — minimum face size to detect
                new Size(0, 0)       // maxSize — 0 means no limit
        );

        log.debug("Found {} faces in image: {}", faces.size(), imagePath);

        // Crop and save each detected face
        for (int j = 0; j < faces.size(); j++) {
            Rect faceRect = faces.get(j);

            // Add some padding around the face (20% on each side)
            int padding = (int) (faceRect.width() * 0.2);
            int x = Math.max(0, faceRect.x() - padding);
            int y = Math.max(0, faceRect.y() - padding);
            int width = Math.min(image.cols() - x, faceRect.width() + 2 * padding);
            int height = Math.min(image.rows() - y, faceRect.height() + 2 * padding);

            // Crop the face region
            Rect paddedRect = new Rect(x, y, width, height);
            Mat faceImage = new Mat(image, paddedRect);

            // Save the cropped face
            String fileName = String.format("img%d_face%d.jpg", imageIndex, j);
            Path facePath = outputDir.resolve(fileName);
            opencv_imgcodecs.imwrite(facePath.toString(), faceImage);

            facePaths.add(facePath.toAbsolutePath().toString());

            // Release the cropped mat
            faceImage.close();
        }

        // Clean up
        image.close();
        gray.close();
        faces.close();

        return facePaths;
    }
}