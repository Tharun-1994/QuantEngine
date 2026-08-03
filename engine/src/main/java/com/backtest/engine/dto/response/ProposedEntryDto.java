package com.backtest.engine.dto.response;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;
import lombok.Data;

/**
 * Patch 25: one row in the SingleBarSignalsResponseDto's proposedEntries[]
 * (or substitutePool[]) list. Built per-ticker by SingleBarEvaluatorImpl
 * from signalsForTheDayV1's ranked entry list + per-slot sizing math.
 *
 * For NORMAL/MKT order types, limitPrice + stopPrice are null. For
 * LIMIT / LIMIT_ATR they are populated by a follow-up patch once the
 * day-loop's HV/ATR math is extracted to a shared helper.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProposedEntryDto {
    private String symbol;
    private String direction;       // "LONG" | "SHORT"
    private String orderType;       // "NORMAL" | "MKT" | "LIMIT" | "LIMIT_ATR"
    private LocalDate entryDate;    // intended execution date (run_date = next trading day)
    private String entryTiming;     // "open" | "close" — echoed from regime
    private String entryReason;
    private int quantity;
    private float capital;          // estimate: qty * last close (NORMAL/MKT)
    private String sector;          // for cap-enforcement audit on the receiving side
    private int rank;               // 1 = top of ranked list
    private Float score;            // ranking metric value (e.g. HV(20)); null if unranked

    // Populated only for LIMIT / LIMIT_ATR order types.
    // NORMAL/MKT entries leave these null — middleware writes MKT orders.
    private Float limitPrice;
    private Float stopPrice;        // initial stop for LIMIT_ATR bracket
    private Float tpPrice;          // Patch 90: initial take-profit for LIMIT_ATR bracket
}