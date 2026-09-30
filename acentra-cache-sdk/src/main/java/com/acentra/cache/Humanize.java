package com.acentra.cache;

import java.util.Locale;

/** Small formatting helpers for human-readable decision reasons. */
final class Humanize {
    private Humanize() {}

    static String bytes(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    static String millis(long ms) {
        if (ms < 1000) return ms + " ms";
        long s = ms / 1000;
        if (s < 120) return s + " seconds";
        long m = s / 60;
        if (m < 120) return m + " minutes";
        return (m / 60) + " hours";
    }

    static String percent(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.05) return String.format(Locale.ROOT, "%.0f%%", v);
        return String.format(Locale.ROOT, "%.1f%%", v);
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
