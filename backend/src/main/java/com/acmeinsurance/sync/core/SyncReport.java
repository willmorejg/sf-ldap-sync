package com.acmeinsurance.sync.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Counters, warnings and errors for one sync run. Returned as JSON by the HTTP trigger. */
public final class SyncReport {

    private static final int MAX_MESSAGES = 200;

    /** What the counters count: accounts, agents, or a CSV feed's user type. */
    public static final String SCOPE_ACCOUNTS = "accounts";
    public static final String SCOPE_AGENTS = "agents";
    /** Outcomes counted per scope, as "scope.outcome" ({@link #key}). */
    public static final String COUNT_SOURCE_ROWS = "sourceRows";
    public static final String COUNT_SKIPPED_OTHER_TYPES = "skippedOtherTypes";
    public static final String COUNT_SKIPPED_INACTIVE = "skippedInactive";
    public static final String COUNT_CREATED = "created";
    public static final String COUNT_UPDATED = "updated";
    public static final String COUNT_UNCHANGED = "unchanged";
    public static final String COUNT_RENAMED = "renamed";
    public static final String COUNT_MOVED = "moved";
    public static final String COUNT_ADOPTED = "adopted";
    public static final String COUNT_DELETED = "deleted";
    public static final String COUNT_DEACTIVATED = "deactivated";
    public static final String COUNT_REACTIVATED = "reactivated";

    /** JSON fields of the report. */
    private static final String FIELD_SYNC = "sync";
    private static final String FIELD_STARTED = "started";
    private static final String FIELD_SECONDS = "seconds";
    private static final String FIELD_COUNTS = "counts";
    private static final String FIELD_ERRORS = "errors";
    private static final String FIELD_WARNINGS = "warnings";
    private static final String FIELD_MESSAGES_NOT_SHOWN = "messagesNotShown";
    private static final double MILLIS_PER_SECOND = 1000.0;

    private final String name;
    private final Instant started = Instant.now();
    private Instant finished;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private int droppedMessages;

    public SyncReport(final String name) {
        this.name = name;
    }

    /** A counter's name: "accounts.created", "agent.unchanged". */
    public static String key(final String scope, final String outcome) {
        return scope + "." + outcome;
    }

    public synchronized void inc(final String key) {
        counts.merge(key, 1, Integer::sum);
    }

    public synchronized void set(final String key, final int value) {
        counts.put(key, value);
    }

    public synchronized void warn(final String message) {
        add(warnings, message);
    }

    public synchronized void error(final String message) {
        counts.merge(FIELD_ERRORS, 1, Integer::sum);
        add(errors, message);
    }

    private void add(final List<String> list, final String message) {
        if (list.size() < MAX_MESSAGES) {
            list.add(message);
        } else {
            droppedMessages++;
        }
    }

    public synchronized boolean hasErrors() {
        return !errors.isEmpty();
    }

    public synchronized int count(final String key) {
        return counts.getOrDefault(key, 0);
    }

    public synchronized SyncReport finish() {
        finished = Instant.now();
        return this;
    }

    public synchronized Map<String, Object> toMap() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put(FIELD_SYNC, name);
        m.put(FIELD_STARTED, started.toString());
        if (finished != null) {
            m.put(FIELD_SECONDS, Duration.between(started, finished).toMillis() / MILLIS_PER_SECOND);
        }
        m.put(FIELD_COUNTS, new LinkedHashMap<>(counts));
        m.put(FIELD_ERRORS, new ArrayList<>(errors));
        m.put(FIELD_WARNINGS, new ArrayList<>(warnings));
        if (droppedMessages > 0) {
            m.put(FIELD_MESSAGES_NOT_SHOWN, droppedMessages);
        }
        return m;
    }

    @Override
    public synchronized String toString() {
        return name + " " + counts + (errors.isEmpty() ? "" : " first error: " + errors.get(0));
    }
}
