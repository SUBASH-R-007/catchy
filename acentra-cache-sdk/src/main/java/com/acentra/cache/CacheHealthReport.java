package com.acentra.cache;

import java.util.List;

/** Deterministic cache health with the exact reasons behind the status. {@code score} is 0..100 (0 when UNKNOWN). */
public record CacheHealthReport(CacheHealthStatus status, int score, List<String> reasons) {}
