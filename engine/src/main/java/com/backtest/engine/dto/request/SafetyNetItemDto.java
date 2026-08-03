package com.backtest.engine.dto.request;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One stateful safety-net policy attached to a regime.
 *
 * <p>The {@code type} field selects which policy class on the engine side
 * will consume this item. {@code params} is a free-form blob whose shape
 * each policy class validates on its own — the wrapper DTO does no schema
 * enforcement on it.</p>
 *
 * <p>Examples:</p>
 * <pre>
 * Simple freeze/resume:
 *   { "type": "simple",
 *     "params": {
 *       "freeze_rules_tree": {...}, "resume_rules_tree": {...},
 *       "freeze_timing": "open",    "resume_timing": "open"
 *     } }
 *
 * SPY volatility (Stage 3c):
 *   { "type": "spy_volatility",
 *     "params": {
 *       "vol_ticker": "SPY", "vol_lookback": 5, "vol_threshold": 0.025,
 *       "timeout_days": 20, "selloff_pct": 0.20,
 *       "peak_drop_pct": 0.80, "rearm_pct": 0.80
 *     } }
 * </pre>
 *
 * <p>This DTO is pure transport. Stage 3a plumbs it through; Stage 3b
 * introduces a policy registry that knows how to evaluate each type.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyNetItemDto {

    /** Policy discriminator: "none" | "simple" | "spy_volatility" | future. */
    @JsonProperty("type")
    private String type;

    /** Policy-specific config. Empty map when no params apply. */
    @JsonProperty("params")
    private Map<String, Object> params;
}