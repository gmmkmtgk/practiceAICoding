package com.fileshare;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

@RestController
public class FileController {

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);
    private static final String TOKEN_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final Database database;
    private final Path uploadDir;
    private final Random random = new Random();

    public FileController(Database database, @Value("${fileshare.upload-dir}") String uploadDir) {
        this.database = database;
        this.uploadDir = Paths.get(uploadDir);
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String generateToken() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(TOKEN_CHARS.charAt(random.nextInt(TOKEN_CHARS.length())));
        }
        return sb.toString();
    }

    @PostMapping("/upload")
    public ResponseEntity<Object> upload(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "expires_in_hours", defaultValue = "24") int expiresInHours,
            @RequestParam(value = "max_downloads", defaultValue = "10") int maxDownloads,
            HttpServletRequest request) {
        if (file == null) {
            return ResponseEntity.status(400).body(Map.of("error", "file required"));
        }

        String filename = file.getOriginalFilename();
        logger.info("Uploading {}", filename);

        String token = generateToken();

        File storedPath = new File(uploadDir.toFile(), filename);
        long size;
        try {
            file.transferTo(storedPath.toPath());
            size = Files.size(storedPath.toPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(expiresInHours).toString();

        try (Connection conn = database.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO shares (token, filename, stored_path, content_type, size_bytes, expires_at, max_downloads)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """);
            stmt.setString(1, token);
            stmt.setString(2, filename);
            stmt.setString(3, storedPath.getPath());
            stmt.setString(4, file.getContentType());
            stmt.setLong(5, size);
            stmt.setString(6, expiresAt);
            stmt.setInt(7, maxDownloads);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("share_url", shareBaseUrl(request) + token);
        body.put("expires_at", expiresAt);
        body.put("max_downloads", maxDownloads);
        return ResponseEntity.status(201).body(body);
    }

    @GetMapping("/{token}")
    public ResponseEntity<Object> download(@PathVariable String token) {
        try (Connection conn = database.getConnection()) {
            PreparedStatement select = conn.prepareStatement("SELECT * FROM shares WHERE token = ?");
            select.setString(1, token);
            ResultSet share = select.executeQuery();

            if (!share.next()) {
                return ResponseEntity.status(404).body(Map.of("error", "not found"));
            }

            int downloadCount = share.getInt("download_count");
            int maxDownloads = share.getInt("max_downloads");
            if (downloadCount >= maxDownloads) {
                return ResponseEntity.status(410).body(Map.of("error", "download limit reached"));
            }

            String filename = share.getString("filename");
            String storedPath = share.getString("stored_path");

            int newCount = downloadCount + 1;
            PreparedStatement update = conn.prepareStatement(
                "UPDATE shares SET download_count = ? WHERE token = ?");
            update.setInt(1, newCount);
            update.setString(2, token);
            update.executeUpdate();

            Resource resource = new FileSystemResource(storedPath);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(resource);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/info/{token}")
    public ResponseEntity<Object> info(@PathVariable String token) {
        try (Connection conn = database.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("""
                SELECT token, filename, content_type, size_bytes, uploaded_at,
                       expires_at, max_downloads, download_count
                FROM shares WHERE token = ?
            """);
            stmt.setString(1, token);
            ResultSet share = stmt.executeQuery();

            if (!share.next()) {
                return ResponseEntity.status(404).body(Map.of("error", "not found"));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("token", share.getString("token"));
            body.put("filename", share.getString("filename"));
            body.put("content_type", share.getString("content_type"));
            body.put("size_bytes", share.getObject("size_bytes"));
            body.put("uploaded_at", share.getString("uploaded_at"));
            body.put("expires_at", share.getString("expires_at"));
            body.put("max_downloads", share.getObject("max_downloads"));
            body.put("download_count", share.getObject("download_count"));
            return ResponseEntity.ok(body);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @DeleteMapping("/{token}")
    public ResponseEntity<Object> delete(@PathVariable String token) {
        try (Connection conn = database.getConnection()) {
            PreparedStatement select = conn.prepareStatement("SELECT stored_path FROM shares WHERE token = ?");
            select.setString(1, token);
            ResultSet share = select.executeQuery();

            if (!share.next()) {
                return ResponseEntity.status(404).body(Map.of("error", "not found"));
            }

            String storedPath = share.getString("stored_path");
            try {
                Files.deleteIfExists(Paths.get(storedPath));
            } catch (IOException e) {
                logger.warn("Failed to delete file {}", storedPath);
            }

            PreparedStatement del = conn.prepareStatement("DELETE FROM shares WHERE token = ?");
            del.setString(1, token);
            del.executeUpdate();

            return ResponseEntity.ok(Map.of("status", "deleted"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static String shareBaseUrl(HttpServletRequest request) {
        return request.getScheme() + "://" + request.getHeader("Host") + "/";
    }
}
