package com.backtest.engine.entity;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;   // LRA Patch 20

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TradeLog {

	private String symbol;
	private String direction;
	private LocalDate entryDate;
	private float entryPrice;
	private float entryValue;
	private String entryReason;
	private int quantity;
	private LocalDate exitDate;
	private float exitPrice;
	private float exitValue;
	private String exitReason;

	private float profit;
	private float profitPercentage;
	private String entryTiming;
	private String exitTiming;
	
	private int dayCount;

	private int capital;

	private Map<String, List<LiveHoldingsTracker>> valueTracker;

	// LRA Patch 20: nullable pair identifier. Null for every single-direction
	// (LONG / SHORT) trade; set to the TradePair.pairId by PairingService
	// for LONGSHORT trades. Both legs of a pair share the same pairId.
	private Integer pairId;
	private Float currentStopPrice;

	// LRA Patch 20: explicit getter so @JsonInclude(NON_NULL) is honored by
	// Jackson. Lombok's @Data does not copy field-level annotations to the
	// generated getter, so we hand-write this one and Lombok skips generating it.
	// Effect: when pairId is null (every ROC trade), the field is omitted from
	// the TradeList.json output, preserving byte-identical existing tradelists.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public Integer getPairId() {
		return this.pairId;
	}
}