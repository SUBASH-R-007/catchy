package io.cachelab.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts JMH's JSON results into the dashboard's {@code bench-results.json} (SPEC 5):
 *
 * <pre>{@code
 * { "sample": false, "machine": { "cpu": ..., "cores": 8, "os": ..., "jvm": ... },
 *   "results": [ { "impl": "segmented", "workload": "read90", "threads": 16,
 *                  "opsPerSec": 0, "errorPct": 0 } ] }
 * }</pre>
 *
 * <p>Usage: {@code BenchExport <jmh-results.json> <bench-results.json>} (the {@code exportBench}
 * Gradle task passes both paths).
 */
public final class BenchExport {

  private BenchExport() {}

  /**
   * Converts one results file.
   *
   * @param args the JMH JSON input path and the output path
   * @throws IOException if a file cannot be read or written
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException("usage: BenchExport <jmh-results.json> <output.json>");
    }
    String jmh = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
    String out = convert(jmh, machine());
    Path target = Path.of(args[1]);
    Files.createDirectories(target.getParent());
    Files.writeString(target, out, StandardCharsets.UTF_8);
    System.out.println("Wrote " + target);
  }

  /** One result row. */
  record Row(String impl, String workload, int threads, double opsPerSec, double errorPct) {}

  static String convert(String jmhJson, Map<String, Object> machine) {
    List<Row> rows = new ArrayList<>();
    for (Object o : (List<?>) Json.parse(jmhJson)) {
      Map<?, ?> run = (Map<?, ?>) o;
      Map<?, ?> params = (Map<?, ?>) run.get("params");
      Map<?, ?> metric = (Map<?, ?>) run.get("primaryMetric");
      double score = ((Number) metric.get("score")).doubleValue();
      Object err = metric.get("scoreError");
      double error =
          err instanceof Number n && !Double.isNaN(n.doubleValue()) ? n.doubleValue() : 0;
      rows.add(
          new Row(
              (String) params.get("impl"),
              (String) params.get("workload"),
              ((Number) run.get("threads")).intValue(),
              score,
              score == 0 ? 0 : Math.round(error / score * 1000) / 10.0));
    }
    return render(rows, machine);
  }

  static Map<String, Object> machine() {
    String cpu = System.getenv("PROCESSOR_IDENTIFIER");
    if (cpu == null) {
      cpu = cpuFromProc();
    }
    return Map.of(
        "cpu",
        cpu == null ? System.getProperty("os.arch") : cpu.trim(),
        "cores",
        Runtime.getRuntime().availableProcessors(),
        "os",
        System.getProperty("os.name") + " " + System.getProperty("os.version"),
        "jvm",
        System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
  }

  private static String cpuFromProc() {
    try {
      return Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
          .filter(l -> l.startsWith("model name"))
          .map(l -> l.substring(l.indexOf(':') + 1))
          .findFirst()
          .orElse(null);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static String render(List<Row> rows, Map<String, Object> machine) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n  \"sample\": false,\n  \"machine\": {");
    sb.append("\"cpu\": ").append(Json.quote(String.valueOf(machine.get("cpu"))));
    sb.append(", \"cores\": ").append(machine.get("cores"));
    sb.append(", \"os\": ").append(Json.quote(String.valueOf(machine.get("os"))));
    sb.append(", \"jvm\": ").append(Json.quote(String.valueOf(machine.get("jvm"))));
    sb.append("},\n  \"settings\": \"JMH 1 fork, 3 x 1 s warm-up, 5 x 1 s measurement\",");
    sb.append("\n  \"results\": [\n");
    for (int i = 0; i < rows.size(); i++) {
      Row r = rows.get(i);
      sb.append(
          String.format(
              Locale.ROOT,
              "    {\"impl\": %s, \"workload\": %s, \"threads\": %d, \"opsPerSec\": %.0f,"
                  + " \"errorPct\": %.1f}%s%n",
              Json.quote(r.impl()),
              Json.quote(r.workload()),
              r.threads(),
              r.opsPerSec(),
              r.errorPct(),
              i + 1 < rows.size() ? "," : ""));
    }
    return sb.append("  ]\n}\n").toString();
  }
}
