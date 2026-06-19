package com.backtest.engine.service;

import java.util.List;

/**
 * Identifies trade IDs that should close because they've reached
 * pair_exit_policy.max_hold_sessions.
 *
 * Returns IDs only — does NOT call exitTrade. The dispatch arm (Patch 22)
 * issues the close calls.
 *
 * LRA Patch 21.
 */
public interface ForceCloseExitProcessor {

    /** Returns trade IDs to force-close today. Empty list if no pair has aged out. */
    List<String> identifyClosures(PairExitContext ctx);
}