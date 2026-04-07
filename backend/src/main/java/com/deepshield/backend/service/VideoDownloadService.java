package com.deepshield.backend.service;

import com.deepshield.backend.exception.DownloadException;
import com.deepshield.backend.exception.UnsupportedPlatformException;
import com.deepshield.backend.model.enums.Platform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Service for downloading videos from social media platforms
 * using yt-dlp via Java's ProcessBuilder.
 *
 * Supports YouTube, Instagram Reels, and TikTok.
 * Downloads are saved to the /downloads directory in the project root.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoDownloadService {

    private final UrlParserService urlParserService;

    /** Directory where downloaded videos are stored */
    private static final Path DOWNLOAD_DIR = Paths.get(System.getProperty("user.dir"), "downloads");

    /** Maximum time to wait for a download (in seconds) */
    private static final int TIMEOUT_SECONDS = 120;

    /**
     * Resolves the yt-dlp executable path.
     * First checks for a bundled binary in /tools, then falls back to system PATH.
     * Automatically sets execute permissions on Mac/Linux.
     */
    private String getYtDlpPath() {
        // Check for bundled binary in project tools/ folder
        String os = System.getProperty("os.name").toLowerCase();
        String binaryName = os.contains("win") ? "yt-dlp.exe" : "yt-dlp";
        Path bundledPath = Paths.get(System.getProperty("user.dir"), "tools", binaryName);

        if (Files.exists(bundledPath)) {
            // Auto-set execute permission on Mac/Linux
            if (!os.contains("win")) {
                bundledPath.toFile().setExecutable(true);
            }
            log.info("Using bundled yt-dlp: {}", bundledPath);
            return bundledPath.toAbsolutePath().toString();
        }

        // Fall back to system-installed yt-dlp
        log.info("Bundled yt-dlp not found, using system PATH");
        return "yt-dlp";
    }

    /**
     * Downloads media from the given social media URL.
     * First tries yt-dlp (for videos). If that fails because
     * there's no video (image post), falls back to downloading the image.
     *
     * @param url the social media URL to download from
     * @return the absolute path to the downloaded file
     */
    public String download(String url) {
        // Step 1: Validate URL and detect platform
        Platform platform = urlParserService.detectPlatform(url);
        if (platform == Platform.UNKNOWN) {
            throw new UnsupportedPlatformException(url);
        }

        log.info("Detected platform: {} for URL: {}", platform, url);

        // Step 2: Ensure download directory exists
        try {
            if (!Files.exists(DOWNLOAD_DIR)) {
                Files.createDirectories(DOWNLOAD_DIR);
            }
        } catch (IOException e) {
            throw new DownloadException("Failed to create download directory", e);
        }

        // Step 3: Try yt-dlp first (works for videos)
        try {
            return downloadWithYtDlp(url);
        } catch (DownloadException e) {
            // If yt-dlp fails because it's an image post, try image download
            if (e.getMessage().contains("no video") || e.getMessage().contains("Unsupported URL")) {
                log.info("No video found at URL, attempting image download...");
                return downloadImage(url);
            }
            throw e;
        }
    }

    /**
     * Downloads a video using yt-dlp.
     */
    private String downloadWithYtDlp(String url) {

        // Step 3: Build the output filename template
        // yt-dlp will replace %(title)s and %(ext)s with actual values
        String outputTemplate = DOWNLOAD_DIR.resolve("%(id)s.%(ext)s").toString();

        // Step 4: Build the yt-dlp command
        String ytDlpPath = getYtDlpPath();

        ProcessBuilder pb = new ProcessBuilder(
                ytDlpPath,
                "--no-playlist",                    // Download single video only
                "-f", "mp4/best[ext=mp4]/best",     // Prefer MP4 format
                "--merge-output-format", "mp4",     // Ensure final output is MP4
                "-o", outputTemplate,               // Output path template
                "--print", "after_move:filepath",   // Print final filepath after download
                "--no-warnings",                    // Suppress warnings
                "--restrict-filenames",             // Safe filenames (no spaces/special chars)
                url
        );

        // Merge stderr into stdout so we can read everything from one stream
        pb.redirectErrorStream(true);

        // Step 5: Execute the process
        try {
            log.info("Starting yt-dlp download...");
            Process process = pb.start();

            // Read all output lines
            StringBuilder output = new StringBuilder();
            String lastLine = "";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("yt-dlp: {}", line);
                    output.append(line).append("\n");
                    lastLine = line.trim();
                }
            }

            // Wait for process to complete with timeout
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new DownloadException(
                        "Download timed out after " + TIMEOUT_SECONDS + " seconds for URL: " + url);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new DownloadException(
                        "yt-dlp exited with code " + exitCode + " for URL: " + url
                                + "\nOutput: " + output);
            }

            // Step 6: The last line of output should be the filepath
            // (because of --print after_move:filepath)
            String downloadedFilePath = lastLine;

            // Verify the file actually exists
            Path filePath = Paths.get(downloadedFilePath);
            if (!Files.exists(filePath)) {
                // Fallback: search downloads directory for any recently created file
                downloadedFilePath = findMostRecentFile();
                if (downloadedFilePath == null) {
                    throw new DownloadException(
                            "Download completed but file not found. yt-dlp output: " + output);
                }
            }

            log.info("Download complete: {}", downloadedFilePath);
            return downloadedFilePath;

        } catch (IOException e) {
            throw new DownloadException("Failed to execute yt-dlp. Is it installed and on your PATH?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("Download was interrupted for URL: " + url, e);
        }
    }

    /**
     * Downloads an image from a URL by fetching it with yt-dlp's --write-thumbnail
     * or by directly downloading from the page.
     * Falls back to using yt-dlp to extract and download the image URL.
     */
    private String downloadImage(String url) {
        try {
            String ytDlpPath = getYtDlpPath();
            long startTime = System.currentTimeMillis();

            // Use yt-dlp to extract the image URL and download thumbnail/image
            String outputTemplate = DOWNLOAD_DIR.resolve("%(id)s.%(ext)s").toString();

            ProcessBuilder pb = new ProcessBuilder(
                    ytDlpPath,
                    "--write-thumbnail",
                    "--skip-download",
                    "--convert-thumbnails", "jpg",
                    "-o", outputTemplate,
                    "--no-warnings",
                    "--restrict-filenames",
                    url
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("yt-dlp (image): {}", line);
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }

            // Look for files created AFTER we started the download
            String imagePath = findFileCreatedAfter(startTime);
            if (imagePath != null) {
                log.info("Image downloaded: {}", imagePath);
                return imagePath;
            }

            // If yt-dlp thumbnail approach didn't work, try direct HTTP download
            return downloadDirectImage(url);

        } catch (IOException | InterruptedException e) {
            throw new DownloadException("Failed to download image from URL: " + url, e);
        }
    }

    /**
     * Last resort: downloads the page and tries to find/save an image from it.
     * Uses Java's built-in HTTP client.
     */
    private String downloadDirectImage(String url) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            java.net.http.HttpResponse<byte[]> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());

            String contentType = response.headers().firstValue("content-type").orElse("");

            if (contentType.startsWith("image/")) {
                // Direct image URL — save it
                String ext = contentType.contains("png") ? "png" : "jpg";
                String fileName = System.currentTimeMillis() + "_downloaded." + ext;
                Path filePath = DOWNLOAD_DIR.resolve(fileName);
                Files.write(filePath, response.body());
                log.info("Direct image downloaded: {}", filePath);
                return filePath.toAbsolutePath().toString();
            }

            throw new DownloadException("Could not download image from URL: " + url + ". The post may be an image that requires authentication to access.");

        } catch (IOException | InterruptedException e) {
            throw new DownloadException("Failed to download image from URL: " + url, e);
        }
    }

    /**
     * Finds the most recently modified file in the downloads directory.
     */
    private String findMostRecentFile() {
        return findFileCreatedAfter(0);
    }

    /**
     * Finds a file in the downloads directory that was modified after the given timestamp.
     *
     * @param afterTimestamp only return files modified after this time (millis since epoch)
     * @return absolute path of the matching file, or null if none found
     */
    private String findFileCreatedAfter(long afterTimestamp) {
        try {
            return Files.list(DOWNLOAD_DIR)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() > afterTimestamp;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(
                                    Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}