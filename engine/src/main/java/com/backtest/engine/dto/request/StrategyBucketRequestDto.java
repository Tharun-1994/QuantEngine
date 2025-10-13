package com.backtest.engine.dto.request;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StrategyBucketRequestDto {
	
	private Integer id;

	private String name; // strategy_name in form

	@JsonProperty("created_at")
	private LocalDateTime createdAt;

	// Timing & rebalance
	private String rebalance;

	@JsonProperty("start_date")
	private LocalDate startDate;

	@JsonProperty("end_date")
	private LocalDate endDate;

	// Min constraints
	@JsonProperty("min_quantity")
	private Integer minQuantity;

	@JsonProperty("min_price")
	private float minPrice;

	// System details
	@JsonProperty("system_type")
	private String systemType;

	@JsonProperty("market_regime_type")
	private String marketRegimeType;

	// Regimes
	private List<MarketRegimeDto> regimes;
}
