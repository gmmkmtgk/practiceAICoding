package com.transcribe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class Database {

    private final String dbPath;

    public Database(@Value("${transcribe.db-path}") String dbPath) {
        this.dbPath = dbPath;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    public void init() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    audio_url TEXT NOT NULL,
                    language TEXT,
                    status TEXT DEFAULT 'queued',
                    result TEXT,
                    error TEXT,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    claimed_at TEXT,
                    claimed_by TEXT,
                    completed_at TEXT
                )
            """);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
