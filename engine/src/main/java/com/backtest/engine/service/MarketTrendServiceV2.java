package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.backtest.engine.dto.request.MarketRegimeDto;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.util.ArrowDataFrame;

public interface MarketTrendServiceV2 {

	public Map<LocalDate, String> generateMarketTrend(List<MarketRegimeDto> marketRegimes,
			Map<String, ArrowDataFrame> marketTrendMap, PriceDataV2 priceData);

	// DualStopPct: evaluate one rule tree (Map shape) → dates it matches, keyed by
	// its concatenated leaf label. Reused for stop-state rules.
	public Map<LocalDate, String> generateMarketSignalsFromTree(Map<String, Object> treeMap,
			Map<String, ArrowDataFrame> marketTrendMap, PriceDataV2 priceData);
}
