package com.acentra.catchy.telemetry.ingest;

import com.acentra.cache.telemetry.RegionSnapshot;
import com.acentra.cache.telemetry.TelemetryBatch;
import com.acentra.cache.telemetry.TelemetryEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Privacy guard: walks the raw JSON tree of a telemetry request and reports every property that the wire records do
 * not declare (for example {@code rawKey}, {@code memberId}, {@code value}). Only property <i>names</i> (with their
 * path) are ever reported, never values. Names are sanitized because they are client controlled.
 */
final class UnknownFieldScanner {
    private static final Set<String> BATCH = names(TelemetryBatch.class);
    private static final Set<String> EVENT = names(TelemetryEvent.class);
    private static final Set<String> SNAPSHOT = names(RegionSnapshot.class);
    private static final Set<String> RECENT = names(RegionSnapshot.RecentWindow.class);
    private static final Set<String> SHADOW = names(RegionSnapshot.Shadow.class);
    private static final int MAX_REPORTED = 10;

    private UnknownFieldScanner() {}

    static List<String> scanBatch(JsonNode root) {
        List<String> out = new ArrayList<>();
        check(root, BATCH, "", out);
        JsonNode events = root.get("events");
        if (events != null && events.isArray()) {
            for (int i = 0; i < events.size(); i++) check(events.get(i), EVENT, "events[" + i + "].", out);
        }
        JsonNode snapshots = root.get("snapshots");
        if (snapshots != null && snapshots.isArray()) {
            for (int i = 0; i < snapshots.size(); i++) {
                JsonNode s = snapshots.get(i);
                String prefix = "snapshots[" + i + "].";
                check(s, SNAPSHOT, prefix, out);
                if (s != null && s.isObject()) {
                    check(s.get("recentWindow"), RECENT, prefix + "recentWindow.", out);
                    check(s.get("shadow"), SHADOW, prefix + "shadow.", out);
                }
            }
        }
        return out;
    }

    static List<String> scanEvent(JsonNode root) {
        List<String> out = new ArrayList<>();
        check(root, EVENT, "", out);
        return out;
    }

    private static void check(JsonNode node, Set<String> allowed, String prefix, List<String> out) {
        if (node == null || !node.isObject()) return;
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            if (!allowed.contains(name)) out.add(sanitize(prefix + name));
        }
    }

    /** Keeps only characters that can appear in a property path; everything else becomes '?'. */
    static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 60; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '[' || c == ']';
            sb.append(ok ? c : '?');
        }
        return sb.toString();
    }

    /** Caps the number of reported names so a hostile payload cannot bloat the response or the audit row. */
    static List<String> capped(List<String> names) {
        if (names.size() <= MAX_REPORTED) return names;
        List<String> out = new ArrayList<>(names.subList(0, MAX_REPORTED));
        out.add("... and " + (names.size() - MAX_REPORTED) + " more");
        return out;
    }

    private static Set<String> names(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toUnmodifiableSet());
    }
}
