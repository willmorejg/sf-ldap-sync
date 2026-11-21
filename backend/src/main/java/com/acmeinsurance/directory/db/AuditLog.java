package com.acmeinsurance.directory.db;

import static com.acmeinsurance.common.Constants.PARAM_LIMIT;
import static com.acmeinsurance.common.Constants.PARAM_Q;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.camel.Header;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * Who changed what through this app. Records attribute names, never values, so
 * passwords and personal data stay out of it. The directory itself also records
 * modifiersName (always svc-directory for changes made here).
 */
@ApplicationScoped
@Named("audit")
public class AuditLog {

    private static final String INSERT =
            "INSERT INTO audit_log (at, actor, action, kind, dn, details) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String SELECT = "SELECT id, at, actor, action, kind, dn, details FROM audit_log";
    private static final String WHERE_MATCHES =
            " WHERE lower(actor) LIKE ? OR lower(dn) LIKE ? OR lower(action) LIKE ?";
    private static final String NEWEST_FIRST = " ORDER BY id DESC LIMIT ?";
    private static final String LIKE_ANY = "%";
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;
    /** Column names, which are also the JSON fields of an audit record. */
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_AT = "at";
    private static final String COLUMN_ACTOR = "actor";
    private static final String COLUMN_ACTION = "action";
    private static final String COLUMN_KIND = "kind";
    private static final String COLUMN_DN = "dn";
    private static final String COLUMN_DETAILS = "details";

    private final Database db;

    public AuditLog(final Database db) {
        this.db = db;
    }

    public synchronized void record(final String actor, final String action, final String kind, final String dn,
                                    final String details) {
        try (Connection c = db.connect();
             PreparedStatement s = c.prepareStatement(INSERT)) {
            s.setString(1, Instant.now().toString());
            s.setString(2, actor);
            s.setString(3, action);
            s.setString(4, kind);
            s.setString(5, dn);
            s.setString(6, details);
            s.executeUpdate();
        } catch (final SQLException e) {
            throw new IllegalStateException("audit log write failed: " + e.getMessage(), e);
        }
    }

    /**
     * @param q matches actor, DN or action (substring, any case); null for all
     */
    public List<Map<String, Object>> recent(@Header(PARAM_Q) final String q, @Header(PARAM_LIMIT) final Integer limit)
            throws SQLException {
        final String like = q == null || q.isBlank() ? null : LIKE_ANY + q.trim().toLowerCase(Locale.ROOT) + LIKE_ANY;
        final String sql = SELECT + (like == null ? "" : WHERE_MATCHES) + NEWEST_FIRST;
        try (Connection c = db.connect(); PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            if (like != null) {
                s.setString(i++, like);
                s.setString(i++, like);
                s.setString(i++, like);
            }
            s.setInt(i, Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, MAX_LIMIT)));
            final List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    final Map<String, Object> m = new LinkedHashMap<>();
                    m.put(COLUMN_ID, r.getLong(COLUMN_ID));
                    m.put(COLUMN_AT, r.getString(COLUMN_AT));
                    m.put(COLUMN_ACTOR, r.getString(COLUMN_ACTOR));
                    m.put(COLUMN_ACTION, r.getString(COLUMN_ACTION));
                    m.put(COLUMN_KIND, r.getString(COLUMN_KIND));
                    m.put(COLUMN_DN, r.getString(COLUMN_DN));
                    m.put(COLUMN_DETAILS, r.getString(COLUMN_DETAILS));
                    out.add(m);
                }
            }
            return out;
        }
    }
}
