package com.transcribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class JobController {

    private static final Logger logger = LoggerFactory.getLogger(JobController.class);

    private final Database database;

    public JobController(Database database) {
        this.database = database;
    }

    @PostMapping("/jobs")
    public ResponseEntity<Object> createJob(@RequestBody Map<String, Object> data) {
        String audioUrl = (String) data.get("audio_url");
        String language = (String) data.getOrDefault("language", "en");

        if (audioUrl == null || audioUrl.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "audio_url required"));
        }

        logger.info("New job: url={}, language={}", audioUrl, language);

        try (Connection conn = database.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO jobs (audio_url, language) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            stmt.setString(1, audioUrl);
            stmt.setString(2, language);
            stmt.executeUpdate();
            ResultSet keys = stmt.getGeneratedKeys();
            long jobId = keys.next() ? keys.getLong(1) : -1;
            return ResponseEntity.status(201).body(Map.of("id", jobId, "status", "queued"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<Object> getJob(@PathVariable long id) {
        try (Connection conn = database.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM jobs WHERE id = ?");
            stmt.setLong(1, id);
            List<Map<String, Object>> results = rows(stmt.executeQuery());
            if (results.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "not found"));
            }
            return ResponseEntity.ok(results.get(0));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/jobs")
    public ResponseEntity<Object> listJobs() {
        try (Connection conn = database.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT id, status, created_at FROM jobs ORDER BY created_at DESC LIMIT 100");
            return ResponseEntity.ok(rows(stmt.executeQuery()));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columns; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            results.add(row);
        }
        return results;
    }
}
