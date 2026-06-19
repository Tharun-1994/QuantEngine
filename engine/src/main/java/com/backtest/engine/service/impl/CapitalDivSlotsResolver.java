package com.backtest.engine.service.impl;

import com.backtest.engine.service.SizingContext;
import com.backtest.engine.service.SizingPolicyResolver;

/**
 * Default sizing mode — per-leg notional = capital / slots.
 *
 * Mirrors the behaviour every existing LONG / SHORT strategy already gets when
 * sizing_policy is null. NOT wired into the existing capital/slots inline math
 * by Patch 18 — those code paths remain untouched. This resolver is only
 * invoked once the LONGSHORT path (Patch 22) starts dispatching to it.
 *
 * LRA Patch 18.
 */
public class CapitalDivSlotsResolver implements SizingPolicyResolver {

    public static final String MODE = "capital_div_slots";

    @Override
    public String getMode() {
        return MODE;
    }

    @Override
    public Double resolve(SizingContext ctx) {
        if (ctx.getCapital() == null) {
            throw new IllegalArgumentException("capital_div_slots requires non-null capital");
        }
        if (ctx.getSlots() == null || ctx.getSlots() == 0) {
            throw new IllegalArgumentException("capital_div_slots requires non-zero slots");
        }
        return ctx.getCapital() / ctx.getSlots();
    }
}