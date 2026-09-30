package io.cachelab.server.trace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an access trace (SPEC 9.5): one lookup per line, either {@code key} or {@code
 * timestamp,key}; the timestamp is ignored (replay order is file order). Blank lines are skipped;
 * the first non-blank line is a header, and skipped, when its key column is literally {@code key}
 * (any case). Fields are plain comma-separated text: CSV quoting is not supported.
 *
 * <p>Limits: at most {@value #MAX_ROWS} rows and keys of at most {@value #MAX_KEY_LENGTH}
 * characters. Equal keys share one {@code String} instance, so a large trace with a modest key
 * space stays small in memory.
 *
 * <p>Thread-safety: stateless. Complexity: O(bytes).
 */
public final class TraceParser {

  /** Maximum number of rows in a trace. */
  public static final int MAX_ROWS = 1_000_000;

  /** Maximum key length, in characters. */
  public static final int MAX_KEY_LENGTH = 200;

  private TraceParser() {}

  /**
   * Parses a trace. The stream is read to the end but not closed.
   *
   * @param in UTF-8 text; never {@code null}
   * @return the keys in file order; never empty
   * @throws InvalidTraceException if the trace has no rows or a line is malformed (the message
   *     names the line)
   * @throws TraceTooLargeException if it has more than {@value #MAX_ROWS} rows
   */
  public static List<String> parse(InputStream in) {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    List<String> keys = new ArrayList<>();
    Map<String, String> canonical = new HashMap<>();
    boolean first = true;
    int lineNo = 0;
    try {
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        lineNo++;
        String trimmed = (lineNo == 1 ? line.replace("﻿", "") : line).strip();
        if (trimmed.isEmpty()) {
          continue;
        }
        String key = keyOf(trimmed, lineNo);
        if (first && key.equalsIgnoreCase("key")) {
          first = false;
          continue; // header row
        }
        first = false;
        if (keys.size() >= MAX_ROWS) {
          throw new TraceTooLargeException("The trace has more than " + MAX_ROWS + " rows");
        }
        keys.add(canonical.computeIfAbsent(key, k -> k));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (keys.isEmpty()) {
      throw new InvalidTraceException("The trace is empty: expected one 'key' or 'timestamp,key'"
          + " per line");
    }
    return keys;
  }

  private static String keyOf(String line, int lineNo) {
    String[] fields = line.split(",", -1);
    if (fields.length > 2) {
      throw new InvalidTraceException(
          "Line " + lineNo + ": expected 'key' or 'timestamp,key' but found " + fields.length
              + " fields");
    }
    String key = fields[fields.length - 1].strip();
    if (key.isEmpty()) {
      throw new InvalidTraceException("Line " + lineNo + ": the key is empty");
    }
    if (key.length() > MAX_KEY_LENGTH) {
      throw new InvalidTraceException(
          "Line " + lineNo + ": the key is longer than " + MAX_KEY_LENGTH + " characters");
    }
    return key;
  }
}
