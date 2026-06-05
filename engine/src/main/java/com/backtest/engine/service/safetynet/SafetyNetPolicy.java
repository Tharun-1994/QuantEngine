package com.backtest.engine.service.safetynet;

import java.time.LocalDate;

import com.backtest.engine.dto.request.SafetyNetItemDto;

/**
 * A stateful safety-net policy attached to a regime.
 *
 * <p>Each policy instance is created fresh per backtest run via
 * {@link SafetyNetRegistry#create(String)}, then:</p>
 * <ol>
 *   <li>{@link #initialize} is called once at backtest setup — the policy
 *       precomputes whatever it needs (date sets, indicator caches,
 *       anchor prices, etc) from its config + market data.</li>
 *   <li>{@link #evaluateAtOpen} is called at the start of each trading day,
 *       before any orders.</li>
 *   <li>{@link #evaluateAtClose} is called at the end of each trading day,
 *       after all signals are processed.</li>
 * </ol>
 *
 * <p>Adding a new policy type means writing a new implementation class +
 * registering it in {@link SafetyNetRegistry} — no day-loop change.</p>
 *
 * <p>Policies should be statefully isolated (each instance is unique to one
 * regime / backtest run) since stateful policies like SPY-vol track peak vol,
 * day counters etc. across days.</p>
 */
public interface SafetyNetPolicy {

    /**
     * Called once at backtest setup. Precompute date sets, indicator caches,
     * anchor prices — anything the policy needs to answer evaluate() cheaply.
     */
    void initialize(SafetyNetItemDto config, SafetyNetInitContext initCtx);

    /** Phase 1: at start of trading day, before any orders. */
    SafetyNetDecision evaluateAtOpen(LocalDate date, LocalDate previousDate);

    /** Phase 2: at end of trading day, after all signals processed. */
    SafetyNetDecision evaluateAtClose(LocalDate date);
}