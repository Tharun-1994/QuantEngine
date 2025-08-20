package com.backtest.engine.entity;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;

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
	private Double exitPrice;
	private float exitValue;
	private String exitReason;

	private float profit;
	private float profitPercentage;
	private String entryTiming;
	private String exitTiming;

	private int capital;

	private Map<String, List<LiveHoldingsTracker>> valueTracker;

}
