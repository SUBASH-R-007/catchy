package com.acentra.catchy.telemetry.job;

import com.acentra.catchy.telemetry.config.CatchyProperties;
import com.acentra.catchy.telemetry.service.RecommendationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled work: recommendation refresh (the only place besides the evaluate endpoint that may call Ollama) and retention. */
@Component
public class BackgroundJobs {
    private static final Logger log = LoggerFactory.getLogger(BackgroundJobs.class);

    private final CatchyProperties props;
    private final RecommendationService recommendations;
    private final RetentionService retention;

    public BackgroundJobs(CatchyProperties props, RecommendationService recommendations, RetentionService retention) {
        this.props = props;
        this.recommendations = recommendations;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${catchy.advisor.interval:PT15S}", initialDelayString = "${catchy.advisor.interval:PT15S}")
    public void refreshRecommendations() {
        if (!props.jobs().enabled()) return;
        try {
            recommendations.refreshAll(true);
        } catch (RuntimeException e) {
            log.warn("Recommendation refresh failed: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${catchy.retention.interval:PT1M}", initialDelayString = "${catchy.retention.interval:PT1M}")
    public void prune() {
        if (!props.jobs().enabled()) return;
        try {
            retention.prune();
        } catch (RuntimeException e) {
            log.warn("Retention job failed: {}", e.getClass().getSimpleName());
        }
    }
}
