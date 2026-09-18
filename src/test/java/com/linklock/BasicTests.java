package com.linklock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
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
            DB_FILE = File.createTempFile("linklock-test", ".db");
            DB_FILE.deleteOnExit();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("linklock.db-path", DB_FILE::getAbsolutePath);
        registry.add("linklock.email.latency-ms", () -> "10");
        registry.add("linklock.email.failure-rate", () -> "0");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Database database;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clean() throws Exception {
        try (Connection conn = database.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM magic_tokens");
            stmt.execute("DELETE FROM users");
        }
    }

    private void requestLink(String email) throws Exception {
        mockMvc.perform(post("/auth/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk());
    }

    private String latestToken() throws Exception {
        try (Connection conn = database.getConnection(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT token FROM magic_tokens LIMIT 1");
            rs.next();
            return rs.getString("token");
        }
    }

    @Test
    void requestReturns200() throws Exception {
        mockMvc.perform(post("/auth/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", "test@example.com"))))
                .andExpect(status().isOk());
    }

    @Test
    void verifyLogsInUser() throws Exception {
        requestLink("alice@example.com");
        String token = latestToken();

        mockMvc.perform(get("/auth/verify").param("token", token))
                .andExpect(status().isFound());
    }

    @Test
    void meRequiresAuth() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meAfterVerify() throws Exception {
        requestLink("bob@example.com");
        String token = latestToken();

        MvcResult verify = mockMvc.perform(get("/auth/verify").param("token", token))
                .andExpect(status().isFound())
                .andReturn();
        MockHttpSession session = (MockHttpSession) verify.getRequest().getSession(false);

        MvcResult result = mockMvc.perform(get("/me").session(session))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("email")).isEqualTo("bob@example.com");
    }

    @Test
    void invalidTokenRejected() throws Exception {
        mockMvc.perform(get("/auth/verify").param("token", "not_a_real_token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutClearsSession() throws Exception {
        requestLink("carol@example.com");
        String token = latestToken();

        MvcResult verify = mockMvc.perform(get("/auth/verify").param("token", token))
                .andExpect(status().isFound())
                .andReturn();
        MockHttpSession session = (MockHttpSession) verify.getRequest().getSession(false);

        mockMvc.perform(post("/auth/logout").session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/me").session(session))
                .andExpect(status().isUnauthorized());
    }
}
