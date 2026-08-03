package com.backtest.engine.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * Patch 27: one row in the SingleBarSignalsResponseDto's stopUpdates[]
 * list. Emitted for each LIVE holding whose exit rule did NOT fire —
 * the engine just publishes today's stop value for tomorrow's broker
 * bracket. Whether the stop is hit by tomorrow's price action is
 * observed at the broker, not predicted by the engine.
 *
 * source field:
 *   "trader_override" — currentStopPrice was set by the trader via F2
 *                       (D3 plumbing); engine echoes it unchanged
 *   "pct_recompute"   — engine computed from regime.stoploss_pct on the
 *                       seeded entry price
 *
 * newStopPrice = null when no stop is configured (stoploss_pct=0 and no
 * trader override). PM uses null to mean "no broker stop bracket needed".
 */
@Data
@Builder
public class StopUpdateDto {
    private String tradeId;
    private String symbol;
    private Float newStopPrice;
    // Patch 108: daily take-profit maintenance value (legacy take_profit_orders).
    // ATR_BASED: entry + takeProfitPct x stoplossPct x ATR(today), UNCAPPED.
    // Null = no TP bracket needed. Engine-computed only (no D3 override).
    private Float newTpPrice;
    private String source;
}