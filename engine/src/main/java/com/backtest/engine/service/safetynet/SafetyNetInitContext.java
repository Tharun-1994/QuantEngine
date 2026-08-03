package com.backtest.engine.service.safetynet;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.StrategyBucketRequestDto;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.util.ArrowDataFrame;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Builder;
import lombok.Getter;

/**
 * Read-only context passed to {@link SafetyNetPolicy#initialize}.
 *
 * <p>Provides everything a policy needs to precompute its state without
 * depending directly on BacktestContext or any other orchestrator. This
 * keeps policies testable in isolation.</p>
 */
@Getter
@Builder
public class SafetyNetInitContext {

    /** The full strategy request (for universe, dates, etc.). */
    private final StrategyBucketRequestDto strategyRequest;

    /** Resolved universe name (e.g. "sp500"). */
    private final String universe;

    /** Loaded market data for the backtest period. */
    private final PriceDataV2 priceData;

    /** All trading dates in the backtest period. */
    private final List<LocalDate> allDates;

    /** Jackson mapper for converting raw param maps into typed DTOs. */
    private final ObjectMapper objectMapper;

    /**
     * Loader for volatility-cut-style market frames (SPY closes, VIX values,
     * etc). Policies pass their rule tree in; the loader returns a map of
     * frame-key → frame. Provided by BacktestContext as a lambda over its
     * private loadVolatilityCutFrames helper.
     */
    private final Function<RuleGroupNodeDto, Map<String, ArrowDataFrame>> frameLoader;
}