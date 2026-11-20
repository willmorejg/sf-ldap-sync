package com.acmeinsurance.ldapsync.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Counters, warnings and errors for one sync run. Returned as JSON by the HTTP trigger. */
public final class SyncReport {

    private static final int MAX_MESSAGES = 200;

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
        counts.merge("errors", 1, Integer::sum);
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
        m.put("sync", name);
        m.put("started", started.toString());
        if (finished != null) {
            m.put("seconds", Duration.between(started, finished).toMillis() / 1000.0);
        }
        m.put("counts", new LinkedHashMap<>(counts));
        m.put("errors", new ArrayList<>(errors));
        m.put("warnings", new ArrayList<>(warnings));
        if (droppedMessages > 0) {
            m.put("messagesNotShown", droppedMessages);
        }
        return m;
    }

    @Override
    public synchronized String toString() {
        return name + " " + counts + (errors.isEmpty() ? "" : " first error: " + errors.get(0));
    }
}
