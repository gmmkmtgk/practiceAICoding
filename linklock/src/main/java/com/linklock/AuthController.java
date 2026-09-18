package com.linklock;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

@RestController
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final EmailService emailService;
    private final Database database;

    public AuthController(EmailService emailService, Database database) {
        this.emailService = emailService;
        this.database = database;
    }

    @PostMapping("/auth/request")
    public ResponseEntity<Object> requestLink(@RequestBody Map<String, Object> data) {
        String email = (String) data.get("email");
        String redirectTo = (String) data.getOrDefault("redirect_to", "/me");

        if (email == null || email.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "email required"));
        }

        try (Connection conn = database.getConnection()) {
            long userId;
            boolean newUser;

            PreparedStatement select = conn.prepareStatement("SELECT id FROM users WHERE email = ?");
            select.setString(1, email);
            ResultSet user = select.executeQuery();

            if (user.next()) {
                userId = user.getLong("id");
                newUser = false;
            } else {
                PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO users (email) VALUES (?)",
                    Statement.RETURN_GENERATED_KEYS);
                insert.setString(1, email);
                insert.executeUpdate();
                ResultSet keys = insert.getGeneratedKeys();
                userId = keys.next() ? keys.getLong(1) : -1;
                newUser = true;
            }

            String token = emailService.generateToken();
            PreparedStatement tokenInsert = conn.prepareStatement(
                "INSERT INTO magic_tokens (token, user_id, redirect_to) VALUES (?, ?, ?)");
            tokenInsert.setString(1, token);
            tokenInsert.setLong(2, userId);
            tokenInsert.setString(3, redirectTo);
            tokenInsert.executeUpdate();

            logger.info("Sending magic link to {}: token={}, redirect_to={}", email, token, redirectTo);

            emailService.sendMagicLink(email, token, redirectTo);

            if (newUser) {
                return ResponseEntity.ok(Map.of("status", "user created, link sent"));
            }
            return ResponseEntity.ok(Map.of("status", "link sent"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/auth/verify")
    public ResponseEntity<Object> verify(@RequestParam(required = false) String token, HttpSession session) {
        try (Connection conn = database.getConnection()) {
            PreparedStatement select = conn.prepareStatement("SELECT * FROM magic_tokens WHERE token = ?");
            select.setString(1, token);
            ResultSet record = select.executeQuery();

            if (!record.next()) {
                return ResponseEntity.status(401).body(Map.of("error", "invalid token"));
            }

            if (record.getInt("used") != 0) {
                return ResponseEntity.status(401).body(Map.of("error", "token already used"));
            }

            long userId = record.getLong("user_id");
            String redirectTo = record.getString("redirect_to");
            if (redirectTo == null || redirectTo.isEmpty()) {
                redirectTo = "/me";
            }

            PreparedStatement update = conn.prepareStatement("UPDATE magic_tokens SET used = 1 WHERE token = ?");
            update.setString(1, token);
            update.executeUpdate();

            session.setAttribute("user_id", userId);

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(redirectTo));
            return new ResponseEntity<>(headers, HttpStatus.FOUND);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Object> me(HttpSession session) {
        Object userId = session.getAttribute("user_id");
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        }

        try (Connection conn = database.getConnection()) {
            PreparedStatement select = conn.prepareStatement("SELECT id, email FROM users WHERE id = ?");
            select.setLong(1, ((Number) userId).longValue());
            ResultSet user = select.executeQuery();

            if (!user.next()) {
                return ResponseEntity.status(404).body(Map.of("error", "user not found"));
            }

            return ResponseEntity.ok(Map.of("id", user.getLong("id"), "email", user.getString("email")));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Object> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("status", "logged out"));
    }
}
