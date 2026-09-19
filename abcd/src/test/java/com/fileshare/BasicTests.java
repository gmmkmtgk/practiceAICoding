package com.fileshare;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BasicTests {

    static final File DB_FILE;
    static final File UPLOAD_DIR;

    static {
        try {
            DB_FILE = File.createTempFile("fileshare-test", ".db");
            DB_FILE.deleteOnExit();
            UPLOAD_DIR = Files.createTempDirectory("fileshare-uploads").toFile();
            UPLOAD_DIR.deleteOnExit();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fileshare.db-path", DB_FILE::getAbsolutePath);
        registry.add("fileshare.upload-dir", UPLOAD_DIR::getAbsolutePath);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Database database;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clean() throws Exception {
        try (Connection conn = database.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM shares");
        }
    }

    @Test
    void uploadReturnsToken() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/upload")
                .file(new MockMultipartFile("file", "hello.txt", "text/plain", "hello world".getBytes())))
                .andExpect(status().isCreated())
                .andReturn();
        Map<String, Object> body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body).containsKey("token");
    }

    @Test
    void downloadReturnsFile() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/upload")
                .file(new MockMultipartFile("file", "hello.txt", "text/plain", "hello world".getBytes())))
                .andExpect(status().isCreated())
                .andReturn();
        String token = (String) mapper.readValue(result.getResponse().getContentAsString(), Map.class).get("token");

        MvcResult download = mockMvc.perform(get("/" + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo("hello world".getBytes());
    }

    @Test
    void infoReturnsMetadata() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/upload")
                .file(new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes())))
                .andExpect(status().isCreated())
                .andReturn();
        String token = (String) mapper.readValue(result.getResponse().getContentAsString(), Map.class).get("token");

        MvcResult info = mockMvc.perform(get("/info/" + token))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> body = mapper.readValue(info.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("filename")).isEqualTo("hello.txt");
    }

    @Test
    void maxDownloadsEnforced() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/upload")
                .file(new MockMultipartFile("file", "test.txt", "text/plain", "data".getBytes()))
                .param("max_downloads", "2"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = (String) mapper.readValue(result.getResponse().getContentAsString(), Map.class).get("token");

        mockMvc.perform(get("/" + token)).andExpect(status().isOk());
        mockMvc.perform(get("/" + token)).andExpect(status().isOk());
        mockMvc.perform(get("/" + token)).andExpect(status().isGone());
    }

    @Test
    void deleteRemovesShare() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/upload")
                .file(new MockMultipartFile("file", "test.txt", "text/plain", "data".getBytes())))
                .andExpect(status().isCreated())
                .andReturn();
        String token = (String) mapper.readValue(result.getResponse().getContentAsString(), Map.class).get("token");

        mockMvc.perform(delete("/" + token)).andExpect(status().isOk());
        mockMvc.perform(get("/info/" + token)).andExpect(status().isNotFound());
    }

    @Test
    void unknownTokenReturns404() throws Exception {
        mockMvc.perform(get("/notreal")).andExpect(status().isNotFound());
    }
}
