package com.backtest.engine.dto.request;

import java.time.LocalDate;
import lombok.Data;

/**
 * Patch 10: payload shape for seeding portfolio state in execution mode.
 * One element per live position the engine must treat as already-held when
 * the day loop begins. Mirrors the fields of TradeEnterRequestDto with two
 * key differences:
 *   - tradeId is supplied by the caller (deterministic, round-trip-stable
 *     across nightly runs — middleware maps it to tradelist.id)
 *   - batch payload, one DTO per position
 *
 * Phase 1 single-direction only. pairId stays null.
 */
@Data
public class LiveHoldingsSeedDto {
    private String tradeId;
    private String symbol;
    private String direction;
    private LocalDate entryDate;
    private float entryprice;
    private int quantity;
    private int capital;
    private String entryTiming;
    private String entryReason;
    // LRA Patch 20: nullable. Phase 1 single-direction = null.
    private Integer pairId;
    private Float currentStopPrice;
}