package com.backtest.engine.service;

/**
 * Computes the dollar notional for one leg of a pair under a given sizing policy.
 *
 * Each implementation handles exactly one mode key (see {@link #getMode()}).
 * The Patch 22 dispatch arm picks the right impl by reading sizing_policy.mode
 * from the regime.
 *
 * Sits dormant until BacktestServiceImplV2.runBacktestLongShortV2 (Patch 22)
 * dispatches into it. Existing LONG / SHORT strategies never reach this path.
 *
 * LRA Patch 18.
 */
public interface SizingPolicyResolver {

    /**
     * Mode key this resolver handles. Matches the "mode" field in the
     * sizing_policy JSON on a MarketRegime row.
     */
    String getMode();

    /**
     * Compute the per-leg dollar notional under this resolver's mode.
     *
     * @param ctx args bundle — see SizingContext for field semantics
     * @return per-leg dollar notional (positive value; caller flips sign for shorts)
     */
    Double resolve(SizingContext ctx);
}