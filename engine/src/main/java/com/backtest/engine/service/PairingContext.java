package com.backtest.engine.service;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Argument bundle for PairingService.constructPairs(). One instance per
 * trading day per regime.
 *
 * LRA Patch 19.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PairingContext {

    /**
     * Filtered + ranked + sliced long-side candidates for the day, in pairing
     * order (index 0 is the strongest long candidate). For LRA: bottom_ibs.iloc[-2:].
     */
    private List<String> longCandidates;

    /**
     * Filtered + ranked + sliced short-side candidates for the day, in pairing
     * order. For LRA: top_ibs.iloc[:2].
     */
    private List<String> shortCandidates;

    /**
     * Pre-slice long-side pool — every ticker that passed the per-leg entry
     * rules on the long side before iloc reduction. Backtracking draws from
     * here when a candidate pair is disallowed.
     */
    private List<String> preReductionLongPool;

    /** Pre-slice short-side pool — same as above for the short side. */
    private List<String> preReductionShortPool;

    /**
     * Deserialised pairing_entry_rules JSON from the regime. Shape:
     * <pre>
     * {
     *   "disallowed_combos": [
     *     { "long_attribute": "risk", "long_value": "Risk Off",
     *       "short_attribute": "risk", "short_value": "Risk On" }
     *   ],
     *   "backtracking": {
     *     "swap_target": "short_leg",   // or "long_leg"
     *     "pool": "pre_reduction_top",  // or "pre_reduction_bottom"
     *     "exclude_attribute": "risk",
     *     "exclude_value": "Risk On",
     *     "selection": "last"           // or "first"
     *   }
     * }
     * </pre>
     * Null means "no constraints" — every (long_i, short_i) positional pair stands.
     */
    private Map<String, Object> pairingRules;

    /**
     * Per-ticker static metadata from the regime's ticker_classification JSON.
     * Shape: {symbol -> {risk: "Risk On", range_tier: "wide", ...}}.
     */
    private Map<String, Map<String, Object>> tickerClassification;

    /**
     * Maximum number of pairs to construct (typically 2 for LRA — 4 total positions).
     * The output is min(longCands.size(), shortCands.size(), maxPairs).
     */
    private int maxPairs;
    
    /**
     * Starting pairId for this call. The dispatch arm tracks a running counter
     * across the entire backtest so pairIds are globally unique. Without this,
     * pairId resets to 0 every day and expandToPartners falsely groups trades
     * from different days that happen to share a pairId.
     *
     * LRA Patch 32.
     */
    private int pairIdStart;
}