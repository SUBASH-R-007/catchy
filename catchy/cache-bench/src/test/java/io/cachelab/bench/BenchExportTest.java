package io.cachelab.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BenchExportTest {

  private static final String JMH =
      """
      [
        {
          "jmhVersion" : "1.37",
          "benchmark" : "io.cachelab.bench.CacheBenchmark.threads16",
          "mode" : "thrpt",
          "threads" : 16,
          "forks" : 1,
          "params" : { "impl" : "segmented", "workload" : "read90" },
          "primaryMetric" : {
            "score" : 1.2345E7,
            "scoreError" : 246900.0,
            "scoreUnit" : "ops/s",
            "rawData" : [ [ 1.2E7, 1.3E7 ] ]
          },
          "secondaryMetrics" : { }
        },
        {
          "benchmark" : "io.cachelab.bench.CacheBenchmark.threads01",
          "threads" : 1,
          "params" : { "impl" : "caffeine", "workload" : "write90" },
          "primaryMetric" : { "score" : 5000000.5, "scoreError" : "NaN" }
        }
      ]
      """;

  @Test
  void convertsJmhResultsToTheDashboardFormat() {
    String out =
        BenchExport.convert(
            JMH, Map.of("cpu", "Test \"CPU\"", "cores", 8, "os", "OS", "jvm", "JVM"));
    Map<?, ?> json = (Map<?, ?>) Json.parse(out);
    assertThat(json.get("sample")).isEqualTo(false);
    assertThat(((Map<?, ?>) json.get("machine")).get("cpu")).isEqualTo("Test \"CPU\"");
    List<?> results = (List<?>) json.get("results");
    assertThat(results).hasSize(2);
    Map<?, ?> first = (Map<?, ?>) results.get(0);
    assertThat(first.get("impl")).isEqualTo("segmented");
    assertThat(first.get("workload")).isEqualTo("read90");
    assertThat(first.get("threads")).isEqualTo(16.0);
    assertThat(first.get("opsPerSec")).isEqualTo(12345000.0);
    assertThat(first.get("errorPct")).isEqualTo(2.0);
    assertThat(((Map<?, ?>) results.get(1)).get("errorPct")).isEqualTo(0.0); // NaN error -> 0
  }

  @Test
  void theJsonReaderRejectsGarbage() {
    assertThatThrownBy(() -> Json.parse("{\"a\": }")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Json.parse("[1, 2] x")).hasMessageContaining("trailing");
    Map<?, ?> parsed =
        (Map<?, ?>) Json.parse("{\"s\": \"a\\\\b\\u0041\", \"n\": null, \"t\": true}");
    assertThat(parsed.get("s")).isEqualTo("a\\bA");
    assertThat(parsed.get("t")).isEqualTo(true);
    assertThat(parsed.containsKey("n")).isTrue();
    assertThat(parsed.get("n")).isNull();
  }
}
