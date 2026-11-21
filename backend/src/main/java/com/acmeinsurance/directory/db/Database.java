package com.acmeinsurance.directory.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.jboss.logging.Logger;

import com.acmeinsurance.directory.DirectoryConfig;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The app's own SQLite database: the audit log and saved queries. Nothing in it is
 * a copy of directory data. A connection per call keeps it simple; writes are
 * serialized by the callers' {@code synchronized} methods.
 */
@ApplicationScoped
public class Database {

    private static final Logger LOG = Logger.getLogger(Database.class);
    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS audit_log (
            id      INTEGER PRIMARY KEY AUTOINCREMENT,
            at      TEXT NOT NULL,
            actor   TEXT NOT NULL,
            action  TEXT NOT NULL,
            kind    TEXT,
            dn      TEXT,
            details TEXT
        )""",
        "CREATE INDEX IF NOT EXISTS audit_log_at ON audit_log (at)",
        """
        CREATE TABLE IF NOT EXISTS saved_query (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            name        TEXT NOT NULL,
            description TEXT,
            base_dn     TEXT NOT NULL,
            scope       TEXT NOT NULL,
            filter      TEXT NOT NULL,
            attributes  TEXT,
            created_by  TEXT NOT NULL,
            created_at  TEXT NOT NULL
        )""",
    };

    private final String url;
    private boolean ready;

    public Database(final DirectoryConfig cfg) {
        this.url = "jdbc:sqlite:" + cfg.db().path();
    }

    /** A new connection; the tables are created on the first call. */
    public synchronized Connection connect() throws SQLException {
        if (!ready) {
            createTables();
            ready = true;
        }
        return DriverManager.getConnection(url);
    }

    private void createTables() throws SQLException {
        final Path file = Path.of(url.substring("jdbc:sqlite:".length())).toAbsolutePath();
        try {
            Files.createDirectories(file.getParent());
        } catch (final IOException e) {
            throw new SQLException("cannot create " + file.getParent(), e);
        }
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            for (final String sql : SCHEMA) {
                s.execute(sql);
            }
        }
        LOG.infof("SQLite database: %s", file);
    }
}
