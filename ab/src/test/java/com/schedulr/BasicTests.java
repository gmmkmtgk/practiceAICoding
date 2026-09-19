package com.schedulr;

import com.fasterxml.jackson.databind.ObjectMapper;
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
            DB_FILE = File.createTempFile("schedulr-test", ".db");
            DB_FILE.deleteOnExit();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("schedulr.db-path", DB_FILE::getAbsolutePath);
        registry.add("schedulr.email.latency-ms", () -> "10");
        registry.add("schedulr.email.failure-rate", () -> "0");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Database database;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clean() throws Exception {
        try (Connection conn = database.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM bookings");
            stmt.execute("DELETE FROM slots");
        }
    }

    private long createSlot(String host, String startAt) throws Exception {
        MvcResult result = mockMvc.perform(post("/slots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "host", host, "start_at", startAt, "duration_minutes", 30))))
                .andExpect(status().isCreated())
                .andReturn();
        Map<String, Object> body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return ((Number) body.get("id")).longValue();
    }

    @Test
    void createSlot() throws Exception {
        MvcResult result = mockMvc.perform(post("/slots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "host", "alice", "start_at", "2026-06-01T14:00:00", "duration_minutes", 30))))
                .andExpect(status().isCreated())
                .andReturn();
        Map<String, Object> body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body).containsKey("id");
    }

    @Test
    void listsAvailability() throws Exception {
        createSlot("alice", "2026-06-01T14:00:00");
        createSlot("alice", "2026-06-01T15:00:00");

        MvcResult result = mockMvc.perform(get("/availability/alice"))
                .andExpect(status().isOk())
                .andReturn();
        List<?> slots = mapper.readValue(result.getResponse().getContentAsString(), List.class);
        assertThat(slots).hasSize(2);
    }

    @Test
    void bookSlot() throws Exception {
        long slotId = createSlot("alice", "2026-06-01T14:00:00");

        mockMvc.perform(post("/book")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "slot_id", slotId, "guest_name", "Bob", "guest_email", "bob@example.com"))))
                .andExpect(status().isCreated());
    }

    @Test
    void doubleBookReturns409() throws Exception {
        long slotId = createSlot("alice", "2026-06-01T14:00:00");

        mockMvc.perform(post("/book")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "slot_id", slotId, "guest_name", "Bob", "guest_email", "b@e.com"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/book")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "slot_id", slotId, "guest_name", "Carol", "guest_email", "c@e.com"))))
                .andExpect(status().isConflict());
    }

    @Test
    void listsBookingsForHost() throws Exception {
        long slotId = createSlot("alice", "2026-06-01T14:00:00");
        mockMvc.perform(post("/book")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "slot_id", slotId, "guest_name", "Bob", "guest_email", "b@e.com"))))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/bookings/alice"))
                .andExpect(status().isOk())
                .andReturn();
        List<?> bookings = mapper.readValue(result.getResponse().getContentAsString(), List.class);
        assertThat(bookings).hasSize(1);
    }
}
