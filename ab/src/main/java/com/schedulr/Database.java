package com.schedulr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class Database {

    private final String dbPath;

    public Database(@Value("${schedulr.db-path}") String dbPath) {
        this.dbPath = dbPath;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    public void init() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS slots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    host TEXT NOT NULL,
                    start_at TEXT NOT NULL,
                    duration_minutes INTEGER NOT NULL,
                    booked INTEGER DEFAULT 0
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bookings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    slot_id INTEGER NOT NULL,
                    guest_name TEXT NOT NULL,
                    guest_email TEXT NOT NULL,
                    booked_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
