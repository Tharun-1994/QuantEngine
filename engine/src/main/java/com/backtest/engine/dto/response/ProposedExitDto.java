package com.backtest.engine.dto.response;

import java.time.LocalDate;

import lombok.Builder;
import lombok.Data;

/**
 * Patch 26: one row in the SingleBarSignalsResponseDto's proposedExits[]
 * list. Emitted by SingleBarEvaluatorImpl for each LIVE holding whose
 * exit rule fires on the last bar. Mutually exclusive with stopUpdates[]
 * per position — a LIVE position either exits OR gets a stop update.
 *
 * exitDate = the intended execution date (request.runDate). The actual
 * broker order fires at exit_timing="open" tomorrow morning (MOO) or
 * exit_timing="close" tomorrow EOD (MOC). Whether the position
 * ultimately fills at the broker depends on borrow availability / market
 * conditions — that reconciliation happens in the morning broker_write step.
 */
@Data
@Builder
public class ProposedExitDto {
    private String tradeId;     // round-trip-stable; PM uses this to find the LIVE row
    private String symbol;
    private String exitReason;  // "exit rule fired on YYYY-MM-DD"
    private LocalDate exitDate; // intended execution date
    private String exitTiming;  // "open" | "close" — echoed from regime
}