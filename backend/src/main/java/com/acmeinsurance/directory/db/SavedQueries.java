package com.acmeinsurance.directory.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

/** LDAP queries developers saved on the query page. */
@ApplicationScoped
public class SavedQueries {

    /** @param attributes requested attributes, comma-separated; empty for all user attributes */
    public record SavedQuery(long id, String name, String description, String baseDn, String scope, String filter,
                             String attributes, String createdBy, String createdAt) {
    }

    private static final String SELECT_ALL = "SELECT * FROM saved_query ORDER BY lower(name), id";
    private static final String INSERT = "INSERT INTO saved_query"
            + " (name, description, base_dn, scope, filter, attributes, created_by, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String DELETE = "DELETE FROM saved_query WHERE id = ?";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_NAME = "name";
    private static final String COLUMN_DESCRIPTION = "description";
    private static final String COLUMN_BASE_DN = "base_dn";
    private static final String COLUMN_SCOPE = "scope";
    private static final String COLUMN_FILTER = "filter";
    private static final String COLUMN_ATTRIBUTES = "attributes";
    private static final String COLUMN_CREATED_BY = "created_by";
    private static final String COLUMN_CREATED_AT = "created_at";

    private final Database db;

    public SavedQueries(final Database db) {
        this.db = db;
    }

    public List<SavedQuery> all() throws SQLException {
        try (Connection c = db.connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(SELECT_ALL)) {
            final List<SavedQuery> out = new ArrayList<>();
            while (r.next()) {
                out.add(new SavedQuery(r.getLong(COLUMN_ID), r.getString(COLUMN_NAME), r.getString(COLUMN_DESCRIPTION),
                        r.getString(COLUMN_BASE_DN), r.getString(COLUMN_SCOPE), r.getString(COLUMN_FILTER),
                        r.getString(COLUMN_ATTRIBUTES), r.getString(COLUMN_CREATED_BY),
                        r.getString(COLUMN_CREATED_AT)));
            }
            return out;
        }
    }

    public synchronized SavedQuery add(final SavedQuery q) throws SQLException {
        final String now = Instant.now().toString();
        try (Connection c = db.connect(); PreparedStatement s = c.prepareStatement(
                INSERT, Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, q.name());
            s.setString(2, q.description());
            s.setString(3, q.baseDn());
            s.setString(4, q.scope());
            s.setString(5, q.filter());
            s.setString(6, q.attributes());
            s.setString(7, q.createdBy());
            s.setString(8, now);
            s.executeUpdate();
            try (ResultSet keys = s.getGeneratedKeys()) {
                keys.next();
                return new SavedQuery(keys.getLong(1), q.name(), q.description(), q.baseDn(), q.scope(), q.filter(),
                        q.attributes(), q.createdBy(), now);
            }
        }
    }

    /** @return false if there was no such query */
    public synchronized boolean delete(final long id) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement s = c.prepareStatement(DELETE)) {
            s.setLong(1, id);
            return s.executeUpdate() == 1;
        }
    }
}
