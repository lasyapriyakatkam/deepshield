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
     * Downloads a video from the given social media URL.
     *
     * @param url the social media URL to download from
     * @return the absolute path to the downloaded video file
     * @throws UnsupportedPlatformException if the URL is from an unsupported platform
     * @throws DownloadException if the download process fails
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
     * Fallback method: finds the most recently modified file in the downloads directory.
     *
     * @return absolute path of the most recent file, or null if directory is empty
     */
    private String findMostRecentFile() {
        try {
            return Files.list(DOWNLOAD_DIR)
                    .filter(Files::isRegularFile)
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