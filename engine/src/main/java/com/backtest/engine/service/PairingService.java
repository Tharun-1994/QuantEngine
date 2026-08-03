package com.backtest.engine.service;

import java.util.List;

import com.backtest.engine.entity.TradePair;

/**
 * Constructs valid (long, short) pairs from filtered candidate sets, applying
 * the regime's pairing_entry_rules. Handles backtracking when a candidate
 * pair matches a disallowed combo — swaps the offending leg with the next
 * valid candidate from the pre-reduction pool.
 *
 * Generic primitive — not LRA-specific. The pairing JSON's "attribute" field
 * lets future strategies pair on sector / region / any per-ticker tag.
 *
 * Sits dormant until BacktestServiceImplV2.runBacktestLongShortV2 (Patch 22)
 * dispatches into it. Existing LONG / SHORT strategies never reach this code.
 *
 * LRA Patch 19.
 */
public interface PairingService {

    /**
     * Returns the final list of pairs for the trading day, with pairIds assigned
     * sequentially starting at 0. Pairs that cannot be resolved via backtracking
     * are dropped silently.
     */
    List<TradePair> constructPairs(PairingContext ctx);
}