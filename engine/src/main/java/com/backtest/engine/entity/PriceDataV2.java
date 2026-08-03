package com.backtest.engine.entity;
import lombok.AllArgsConstructor;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.backtest.engine.util.ArrowDataFrame;
import com.backtest.engine.util.ArrowStringDataFrame;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
@AllArgsConstructor
public class PriceDataV2 {
	private final ArrowDataFrame daily_closes;
	private final ArrowDataFrame daily_opens;
	private final ArrowDataFrame daily_highs;
	private final ArrowDataFrame daily_lows;
	private final ArrowStringDataFrame daily_universes;
	private final List<LocalDate> trading_dates;
	private final List<LocalDate> all_dates;

	// this would have data only if it has Limit/STP/Takeprofit Order type is ATR based
	private final ArrowDataFrame daily_atr;
	
	private Map<String, String> paths;
	
	private final Map<String,ArrowDataFrame> marketTickerPrices;
	
	private Map<LocalDate, String> marketTrendOfDay;
	
	private LocalDate endDate;

	public Float getValue(String ticker, LocalDate date, String valueIndicator) {
		
		if(valueIndicator.equalsIgnoreCase("close")) {
			
			return this.daily_closes.getValue(date, ticker);
		}
		return null;
	}
	
	
}
