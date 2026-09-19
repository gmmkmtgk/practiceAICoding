package com.fileshare;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class Database {

    private final String dbPath;

    public Database(@Value("${fileshare.db-path}") String dbPath) {
        this.dbPath = dbPath;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    public void init() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS shares (
                    token TEXT PRIMARY KEY,
                    filename TEXT NOT NULL,
                    stored_path TEXT NOT NULL,
                    content_type TEXT,
                    size_bytes INTEGER,
                    uploaded_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    expires_at TEXT,
                    max_downloads INTEGER,
                    download_count INTEGER DEFAULT 0
                )
            """);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
