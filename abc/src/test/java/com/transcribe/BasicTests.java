package com.transcribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BasicTests {

    static final File DB_FILE;

    static {
        try {
            DB_FILE = File.createTempFile("transcribe-test", ".db");
            DB_FILE.deleteOnExit();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("transcribe.db-path", DB_FILE::getAbsolutePath);
        registry.add("transcribe.transcription.latency-ms", () -> "10");
        registry.add("transcribe.transcription.failure-rate", () -> "0");
        registry.add("transcribe.worker.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Database database;

    @Autowired
    private JobWorker worker;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clean() throws Exception {
        try (Connection conn = database.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM jobs");
        }
    }

    private long createJob(String audioUrl) throws Exception {
        MvcResult result = mockMvc.perform(post("/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("audio_url", audioUrl))))
                .andExpect(status().isCreated())
                .andReturn();
        Map<String, Object> body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return ((Number) body.get("id")).longValue();
    }

    @Test
    void createJob() throws Exception {
        MvcResult result = mockMvc.perform(post("/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "audio_url", "https://example.com/audio.mp3", "language", "en"))))
                .andExpect(status().isCreated())
                .andReturn();
        Map<String, Object> body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("status")).isEqualTo("queued");
    }

    @Test
    void getJob() throws Exception {
        long jobId = createJob("https://example.com/audio.mp3");

        MvcResult result = mockMvc.perform(get("/jobs/" + jobId))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("status")).isEqualTo("queued");
    }

    @Test
    void processCompletesJob() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange -> {
            byte[] payload = "fake audio data".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/audio";
            long jobId = createJob(url);

            worker.processOneJob();

            MvcResult result = mockMvc.perform(get("/jobs/" + jobId))
                    .andExpect(status().isOk())
                    .andReturn();
            Map<String, Object> body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
            assertThat(body.get("status")).isEqualTo("done");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listJobs() throws Exception {
        createJob("https://example.com/a.mp3");
        createJob("https://example.com/b.mp3");

        MvcResult result = mockMvc.perform(get("/jobs"))
                .andExpect(status().isOk())
                .andReturn();
        List<?> jobs = mapper.readValue(result.getResponse().getContentAsString(), List.class);
        assertThat(jobs).hasSize(2);
    }

    @Test
    void invalidJobReturns404() throws Exception {
        mockMvc.perform(get("/jobs/9999"))
                .andExpect(status().isNotFound());
    }
}
