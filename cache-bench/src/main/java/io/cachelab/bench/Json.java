package io.cachelab.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader (objects, arrays, strings, numbers, booleans, null) for JMH result files,
 * so the exporter needs no JSON library. Numbers parse to {@link Double}; NaN written by JMH as a
 * string {@code "NaN"} stays a string.
 */
final class Json {

  private final String s;
  private int i;

  private Json(String s) {
    this.s = s;
  }

  static Object parse(String text) {
    Json p = new Json(text);
    Object value = p.value();
    p.skipWhitespace();
    if (p.i != p.s.length()) {
      throw p.error("trailing characters");
    }
    return value;
  }

  static String quote(String text) {
    StringBuilder sb = new StringBuilder("\"");
    for (char c : text.toCharArray()) {
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }

  private Object value() {
    skipWhitespace();
    if (i >= s.length()) {
      throw error("unexpected end");
    }
    char c = s.charAt(i);
    return switch (c) {
      case '{' -> object();
      case '[' -> array();
      case '"' -> string();
      case 't' -> literal("true", Boolean.TRUE);
      case 'f' -> literal("false", Boolean.FALSE);
      case 'n' -> literal("null", null);
      default -> number();
    };
  }

  private Map<String, Object> object() {
    Map<String, Object> map = new LinkedHashMap<>();
    i++;
    skipWhitespace();
    if (peek('}')) {
      return map;
    }
    do {
      skipWhitespace();
      String key = string();
      skipWhitespace();
      expect(':');
      map.put(key, value());
      skipWhitespace();
    } while (consume(','));
    expect('}');
    return map;
  }

  private List<Object> array() {
    List<Object> list = new ArrayList<>();
    i++;
    skipWhitespace();
    if (peek(']')) {
      return list;
    }
    do {
      list.add(value());
      skipWhitespace();
    } while (consume(','));
    expect(']');
    return list;
  }

  private String string() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (i < s.length() && s.charAt(i) != '"') {
      char c = s.charAt(i++);
      if (c == '\\') {
        char e = s.charAt(i++);
        switch (e) {
          case 'n' -> sb.append('\n');
          case 't' -> sb.append('\t');
          case 'r' -> sb.append('\r');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'u' -> {
            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
          }
          default -> sb.append(e);
        }
      } else {
        sb.append(c);
      }
    }
    expect('"');
    return sb.toString();
  }

  private Double number() {
    int start = i;
    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
      i++;
    }
    if (start == i) {
      throw error("unexpected character '" + s.charAt(i) + "'");
    }
    return Double.valueOf(s.substring(start, i));
  }

  private Object literal(String word, Object value) {
    if (!s.startsWith(word, i)) {
      throw error("expected " + word);
    }
    i += word.length();
    return value;
  }

  private void skipWhitespace() {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
  }

  private boolean peek(char c) {
    if (i < s.length() && s.charAt(i) == c) {
      i++;
      return true;
    }
    return false;
  }

  private boolean consume(char c) {
    skipWhitespace();
    return peek(c);
  }

  private void expect(char c) {
    skipWhitespace();
    if (!peek(c)) {
      throw error("expected '" + c + "'");
    }
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException("Invalid JSON at " + i + ": " + message);
  }
}
