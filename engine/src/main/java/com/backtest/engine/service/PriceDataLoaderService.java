package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.entity.PriceData;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.util.ArrowDataFrame;
import com.backtest.engine.util.ArrowStringDataFrame;

public interface PriceDataLoaderService {
//	
	public PriceData loadPricesMarketData( Map<LocalDate, Map<String, Float>> daily_closes,  Map<LocalDate, Map<String, Float>> daily_opens,  Map<LocalDate, Map<String, Float>> daily_highs,  Map<LocalDate, Map<String, Float>> daily_lows,
			Map<LocalDate, Set<String>> daily_universes, List<LocalDate> trading_dates, List<LocalDate> all_dates,
			Map<LocalDate, Map<String, Float>> daily_atr);

	public PriceDataV2 loadPricesMarketDatav2(ArrowDataFrame arrowDataFrame, ArrowDataFrame arrowDataFrame2,
			ArrowDataFrame arrowDataFrame3, ArrowDataFrame arrowDataFrame4, ArrowStringDataFrame arrowStringDataFrame,
			List<LocalDate> list, List<LocalDate> list2, ArrowDataFrame daily_atr,Map<String,ArrowDataFrame> marketTickerPrices);
	
	


}
