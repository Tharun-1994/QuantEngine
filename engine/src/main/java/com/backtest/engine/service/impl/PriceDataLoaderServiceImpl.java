package com.backtest.engine.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.backtest.engine.entity.PriceData;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.service.PriceDataLoaderService;
import com.backtest.engine.util.ArrowDataFrame;
import com.backtest.engine.util.ArrowStringDataFrame;

@Service("priceDataService")
public class PriceDataLoaderServiceImpl implements PriceDataLoaderService {

	@Override
	public PriceData loadPricesMarketData(Map<LocalDate, Map<String, Float>> daily_closes,
			Map<LocalDate, Map<String, Float>> daily_opens, Map<LocalDate, Map<String, Float>> daily_highs,
			Map<LocalDate, Map<String, Float>> daily_lows, Map<LocalDate, Set<String>> daily_universes,
			List<LocalDate> trading_dates, List<LocalDate> all_dates,Map<LocalDate, Map<String, Float>> daily_atr) {

		Instant priceDataObjectStart = Instant.now();
		PriceData priceData = PriceData.builder().daily_closes(daily_closes).all_dates(all_dates)
				.daily_highs(daily_highs).daily_lows(daily_lows).daily_opens(daily_opens)
				.daily_universes(daily_universes).trading_dates(trading_dates).all_dates(all_dates)
				
				// This DAILY ATR would have data only if it has Limit Order type is ATR based
				.daily_atr(daily_atr)
				.build();

		long priceDataObjectend = Duration.between(priceDataObjectStart, Instant.now()).toMillis();

		return priceData;
	}

	@Override
	public PriceDataV2 loadPricesMarketDatav2(ArrowDataFrame daily_closes,
			ArrowDataFrame daily_opens, ArrowDataFrame daily_highs,
			ArrowDataFrame daily_lows, ArrowStringDataFrame daily_universes,
			List<LocalDate> trading_dates, List<LocalDate> all_dates,ArrowDataFrame daily_atr,Map<String,ArrowDataFrame> marketTickerPrices) {

		Instant priceDataObjectStart = Instant.now();
		PriceDataV2 priceData = PriceDataV2.builder().daily_closes(daily_closes).all_dates(all_dates)
				.daily_highs(daily_highs).daily_lows(daily_lows).daily_opens(daily_opens)
				.daily_universes(daily_universes).trading_dates(trading_dates).all_dates(all_dates)
				
				//Market regime ticker close prices
				.marketTickerPrices(marketTickerPrices)
				// This DAILY ATR would have data only if it has Limit Order type is ATR based
				.daily_atr(daily_atr)
				.build();

		long priceDataObjectend = Duration.between(priceDataObjectStart, Instant.now()).toMillis();

		return priceData;
	}

}
