package io.cachelab.server.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A deliberately small JSON Schema (2020-12) validator for tests. It supports exactly the keywords
 * that {@code docs/api/metrics.schema.json} uses: {@code type}, {@code properties}, {@code
 * required}, {@code additionalProperties: false}, {@code items}, {@code maxItems}, {@code enum},
 * {@code minimum}, {@code maximum}, {@code minLength}, local {@code $ref} and {@code oneOf}. Any
 * other keyword fails loudly, so the schema cannot silently outgrow the validator.
 *
 * <p>{@link #validate} returns the first violation, prefixed with its JSON path (for example {@code
 * $.caches[0].hitRate}).
 */
final class MiniJsonSchemaValidator {

  private static final Set<String> ANNOTATIONS =
      Set.of("$schema", "$id", "$defs", "title", "description");
  private static final Set<String> KEYWORDS =
      Set.of(
          "type",
          "properties",
          "required",
          "additionalProperties",
          "items",
          "maxItems",
          "enum",
          "minimum",
          "maximum",
          "minLength",
          "$ref",
          "oneOf");

  private final JsonNode root;

  MiniJsonSchemaValidator(JsonNode root) {
    this.root = root;
  }

  /** Loads the metrics schema from the repository, whether tests run from the module or root. */
  static MiniJsonSchemaValidator forMetricsSchema() throws IOException {
    List<Path> candidates =
        List.of(
            Path.of("..", "docs", "api", "metrics.schema.json"),
            Path.of("docs", "api", "metrics.schema.json"));
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        return new MiniJsonSchemaValidator(new ObjectMapper().readTree(candidate.toFile()));
      }
    }
    throw new IllegalStateException(
        "metrics.schema.json not found from " + Path.of("").toAbsolutePath());
  }

  /**
   * Validates {@code instance} against the root schema.
   *
   * @return the first violation as {@code "<json path>: <reason>"}, or empty when valid
   * @throws IllegalStateException if the schema uses an unsupported keyword or a bad {@code $ref}
   */
  Optional<String> validate(JsonNode instance) {
    return check(root, instance, "$");
  }

  private Optional<String> check(JsonNode schema, JsonNode node, String path) {
    requireSupported(schema);
    return first(
        () -> checkRef(schema, node, path),
        () -> checkType(schema, node, path),
        () -> checkEnum(schema, node, path),
        () -> checkBounds(schema, node, path),
        () -> checkMinLength(schema, node, path),
        () -> checkObject(schema, node, path),
        () -> checkArray(schema, node, path),
        () -> checkOneOf(schema, node, path));
  }

  @SafeVarargs
  private static Optional<String> first(Supplier<Optional<String>>... checks) {
    for (Supplier<Optional<String>> check : checks) {
      Optional<String> violation = check.get();
      if (violation.isPresent()) {
        return violation;
      }
    }
    return Optional.empty();
  }

  private static void requireSupported(JsonNode schema) {
    for (Map.Entry<String, JsonNode> entry : schema.properties()) {
      String keyword = entry.getKey();
      if (!KEYWORDS.contains(keyword) && !ANNOTATIONS.contains(keyword)) {
        throw new IllegalStateException("unsupported schema keyword: " + keyword);
      }
    }
    JsonNode additional = schema.get("additionalProperties");
    if (additional != null && !(additional.isBoolean() && !additional.booleanValue())) {
      throw new IllegalStateException("only additionalProperties: false is supported");
    }
  }

  private Optional<String> checkRef(JsonNode schema, JsonNode node, String path) {
    JsonNode ref = schema.get("$ref");
    if (ref == null) {
      return Optional.empty();
    }
    JsonNode target = ref.asText().startsWith("#/") ? root.at(ref.asText().substring(1)) : null;
    if (target == null || target.isMissingNode()) {
      throw new IllegalStateException("unresolvable $ref: " + ref.asText());
    }
    return check(target, node, path);
  }

  private static Optional<String> checkType(JsonNode schema, JsonNode node, String path) {
    JsonNode type = schema.get("type");
    if (type == null) {
      return Optional.empty();
    }
    List<String> allowed = new ArrayList<>();
    if (type.isArray()) {
      type.forEach(t -> allowed.add(t.asText()));
    } else {
      allowed.add(type.asText());
    }
    for (String candidate : allowed) {
      if (hasType(candidate, node)) {
        return Optional.empty();
      }
    }
    return violation(path, "expected type " + allowed + " but was " + node.getNodeType());
  }

  private static boolean hasType(String type, JsonNode node) {
    return switch (type) {
      case "null" -> node.isNull();
      case "boolean" -> node.isBoolean();
      case "string" -> node.isTextual();
      case "object" -> node.isObject();
      case "array" -> node.isArray();
      case "number" -> node.isNumber();
      case "integer" ->
          node.isIntegralNumber()
              || (node.isNumber() && node.decimalValue().stripTrailingZeros().scale() <= 0);
      default -> throw new IllegalStateException("unsupported type: " + type);
    };
  }

  private static Optional<String> checkEnum(JsonNode schema, JsonNode node, String path) {
    JsonNode values = schema.get("enum");
    if (values == null) {
      return Optional.empty();
    }
    for (JsonNode value : values) {
      if (value.equals(node)) {
        return Optional.empty();
      }
    }
    return violation(path, node + " is not one of " + values);
  }

  private static Optional<String> checkBounds(JsonNode schema, JsonNode node, String path) {
    if (!node.isNumber()) {
      return Optional.empty();
    }
    JsonNode minimum = schema.get("minimum");
    if (minimum != null && node.decimalValue().compareTo(minimum.decimalValue()) < 0) {
      return violation(path, node + " is below minimum " + minimum);
    }
    JsonNode maximum = schema.get("maximum");
    if (maximum != null && node.decimalValue().compareTo(maximum.decimalValue()) > 0) {
      return violation(path, node + " is above maximum " + maximum);
    }
    return Optional.empty();
  }

  private static Optional<String> checkMinLength(JsonNode schema, JsonNode node, String path) {
    JsonNode minLength = schema.get("minLength");
    if (minLength == null || !node.isTextual()) {
      return Optional.empty();
    }
    String text = node.textValue();
    if (text.codePointCount(0, text.length()) < minLength.intValue()) {
      return violation(path, "string shorter than minLength " + minLength);
    }
    return Optional.empty();
  }

  private Optional<String> checkObject(JsonNode schema, JsonNode node, String path) {
    if (!node.isObject()) {
      return Optional.empty();
    }
    for (JsonNode name : schema.path("required")) {
      if (!node.has(name.asText())) {
        return violation(path, "missing required property '" + name.asText() + "'");
      }
    }
    JsonNode properties = schema.path("properties");
    if (schema.has("additionalProperties")) {
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        if (!properties.has(field.getKey())) {
          return violation(path + "." + field.getKey(), "property not allowed by the schema");
        }
      }
    }
    for (Map.Entry<String, JsonNode> property : properties.properties()) {
      JsonNode value = node.get(property.getKey());
      if (value != null) {
        Optional<String> violation =
            check(property.getValue(), value, path + "." + property.getKey());
        if (violation.isPresent()) {
          return violation;
        }
      }
    }
    return Optional.empty();
  }

  private Optional<String> checkArray(JsonNode schema, JsonNode node, String path) {
    if (!node.isArray()) {
      return Optional.empty();
    }
    JsonNode maxItems = schema.get("maxItems");
    if (maxItems != null && node.size() > maxItems.intValue()) {
      return violation(path, node.size() + " items exceed maxItems " + maxItems);
    }
    JsonNode items = schema.get("items");
    for (int i = 0; items != null && i < node.size(); i++) {
      Optional<String> violation = check(items, node.get(i), path + "[" + i + "]");
      if (violation.isPresent()) {
        return violation;
      }
    }
    return Optional.empty();
  }

  private Optional<String> checkOneOf(JsonNode schema, JsonNode node, String path) {
    JsonNode branches = schema.get("oneOf");
    if (branches == null) {
      return Optional.empty();
    }
    List<String> failures = new ArrayList<>();
    for (JsonNode branch : branches) {
      check(branch, node, path).ifPresent(failures::add);
    }
    int matches = branches.size() - failures.size();
    if (matches == 1) {
      return Optional.empty();
    }
    return violation(
        path, "expected exactly one oneOf branch to match, " + matches + " did " + failures);
  }

  private static Optional<String> violation(String path, String reason) {
    return Optional.of(path + ": " + reason);
  }
}
