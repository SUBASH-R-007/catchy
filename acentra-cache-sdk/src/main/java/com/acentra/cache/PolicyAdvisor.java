package com.acentra.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Deterministic, rule-based policy recommendation (the source of truth; any LLM text is advisory only).
 * Requires a minimum sample, a minimum improvement and no active cooldown before recommending a switch.
 */
public final class PolicyAdvisor {
    private PolicyAdvisor() {}

    public record Settings(long minimumRequests, double minimumImprovementPercent, Duration cooldown) {
        public static Settings defaults() {
            return new Settings(100, 5.0, Duration.ofMinutes(5));
        }
    }

    public record Input(
            EvictionPolicy currentPolicy,
            long sampleSize,
            double lruHitRate,
            double lfuHitRate,
            double topKeyConcentrationPercent,
            Instant lastPolicyChangeAt,
            Instant now) {}

    public static CachePolicyRecommendation evaluate(Input in, Settings s) {
        EvictionPolicy current = in.currentPolicy();
        EvictionPolicy alt = current == EvictionPolicy.LRU ? EvictionPolicy.LFU : EvictionPolicy.LRU;
        double curRate = current == EvictionPolicy.LRU ? in.lruHitRate() : in.lfuHitRate();
        double altRate = current == EvictionPolicy.LRU ? in.lfuHitRate() : in.lruHitRate();
        double improvement = Humanize.round2(altRate - curRate);

        boolean sampleMet = in.sampleSize() >= s.minimumRequests();
        Instant cooldownEnds = null;
        boolean cooldown = false;
        if (in.lastPolicyChangeAt() != null && in.now() != null) {
            cooldownEnds = in.lastPolicyChangeAt().plus(s.cooldown());
            cooldown = in.now().isBefore(cooldownEnds);
            if (!cooldown) cooldownEnds = null;
        }
        boolean improvementMet = improvement >= s.minimumImprovementPercent();

        double sampleFactor = Math.min(1.0, in.sampleSize() / (double) (s.minimumRequests() * 10));
        CachePolicyRecommendation.Action action;
        EvictionPolicy recommended;
        int confidence;
        String reason;

        if (!sampleMet) {
            action = CachePolicyRecommendation.Action.KEEP;
            recommended = current;
            confidence = (int) Math.round(25.0 * in.sampleSize() / Math.max(1, s.minimumRequests()));
            reason = "Not enough data yet: " + in.sampleSize() + " of " + s.minimumRequests()
                    + " required requests observed.";
        } else if (improvementMet && cooldown) {
            action = CachePolicyRecommendation.Action.KEEP;
            recommended = current;
            confidence = switchConfidence(sampleFactor, improvement);
            reason = "Simulated " + alt + " gain is " + pts(improvement) + " but the policy changed recently; cooldown active until "
                    + cooldownEnds + ".";
        } else if (improvementMet) {
            action = CachePolicyRecommendation.Action.SWITCH;
            recommended = alt;
            confidence = switchConfidence(sampleFactor, improvement);
            reason = alt == EvictionPolicy.LFU
                    ? (in.topKeyConcentrationPercent() >= 60
                            ? "A small stable set of keys dominates repeated requests (top 20% of keys receive "
                                    + Humanize.percent(in.topKeyConcentrationPercent()) + " of requests); LFU retains them."
                            : "Stable repeated access favors frequency-based retention.")
                    : "Recent-access locality favors recency-based retention; the working set shifts over time, so LFU "
                            + "keeps keys that were popular earlier.";
        } else {
            action = CachePolicyRecommendation.Action.KEEP;
            recommended = current;
            double keepMargin = Math.max(0, s.minimumImprovementPercent() - improvement) / (s.minimumImprovementPercent() + 10);
            confidence = (int) Math.round(50 + 25 * sampleFactor + 25 * Math.min(1.0, keepMargin));
            reason = improvement < 0
                    ? "Current " + current + " outperforms " + alt + " by " + pts(-improvement) + " in simulation; no switch needed."
                    : "Simulated " + alt + " gain of " + pts(improvement) + " is below the " + pts(s.minimumImprovementPercent())
                            + " threshold; no switch needed.";
        }
        return new CachePolicyRecommendation(
                current, recommended, action, in.lruHitRate(), in.lfuHitRate(), improvement,
                Math.max(0, Math.min(100, confidence)), reason, in.sampleSize(), sampleMet, cooldown, cooldownEnds, true);
    }

    private static int switchConfidence(double sampleFactor, double improvement) {
        double margin = Math.min(1.0, Math.abs(improvement) / 20.0);
        return (int) Math.round(50 + 25 * sampleFactor + 25 * margin);
    }

    private static String pts(double v) {
        return String.format(Locale.ROOT, "%.1f pts", v);
    }
}
