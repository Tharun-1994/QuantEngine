package com.backtest.engine.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;
import lombok.Data;

/**
 * Limit order entity. The original two fields (ticker, limitPrice) are used by
 * the engine's day loop for fill simulation in backtest mode.
 *
 * Patch 15 (2026-06-12): added rich fields populated ONLY in execution mode
 * (via the skip-last-bar capture in BacktestServiceImplV2's 4-arg method).
 * Position Manager (middleware C2) uses these to insert PROPOSED rows on
 * tradelist without re-deriving the data the engine already computed.
 *
 * All Patch 15 fields are object types (Integer / Float / String) with
 * @JsonInclude(NON_NULL) so backtest-mode JSON output is byte-identical to
 * pre-Patch 15. Builder leaves them null when not set.
 */
@Builder
@Data
public class LimitOrder {
	// Original Phase B fields — populated in backtest and execution mode
	private String ticker;
	private float limitPrice;   // 0.0 reserved for MKT-style orders (Patch 16 will use this)

	// Patch 15: execution-mode rich data. Null in backtest mode.
	@JsonInclude(JsonInclude.Include.NON_NULL) private String  direction;
	@JsonInclude(JsonInclude.Include.NON_NULL) private Integer rank;
	@JsonInclude(JsonInclude.Include.NON_NULL) private Integer intendedQty;
	@JsonInclude(JsonInclude.Include.NON_NULL) private Float   intendedCapital;
	@JsonInclude(JsonInclude.Include.NON_NULL) private Float   referenceClose;
	@JsonInclude(JsonInclude.Include.NON_NULL) private Float   initialStopPrice;
	@JsonInclude(JsonInclude.Include.NON_NULL) private Float   initialTpPrice;
	@JsonInclude(JsonInclude.Include.NON_NULL) private Float   rankingValue;
}