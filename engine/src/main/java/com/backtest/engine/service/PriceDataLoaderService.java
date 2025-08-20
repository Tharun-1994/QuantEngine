package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.entity.PriceData;

import tech.tablesaw.api.Table;

public interface PriceDataLoaderService {
//	
	public PriceData loadPricesMarketData( Map<LocalDate, Map<String, Double>> daily_closes,  Map<LocalDate, Map<String, Double>> daily_opens,  Map<LocalDate, Map<String, Double>> daily_highs,  Map<LocalDate, Map<String, Double>> daily_lows,
			Map<LocalDate, Set<String>> daily_universes, List<LocalDate> trading_dates, List<LocalDate> all_dates);

}
