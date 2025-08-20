package com.backtest.engine.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.backtest.engine.entity.PriceData;
import com.backtest.engine.service.PriceDataLoaderService;

import tech.tablesaw.api.DateColumn;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.numbers.IntColumnType;

@Service("priceDataService")
public class PriceDataLoaderServiceImpl implements PriceDataLoaderService {

	@Override
	public PriceData loadPricesMarketData(Map<LocalDate, Map<String, Double>> daily_closes,
			Map<LocalDate, Map<String, Double>> daily_opens, Map<LocalDate, Map<String, Double>> daily_highs,
			Map<LocalDate, Map<String, Double>> daily_lows, Map<LocalDate, Set<String>> daily_universes,
			List<LocalDate> trading_dates, List<LocalDate> all_dates) {

		Instant priceDataObjectStart = Instant.now();
		PriceData priceData = PriceData.builder().daily_closes(daily_closes).all_dates(all_dates)
				.daily_highs(daily_highs).daily_lows(daily_lows).daily_opens(daily_opens)
				.daily_universes(daily_universes).trading_dates(trading_dates).all_dates(all_dates).build();

		long priceDataObjectend = Duration.between(priceDataObjectStart, Instant.now()).toMillis();
		System.err.println("Price Pojo Time  : " + priceDataObjectend);

		return priceData;
	}

}
