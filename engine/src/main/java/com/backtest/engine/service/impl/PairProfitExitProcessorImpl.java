package com.backtest.engine.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.backtest.engine.entity.TradeLog;
import com.backtest.engine.service.PairExitContext;
import com.backtest.engine.service.PairProfitExitProcessor;

/**
 * Default implementation — signed combined P&L vs threshold, both legs close together.
 *
 * Algorithm:
 *   1. If policy.profit_exit.enabled != true, return [].
 *   2. Group live trades by pairId (skip trades with null pairId — single-leg).
 *   3. For each pair (must have exactly 2 legs), compute combined signed P&L:
 *      - LONG leg P&L  = (currentPrice - entryPrice) * quantity
 *      - SHORT leg P&L = (entryPrice - currentPrice) * quantity     (quantity is positive
 *                                                                    per engine convention)
 *   4. If combinedPnL > threshold, mark both trade IDs for closure.
 *
 * pnl_method must be "signed". "abs_per_leg" (the Python bug) is rejected with
 * a clear exception — RT chose corrected semantics in Phase-1 planning.
 *
 * LRA Patch 21.
 */
@Service
public class PairProfitExitProcessorImpl implements PairProfitExitProcessor {

    @Override
    public List<String> identifyClosures(PairExitContext ctx) {
        Map<String, Object> policy = ctx.getPairExitPolicy();
        if (policy == null) {
            return new ArrayList<>();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> profitExit = (Map<String, Object>) policy.get("profit_exit");
        if (profitExit == null || !Boolean.TRUE.equals(profitExit.get("enabled"))) {
            return new ArrayList<>();
        }

        String pnlMethod = (String) profitExit.getOrDefault("pnl_method", "signed");
        if (!"signed".equals(pnlMethod)) {
            throw new IllegalArgumentException(
                "PairProfitExitProcessor only supports pnl_method='signed'; "
                + "received '" + pnlMethod + "'. The Python 'abs_per_leg' bug "
                + "was explicitly rejected during Phase 1 planning.");
        }

        double threshold = ((Number) profitExit.getOrDefault("threshold", 0)).doubleValue();

        // Group live trades by pairId, skipping non-pair trades
        Map<Integer, List<Map.Entry<String, TradeLog>>> byPair = new HashMap<>();
        for (Map.Entry<String, TradeLog> entry : ctx.getLiveTrades().entrySet()) {
            Integer pid = entry.getValue().getPairId();
            if (pid == null) continue;
            byPair.computeIfAbsent(pid, k -> new ArrayList<>()).add(entry);
        }

        List<String> toClose = new ArrayList<>();
        for (Map.Entry<Integer, List<Map.Entry<String, TradeLog>>> pair : byPair.entrySet()) {
            List<Map.Entry<String, TradeLog>> legs = pair.getValue();
            if (legs.size() != 2) continue;   // malformed pair, skip defensively

            double combined = 0.0;
            for (Map.Entry<String, TradeLog> leg : legs) {
                TradeLog t = leg.getValue();
                Double currentPrice = ctx.getCurrentPrices().get(t.getSymbol());
                if (currentPrice == null) {
                    // missing price — treat pair as unevaluable for the day
                    combined = Double.NaN;
                    break;
                }
                double entryPrice = t.getEntryPrice();
                int qty = t.getQuantity();
                double legPnL;
                if ("LONG".equals(t.getDirection())) {
                    legPnL = (currentPrice - entryPrice) * qty;
                } else {
                    // SHORT — quantity is positive per engine convention; flip the sign
                    legPnL = (entryPrice - currentPrice) * qty;
                }
                combined += legPnL;
            }

            if (!Double.isNaN(combined) && combined > threshold) {
                for (Map.Entry<String, TradeLog> leg : legs) {
                    toClose.add(leg.getKey());
                }
            }
        }
        return toClose;
    }
}