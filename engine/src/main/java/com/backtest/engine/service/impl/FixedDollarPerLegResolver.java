package com.backtest.engine.service.impl;

import java.util.List;
import java.util.Map;

import com.backtest.engine.entity.TradePair;
import com.backtest.engine.service.SizingContext;
import com.backtest.engine.service.SizingPolicyResolver;

/**
 * Sizing mode where each leg gets a fixed dollar amount drawn from
 * VIX-conditional bands, with overrides keyed by the pair's attribute combo.
 *
 * Policy shape (the sizing_policy JSON on the regime):
 * <pre>
 * {
 *   "mode": "fixed_dollar_per_leg",
 *   "params": {
 *     "conditional_on": "vix_close",
 *     "bands": [
 *       { "vix_max": 22.5, "values": { "cap_a": 93750,  "cap_b": 112500 } },
 *       { "vix_max": null, "values": { "cap_a": 127500, "cap_b": 153750 } }
 *     ],
 *     "leg_capital_assignment": {
 *       "strategy":  "by_pair_attribute_combo",
 *       "attribute": "risk",
 *       "default":   { "long_cap": "cap_a", "short_cap": "cap_a" },
 *       "overrides": [
 *         { "long_value": "Risk On", "short_value": "Risk Off",
 *           "long_cap":   "cap_b",   "short_cap":   "cap_a" }
 *       ]
 *     }
 *   }
 * }
 * </pre>
 *
 * Used by LRA pairs. Sits dormant until Patch 22.
 *
 * LRA Patch 18.
 */
public class FixedDollarPerLegResolver implements SizingPolicyResolver {

    public static final String MODE = "fixed_dollar_per_leg";

    @Override
    public String getMode() {
        return MODE;
    }

    @Override
    public Double resolve(SizingContext ctx) {
        Map<String, Object> policy = ctx.getPolicy();
        if (policy == null || !MODE.equals(policy.get("mode"))) {
            throw new IllegalArgumentException(
                "fixed_dollar_per_leg requires policy.mode == '" + MODE + "'");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) policy.get("params");
        if (params == null) {
            throw new IllegalArgumentException("missing sizing_policy.params");
        }

        Map<String, Object> bandValues = pickBand(params, ctx.getVixClose());
        String capKey = pickCapKey(params, ctx);

        Object capValue = bandValues.get(capKey);
        if (capValue == null) {
            throw new IllegalArgumentException(
                "VIX band has no value for cap key '" + capKey + "'");
        }
        return ((Number) capValue).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pickBand(Map<String, Object> params, Double vix) {
        List<Map<String, Object>> bands = (List<Map<String, Object>>) params.get("bands");
        if (bands == null || bands.isEmpty()) {
            throw new IllegalArgumentException("sizing_policy.params.bands is missing or empty");
        }
        if (vix == null) {
            throw new IllegalArgumentException(
                "VIX close required for fixed_dollar_per_leg band lookup but not provided");
        }
        for (Map<String, Object> band : bands) {
            Object vmax = band.get("vix_max");
            if (vmax == null) {
                // unbounded band — applies if no earlier band matched
                return (Map<String, Object>) band.get("values");
            }
            if (vix <= ((Number) vmax).doubleValue()) {
                return (Map<String, Object>) band.get("values");
            }
        }
        throw new IllegalArgumentException(
            "no band matched VIX " + vix + " — add an unbounded band (vix_max: null)");
    }

    @SuppressWarnings("unchecked")
    private String pickCapKey(Map<String, Object> params, SizingContext ctx) {
        Map<String, Object> assignment =
            (Map<String, Object>) params.get("leg_capital_assignment");
        if (assignment == null) {
            throw new IllegalArgumentException("missing leg_capital_assignment");
        }
        String strategy = (String) assignment.get("strategy");
        if (!"by_pair_attribute_combo".equals(strategy)) {
            throw new IllegalArgumentException(
                "unsupported leg_capital_assignment.strategy: " + strategy);
        }

        String legSide = ctx.getLegSide();
        Map<String, Object> defaults = (Map<String, Object>) assignment.get("default");
        String defaultCap = (String) defaults.get(legSide + "_cap");

        TradePair pair = ctx.getPair();
        Map<String, Map<String, Object>> classification = ctx.getTickerClassification();
        if (pair != null && classification != null) {
            String attribute = (String) assignment.get("attribute");
            String longAttrVal  = readAttribute(classification, pair.getLongLeg(),  attribute);
            String shortAttrVal = readAttribute(classification, pair.getShortLeg(), attribute);

            List<Map<String, Object>> overrides =
                (List<Map<String, Object>>) assignment.get("overrides");
            if (overrides != null) {
                for (Map<String, Object> override : overrides) {
                    String longVal  = (String) override.get("long_value");
                    String shortVal = (String) override.get("short_value");
                    if (longVal != null  && longVal.equals(longAttrVal)
                     && shortVal != null && shortVal.equals(shortAttrVal)) {
                        return (String) override.get(legSide + "_cap");
                    }
                }
            }
        }
        return defaultCap;
    }

    private String readAttribute(Map<String, Map<String, Object>> classification,
                                 String symbol, String attribute) {
        Map<String, Object> meta = classification.get(symbol);
        return meta == null ? null : (String) meta.get(attribute);
    }
}