package com.backtest.engine.service.safetynet;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SafetyNetDecision {
    /**
     * Four kinds of decisions:
     *   NOOP    — no state change.
     *   FREEZE  — suspend trading AND close all existing positions.
     *   SUSPEND — suspend trading WITHOUT closing positions (e.g. SPY-vol re-arm).
     *   RESUME  — lift suspension.
     */
    public enum Action { NOOP, FREEZE, SUSPEND, RESUME }

    private final Action action;
    /** Human-readable reason for logging / exit-trade reasons. */
    private final String reason;

    public static SafetyNetDecision noop() {
        return SafetyNetDecision.builder().action(Action.NOOP).build();
    }
    public static SafetyNetDecision freeze(String reason) {
        return SafetyNetDecision.builder().action(Action.FREEZE).reason(reason).build();
    }
    public static SafetyNetDecision suspend(String reason) {
        return SafetyNetDecision.builder().action(Action.SUSPEND).reason(reason).build();
    }
    public static SafetyNetDecision resume(String reason) {
        return SafetyNetDecision.builder().action(Action.RESUME).reason(reason).build();
    }

    public boolean isFreeze()  { return action == Action.FREEZE; }
    public boolean isSuspend() { return action == Action.SUSPEND; }
    public boolean isResume()  { return action == Action.RESUME; }
}