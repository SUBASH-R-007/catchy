package com.acentra.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HealthAndAdvisorTest {

    private static CacheHealthEvaluator.Input in(long req, double hit, double mem, long ev, long exp, long puts, int streak,
                                                 CacheRiskLevel risk, long stale, double srcErr, Long silent) {
        return new CacheHealthEvaluator.Input(req, hit, mem, ev, exp, puts, 10, streak, risk, stale, srcErr, silent);
    }

    @Test
    void excellentWhenHitRateHighAndMemoryLow() {
        var r = CacheHealthEvaluator.evaluate(in(1000, 91.4, 51, 0, 0, 100, 0, CacheRiskLevel.LOW, 0, 0, null));
        assertThat(r.status()).isEqualTo(CacheHealthStatus.EXCELLENT);
        assertThat(r.score()).isBetween(85, 100);
    }

    @Test
    void goodWhenHitRateBetweenTargets() {
        var r = CacheHealthEvaluator.evaluate(in(1000, 70, 85, 0, 0, 100, 0, CacheRiskLevel.LOW, 0, 0, null));
        assertThat(r.status()).isEqualTo(CacheHealthStatus.GOOD);
        assertThat(r.score()).isBetween(65, 84);
    }

    @Test
    void warningReasonsAreExact() {
        var r = CacheHealthEvaluator.evaluate(in(1000, 48.2, 93, 436, 0, 500, 0, CacheRiskLevel.MEDIUM, 0, 0, null));
        assertThat(r.status()).isEqualTo(CacheHealthStatus.WARNING);
        assertThat(r.reasons()).contains("Hit rate is 48.2%, below 65% target.",
                "Memory utilization is 93%.", "436 evictions occurred in the last 10 minutes.");
        assertThat(r.score()).isBetween(35, 64);
    }

    @Test
    void criticalConditions() {
        assertThat(CacheHealthEvaluator.evaluate(in(500, 90, 98.5, 0, 0, 0, 0, CacheRiskLevel.LOW, 0, 0, null)).status())
                .isEqualTo(CacheHealthStatus.CRITICAL);
        assertThat(CacheHealthEvaluator.evaluate(in(500, 90, 10, 0, 0, 0, 3, CacheRiskLevel.LOW, 0, 0, null)).status())
                .isEqualTo(CacheHealthStatus.CRITICAL);
        assertThat(CacheHealthEvaluator.evaluate(in(500, 90, 10, 0, 0, 0, 0, CacheRiskLevel.HIGH, 1, 0, null)).status())
                .isEqualTo(CacheHealthStatus.CRITICAL);
        assertThat(CacheHealthEvaluator.evaluate(in(500, 90, 10, 0, 0, 0, 0, CacheRiskLevel.LOW, 0, 40, null)).status())
                .isEqualTo(CacheHealthStatus.CRITICAL);
        assertThat(CacheHealthEvaluator.evaluate(in(500, 90, 10, 0, 0, 0, 0, CacheRiskLevel.LOW, 0, 0, 300L)).status())
                .isEqualTo(CacheHealthStatus.CRITICAL);
        assertThat(CacheHealthEvaluator.evaluate(in(500, 90, 10, 0, 0, 0, 0, CacheRiskLevel.LOW, 1, 0, null)).status())
                .as("stale violation in a LOW region is only a warning").isEqualTo(CacheHealthStatus.WARNING);
    }

    @Test
    void unknownWithTooFewRequests() {
        var r = CacheHealthEvaluator.evaluate(in(5, 0, 1, 0, 0, 0, 0, CacheRiskLevel.LOW, 0, 0, null));
        assertThat(r.status()).isEqualTo(CacheHealthStatus.UNKNOWN);
        assertThat(r.reasons()).hasSize(1);
    }

    @Test
    void expiryChurnIsAWarning() {
        var r = CacheHealthEvaluator.evaluate(in(1000, 90, 10, 0, 90, 100, 0, CacheRiskLevel.LOW, 0, 0, null));
        assertThat(r.status()).isEqualTo(CacheHealthStatus.WARNING);
        assertThat(r.reasons().get(0)).contains("expiry churn");
    }

    // ---- advisor ---------------------------------------------------------------------------------------------------

    private static final PolicyAdvisor.Settings S = new PolicyAdvisor.Settings(100, 5.0, Duration.ofMinutes(10));
    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    @Test
    void recommendsLfuWhenSimulatedGainIsLargeAndSampleSufficient() {
        var r = PolicyAdvisor.evaluate(new PolicyAdvisor.Input(EvictionPolicy.LRU, 1000, 69.4, 80.9, 78.5, null, NOW), S);
        assertThat(r.action()).isEqualTo(CachePolicyRecommendation.Action.SWITCH);
        assertThat(r.recommendedPolicy()).isEqualTo(EvictionPolicy.LFU);
        assertThat(r.improvementPercent()).isEqualTo(11.5);
        assertThat(r.approvalRequired()).isTrue();
        assertThat(r.reason()).contains("small stable set");
        assertThat(r.confidence()).isBetween(80, 100);
        assertThat(r.summary()).isEqualTo("Switch to LFU");
    }

    @Test
    void keepsWhenImprovementBelowThreshold() {
        var r = PolicyAdvisor.evaluate(new PolicyAdvisor.Input(EvictionPolicy.LRU, 1000, 70.0, 73.0, 50, null, NOW), S);
        assertThat(r.action()).isEqualTo(CachePolicyRecommendation.Action.KEEP);
        assertThat(r.recommendedPolicy()).isEqualTo(EvictionPolicy.LRU);
        assertThat(r.summary()).isEqualTo("Keep LRU");
    }

    @Test
    void keepsWhenSampleTooSmall() {
        var r = PolicyAdvisor.evaluate(new PolicyAdvisor.Input(EvictionPolicy.LRU, 40, 10.0, 90.0, 90, null, NOW), S);
        assertThat(r.action()).isEqualTo(CachePolicyRecommendation.Action.KEEP);
        assertThat(r.minimumSampleMet()).isFalse();
        assertThat(r.reason()).contains("40 of 100");
    }

    @Test
    void cooldownBlocksSwitch() {
        var r = PolicyAdvisor.evaluate(new PolicyAdvisor.Input(EvictionPolicy.LRU, 1000, 50, 80, 70,
                NOW.minus(Duration.ofMinutes(2)), NOW), S);
        assertThat(r.action()).isEqualTo(CachePolicyRecommendation.Action.KEEP);
        assertThat(r.cooldownActive()).isTrue();
        assertThat(r.cooldownEndsAt()).isEqualTo(NOW.minus(Duration.ofMinutes(2)).plus(Duration.ofMinutes(10)));
        var after = PolicyAdvisor.evaluate(new PolicyAdvisor.Input(EvictionPolicy.LRU, 1000, 50, 80, 70,
                NOW.minus(Duration.ofMinutes(20)), NOW), S);
        assertThat(after.action()).isEqualTo(CachePolicyRecommendation.Action.SWITCH);
    }

    @Test
    void recommendsLruWhenLfuCurrentAndRecencyWins() {
        var r = PolicyAdvisor.evaluate(new PolicyAdvisor.Input(EvictionPolicy.LFU, 500, 85, 60, 30, null, NOW), S);
        assertThat(r.recommendedPolicy()).isEqualTo(EvictionPolicy.LRU);
        assertThat(r.improvementPercent()).isEqualTo(25.0);
    }
}
