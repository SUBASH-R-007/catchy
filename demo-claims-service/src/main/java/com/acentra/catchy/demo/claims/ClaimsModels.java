package com.acentra.catchy.demo.claims;

import java.util.List;

/** Synthetic, non-patient-specific data shapes of the claims demo. */
public final class ClaimsModels {
    private ClaimsModels() {}

    public enum ServedFrom { CACHE, SOURCE }

    /** One synthetic adjudication rule. Contains no patient data. */
    public record Rule(String ruleId, String category, String condition, String action, String severity) {}

    /** The cached value of the {@code claim-rules} region (about 1-2 KB as JSON). */
    public record RuleSet(String ruleSetId, String version, List<Rule> rules) {}

    public record RuleSetResponse(String ruleSetId, String version, List<Rule> rules, ServedFrom servedFrom) {
        static RuleSetResponse of(RuleSet r, ServedFrom servedFrom) {
            return new RuleSetResponse(r.ruleSetId(), r.version(), r.rules(), servedFrom);
        }
    }

    /** The cached value of the {@code provider-directory} region: a synthetic provider-group summary. */
    public record ProviderGroupSummary(String providerGroupId, String displayName, String specialty, String networkStatus,
                                       int contractedProviders, List<String> serviceRegions, String directoryVersion) {}

    public record ProviderGroupResponse(String providerGroupId, String displayName, String specialty, String networkStatus,
                                        int contractedProviders, List<String> serviceRegions, String directoryVersion,
                                        ServedFrom servedFrom) {
        static ProviderGroupResponse of(ProviderGroupSummary p, ServedFrom servedFrom) {
            return new ProviderGroupResponse(p.providerGroupId(), p.displayName(), p.specialty(), p.networkStatus(),
                    p.contractedProviders(), p.serviceRegions(), p.directoryVersion(), servedFrom);
        }
    }
}
