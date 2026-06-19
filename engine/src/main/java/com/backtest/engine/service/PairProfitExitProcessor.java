package com.backtest.engine.service;

import java.util.List;

/**
 * Identifies trade IDs that should close because their pair's combined P&L
 * meets the regime's profit_exit policy criteria.
 *
 * Returns IDs only — does NOT call exitTrade. The dispatch arm (Patch 22)
 * issues the close calls. This keeps the processor pure and unit-testable.
 *
 * Sits dormant until BacktestServiceImplV2.runBacktestLongShortV2 invokes it.
 * Existing LONG / SHORT strategies never reach this path.
 *
 * LRA Patch 21.
 */
public interface PairProfitExitProcessor {

    /**
     * Returns trade IDs (both legs of each closing pair) to close today.
     * Empty list if profit_exit.enabled is false or no pair meets the threshold.
     */
    List<String> identifyClosures(PairExitContext ctx);
}