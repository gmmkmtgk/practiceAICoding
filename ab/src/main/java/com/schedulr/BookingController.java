package com.schedulr;

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
public class BookingController {

    private static final Logger logger = LoggerFactory.getLogger(BookingController.class);

    private final EmailService emailService;
    private final Database database;

    public BookingController(EmailService emailService, Database database) {
        this.emailService = emailService;
        this.database = database;
    }

    @PostMapping("/slots")
    public ResponseEntity<Object> createSlot(@RequestBody Map<String, Object> data) {
        String host = (String) data.get("host");
        String startAt = (String) data.get("start_at");
        int duration = ((Number) data.getOrDefault("duration_minutes", 30)).intValue();

        try (Connection conn = database.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO slots (host, start_at, duration_minutes) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            stmt.setString(1, host);
            stmt.setString(2, startAt);
            stmt.setInt(3, duration);
            stmt.executeUpdate();
            ResultSet keys = stmt.getGeneratedKeys();
            long slotId = keys.next() ? keys.getLong(1) : -1;
            return ResponseEntity.status(201).body(Map.of("id", slotId));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/availability/{host}")
    public ResponseEntity<Object> availability(@PathVariable String host) {
        try (Connection conn = database.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM slots WHERE host = ? AND booked = 0 ORDER BY start_at");
            stmt.setString(1, host);
            return ResponseEntity.ok(rows(stmt.executeQuery()));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping("/book")
    public ResponseEntity<Object> book(@RequestBody Map<String, Object> data) {
        long slotId = ((Number) data.get("slot_id")).longValue();
        String guestName = (String) data.get("guest_name");
        String guestEmail = (String) data.get("guest_email");

        logger.info("Booking slot {} for {} <{}>", slotId, guestName, guestEmail);

        try (Connection conn = database.getConnection()) {
            PreparedStatement select = conn.prepareStatement("SELECT * FROM slots WHERE id = ?");
            select.setLong(1, slotId);
            ResultSet slot = select.executeQuery();

            if (!slot.next()) {
                return ResponseEntity.status(404).body(Map.of("error", "slot not found"));
            }
            if (slot.getInt("booked") != 0) {
                return ResponseEntity.status(409).body(Map.of("error", "slot already booked"));
            }

            String host = slot.getString("host");
            String startAt = slot.getString("start_at");

            PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO bookings (slot_id, guest_name, guest_email) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            insert.setLong(1, slotId);
            insert.setString(2, guestName);
            insert.setString(3, guestEmail);
            insert.executeUpdate();
            ResultSet keys = insert.getGeneratedKeys();
            long bookingId = keys.next() ? keys.getLong(1) : -1;

            PreparedStatement update = conn.prepareStatement("UPDATE slots SET booked = 1 WHERE id = ?");
            update.setLong(1, slotId);
            update.executeUpdate();

            try {
                emailService.sendBookingConfirmation(guestEmail, host, startAt);
            } catch (Exception e) {
                logger.error("Email failed: {}", e.getMessage());
            }

            return ResponseEntity.status(201).body(Map.of("booking_id", bookingId));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/bookings/{host}")
    public ResponseEntity<Object> listBookings(@PathVariable String host) {
        try (Connection conn = database.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("""
                SELECT bookings.*, slots.start_at, slots.duration_minutes
                FROM bookings JOIN slots ON bookings.slot_id = slots.id
                WHERE slots.host = ?
                ORDER BY slots.start_at
            """);
            stmt.setString(1, host);
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
