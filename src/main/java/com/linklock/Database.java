package com.linklock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class Database {

    private final String dbPath;

    public Database(@Value("${linklock.db-path}") String dbPath) {
        this.dbPath = dbPath;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    public void init() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    email TEXT UNIQUE NOT NULL
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS magic_tokens (
                    token TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    redirect_to TEXT,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    used INTEGER DEFAULT 0
                )
            """);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
