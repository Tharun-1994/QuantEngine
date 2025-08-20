package com.backtest.engine.entity;



import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.Builder;
import lombok.Data;
import tech.tablesaw.api.Table;
@Builder
@Data
public class PriceData {
	private final Map<LocalDate, Map<String, Double>> daily_closes;
	private final Map<LocalDate, Map<String, Double>> daily_opens;
	private final Map<LocalDate, Map<String, Double>> daily_highs;
	private final Map<LocalDate, Map<String, Double>> daily_lows;
	private final Map<LocalDate, Set<String>> daily_universes;
	private final List<LocalDate> trading_dates;
	private final List<LocalDate> all_dates;
	
	
}
