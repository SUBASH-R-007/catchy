package com.acentra.catchy.demo.eligibility;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Environment-driven settings (see application.yml). The API key and salt are never logged. */
@ConfigurationProperties(prefix = "catchy")
public class DemoProperties {

    private String environment = "staging";
    private String keySalt = "";
    private String dashboardUrl = "http://localhost:5173";
    private final Telemetry telemetry = new Telemetry();
    private final Source source = new Source();

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getKeySalt() { return keySalt; }
    public void setKeySalt(String keySalt) { this.keySalt = keySalt; }
    public String getDashboardUrl() { return dashboardUrl; }
    public void setDashboardUrl(String dashboardUrl) { this.dashboardUrl = dashboardUrl; }
    public Telemetry getTelemetry() { return telemetry; }
    public Source getSource() { return source; }

    public static class Telemetry {
        private String url = "http://localhost:8090";
        private String apiKey = "";

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public boolean isEnabled() {
            return apiKey != null && !apiKey.isBlank() && url != null && !url.isBlank();
        }
    }

    /** Latency of the simulated eligibility / authorization back end behind the caches. */
    public static class Source {
        private int latencyMinMs = 5;
        private int latencyMaxMs = 15;

        public int getLatencyMinMs() { return latencyMinMs; }
        public void setLatencyMinMs(int latencyMinMs) { this.latencyMinMs = latencyMinMs; }
        public int getLatencyMaxMs() { return latencyMaxMs; }
        public void setLatencyMaxMs(int latencyMaxMs) { this.latencyMaxMs = latencyMaxMs; }
    }
}
