package com.backtest.engine.service.safetynet;

/**
 * Factory for {@link SafetyNetPolicy} instances by type string.
 *
 * <p>Each call to {@link #create} returns a fresh instance — policies own
 * per-backtest state and must not be shared across runs.</p>
 *
 * <p>Adding a new policy: implement {@link SafetyNetPolicy}, then add a
 * case below. That's the only file that needs to know about the new type.</p>
 */
public final class SafetyNetRegistry {

    private SafetyNetRegistry() {}

    /**
     * Create a fresh policy instance for the given type.
     *
     * @param type one of: "simple", "spy_volatility", "none"
     * @return new policy instance, or null if type is "none" / unknown
     */
    public static SafetyNetPolicy create(String type) {
        if (type == null || type.isBlank()) return null;
        switch (type.trim().toLowerCase()) {
            case "none":
                return null;
            case "simple":
                return new SimpleFreezeResumePolicy();
            case "spy_volatility":
                return new SpyVolatilityPolicy();
            case "spy_volatility_pause":
                return new SpyVolatilityPausePolicy();
            default:
                System.err.println(
                    "[WARN] Unknown safety net type: '" + type + "'. Ignoring.");
                return null;
        }
    }
}