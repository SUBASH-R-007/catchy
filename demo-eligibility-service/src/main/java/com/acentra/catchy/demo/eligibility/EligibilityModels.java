package com.acentra.catchy.demo.eligibility;

/** Synthetic, minimal derived results. The cache never holds a full member or authorization record. */
public final class EligibilityModels {
    private EligibilityModels() {}

    public enum ServedFrom { CACHE, SOURCE }

    /** The ONLY thing cached for a member: a minimal derived result (no name, DOB, address, claims, diagnoses...). */
    public record EligibilityResult(boolean active, String planTier, String asOf) {}

    /** The ONLY thing cached for an authorization request: a status code and its effective date. */
    public record AuthorizationDecision(String status, String asOf) {}

    public record EligibilityResponse(String memberRef, boolean active, String planTier, String asOf, ServedFrom servedFrom,
                                      String note) {}

    /** Result of {@code POST /api/eligibility/{id}/validate}. {@code cachedBefore} is null when nothing was cached. */
    public record ValidationResponse(String memberRef, EligibilityResult cachedBefore, EligibilityResult sourceValue,
                                     boolean driftDetected, boolean cacheCorrected, String note) {}

    public record PreliminaryResponse(String requestRef, String status, boolean preliminary, boolean finalDecisionAllowed,
                                      ServedFrom servedFrom, String note) {}

    public record FinalizeResponse(String requestRef, String status, boolean preliminary, boolean validatedAgainstSource,
                                   boolean finalDecisionAllowed, boolean driftDetected, boolean cacheCorrected, String note) {}

    public record SourceChangeResponse(String ref, String sourceStatus, String note) {}
}
