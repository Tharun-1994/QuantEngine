package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.Map;

import com.backtest.engine.entity.TradePair;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Argument bundle for SizingPolicyResolver.resolve(). Future sizing modes
 * (atr_volatility, risk_parity, etc.) add fields here without changing the
 * interface signature.
 *
 * LRA Patch 18.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SizingContext {

    /** The pair being sized. Null if the mode is leg-agnostic (capital_div_slots). */
    private TradePair pair;

    /** "long" or "short" — which side of the pair is being sized. */
    private String legSide;

    /** The trading date — used for VIX-band lookup on date-conditional modes. */
    private LocalDate date;

    /** Raw sizing_policy JSON from the regime, deserialised by Jackson. */
    private Map<String, Object> policy;

    /**
     * Per-ticker static metadata from the regime's ticker_classification JSON.
     * Shape: {symbol -> {risk: "Risk On", range_tier: "wide", ...}}.
     * Null when classification is not consumed by the active mode.
     */
    private Map<String, Map<String, Object>> tickerClassification;

    /** VIX close on the trading date. Null if the active mode doesn't need it. */
    private Double vixClose;

    /** Total strategy capital. Used by capital_div_slots. */
    private Double capital;

    /** Number of slots from the regime. Used by capital_div_slots. */
    private Integer slots;
}