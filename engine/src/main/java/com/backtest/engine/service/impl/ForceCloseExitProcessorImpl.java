package com.backtest.engine.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.backtest.engine.entity.TradeLog;
import com.backtest.engine.service.ForceCloseExitProcessor;
import com.backtest.engine.service.PairExitContext;

/**
 * Default impl — per-position max-hold check. Each open paired position closes
 * once its sessionsHeld reaches policy.max_hold_sessions, independent of
 * whether its partner leg is younger or older.
 *
 * The "per_position" method is the corrected semantic chosen during Phase-1
 * planning (RT: "there could be bug in python script, we should develop it in
 * correct way"). The Python "portfolio_shielded" early-return bug is rejected
 * with a clear exception if encoded in the policy.
 *
 * LRA Patch 21.
 */
@Service
public class ForceCloseExitProcessorImpl implements ForceCloseExitProcessor {

    @Override
    public List<String> identifyClosures(PairExitContext ctx) {
        Map<String, Object> policy = ctx.getPairExitPolicy();
        if (policy == null) {
            return new ArrayList<>();
        }
        Object maxHoldObj = policy.get("max_hold_sessions");
        if (maxHoldObj == null) {
            return new ArrayList<>();
        }
        int maxHoldSessions = ((Number) maxHoldObj).intValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> forceClose = (Map<String, Object>) policy.get("force_close");
        String method = forceClose == null
                ? "per_position"
                : (String) forceClose.getOrDefault("method", "per_position");
        if (!"per_position".equals(method)) {
            throw new IllegalArgumentException(
                "ForceCloseExitProcessor only supports method='per_position'; "
                + "received '" + method + "'. The Python 'portfolio_shielded' "
                + "early-return bug was explicitly rejected during Phase 1 planning.");
        }

        List<String> toClose = new ArrayList<>();
        for (Map.Entry<String, TradeLog> entry : ctx.getLiveTrades().entrySet()) {
            TradeLog t = entry.getValue();
            // Only consider paired positions — single-direction trades have their
            // own exit semantics (rule trees, stoploss, etc.) and don't reach here.
            if (t.getPairId() == null) continue;

            Integer sessions = ctx.getSessionsHeldByTradeId().get(entry.getKey());
            if (sessions != null && sessions >= maxHoldSessions) {
                toClose.add(entry.getKey());
            }
        }
        return toClose;
    }
}