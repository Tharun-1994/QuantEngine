package com.backtest.engine.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class StrategyRequestDto {
	private int id;
	private String rebalance;
	private String name;
	private String universe;
	private int slots;
	private int capital;
	@JsonProperty("start_date")
	private LocalDate startDate;
	@JsonProperty("end_date")
	private LocalDate endDate;
	@JsonProperty("stoploss_pct")
	private int stoplossPct;
	@JsonProperty("takeprofit_pct")
	private int takeprofitPct;
	@JsonProperty("entry_rules")
	private String entryRules;
	@JsonProperty("exit_rules")
	private String exitRules;
	
	private String ranking;
	
	@JsonProperty("stoploss_timing")
	private String stoplossTiming;

	@JsonProperty("takeprofit_timing")
	private String takeprofitTiming;

	@JsonProperty("entry_timing")
	private String entryTiming;

	@JsonProperty("exit_timing")
	private String exitTiming;

	@JsonProperty("ranking_lookback")
	private Integer rankingLookback;
	
	@JsonProperty("ranking_order")
	private String rankingOrder;

}
