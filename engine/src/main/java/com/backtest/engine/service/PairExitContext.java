package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.Map;

import com.backtest.engine.entity.TradeLog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Argument bundle for the pair-exit processors. One instance per trading day.
 *
 * Both PairProfitExitProcessor and ForceCloseExitProcessor consume this context.
 * Fields not used by a given processor are simply ignored (e.g. ForceClose
 * doesn't read currentPrices).
 *
 * LRA Patch 21.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PairExitContext {

    /** Open trades only — tradeId -> TradeLog. The dispatch arm filters live trades. */
    private Map<String, TradeLog> liveTrades;

    /** Today's close price per symbol — used by profit-exit P&L math. */
    private Map<String, Double> currentPrices;

    /**
     * Trading-session count from entry to today per trade id. Day 0 is the
     * entry session, day 1 is the next session, etc. Computed by the dispatch
     * arm from entryDate and the universe's trading calendar.
     */
    private Map<String, Integer> sessionsHeldByTradeId;

    /**
     * Deserialised pair_exit_policy JSON from the regime. Shape:
     * <pre>
     * {
     *   "max_hold_sessions": 2,
     *   "force_close": { "method": "per_position" },
     *   "profit_exit": {
     *     "enabled": true,
     *     "threshold": 0,
     *     "pnl_method": "signed"
     *   }
     * }
     * </pre>
     * Null means "no pair exit rules" — both processors return empty lists.
     */
    private Map<String, Object> pairExitPolicy;

    /** Trading date for which exits are being evaluated. */
    private LocalDate date;
}