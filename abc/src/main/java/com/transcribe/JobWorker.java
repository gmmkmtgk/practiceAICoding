package com.transcribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;

@Component
public class JobWorker {

    private static final Logger logger = LoggerFactory.getLogger(JobWorker.class);

    private final Database database;
    private final TranscriptionService transcriptionService;
    private final String workerId;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private volatile boolean running = false;

    public JobWorker(Database database, TranscriptionService transcriptionService) {
        this.database = database;
        this.transcriptionService = transcriptionService;
        byte[] bytes = new byte[4];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        this.workerId = sb.toString();
    }

    public Long processOneJob() {
        try (Connection conn = database.getConnection()) {
            long jobId;
            String audioUrl;
            String language;

            PreparedStatement select = conn.prepareStatement(
                "SELECT * FROM jobs WHERE status = 'queued' ORDER BY id LIMIT 1");
            ResultSet rs = select.executeQuery();
            if (!rs.next()) {
                return null;
            }
            jobId = rs.getLong("id");
            audioUrl = rs.getString("audio_url");
            language = rs.getString("language");

            PreparedStatement claim = conn.prepareStatement("""
                UPDATE jobs SET status = 'running',
                                claimed_at = CURRENT_TIMESTAMP,
                                claimed_by = ?
                WHERE id = ?
            """);
            claim.setString(1, workerId);
            claim.setLong(2, jobId);
            claim.executeUpdate();

            try {
                byte[] audioData = download(audioUrl);
                String result = transcriptionService.transcribeAudio(audioData, language);

                PreparedStatement done = conn.prepareStatement("""
                    UPDATE jobs SET status = 'done',
                                    result = ?,
                                    completed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                """);
                done.setString(1, result);
                done.setLong(2, jobId);
                done.executeUpdate();
            } catch (Exception e) {
                logger.error("Transcription failed for job {}: {}", jobId, e.getMessage());
            }

            return jobId;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] download(String audioUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(audioUrl))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<InputStream> response = httpClient.send(
            request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            return body.readAllBytes();
        }
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        Thread thread = new Thread(this::loop);
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                if (processOneJob() == null) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.error("Worker loop error: {}", e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
