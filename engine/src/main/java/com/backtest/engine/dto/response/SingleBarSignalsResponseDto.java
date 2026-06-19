package com.backtest.engine.dto.response;

import java.time.LocalDate;
import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * Patch 28: response body for POST /api/execution/signals/last-bar.
 *
 * Replaces the day-loop endpoint's BacktestReponseDto (which carried
 * a full tradeLogger + equityLogger + fillOutcomes from 2023-onwards
 * simulation, none of which is needed for execution). The single-bar
 * endpoint emits only what middleware needs to:
 *   - write PROPOSED + SUBSTITUTE_POOL rows to the tradelist
 *   - mark LIVE rows for tomorrow's exit (proposedExits)
 *   - update current_stop_price on LIVE rows (stopUpdates)
 *
 * dataDate = the last bar in priceData (the data date, e.g. 2026-06-12)
 * runDate  = intended execution date = next trading day (e.g. 2026-06-15)
 * activeRegimeOnLastBar = which regime evaluated; null when no regime
 *                         was active (regime gate off → all lists empty)
 */
@Data
@Builder
public class SingleBarSignalsResponseDto {
	private LocalDate dataDate;
    private LocalDate runDate;
    private String activeRegimeOnLastBar;
    // Single ranked candidate list — matches BacktestReponseDto.proposedOrders
    // contract. Middleware splits this into PROPOSED (top N=slots) and
    // SUBSTITUTE_POOL (next M=marketregime.substitute_pool_size from PM's DB).
    // Engine doesn't know substitute_pool_size; that's middleware's job.
    private List<ProposedEntryDto> proposedEntries;
    private List<ProposedExitDto> proposedExits;
    private List<StopUpdateDto> stopUpdates;

    // Patch 68: PORTFOLIO stoploss trip signal.
    // When true (i.e. == Boolean.FALSE), middleware (runner.py, Patch 70) flips
    // strategy.execution_enabled = False. The proposedExits list already
    // contains close-all rows for every LIVE position; proposedEntries and
    // stopUpdates are empty in this case. The disable reason is the engine's
    // human-readable trip text ("Portfolio Stoploss Hit: 15.30% (threshold 15%) on 2026-06-12").
    private Boolean executionEnabledChange;
    private String  executionDisableReason;
}