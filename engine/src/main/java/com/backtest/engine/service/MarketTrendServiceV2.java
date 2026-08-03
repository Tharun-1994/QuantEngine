package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.backtest.engine.dto.request.MarketRegimeDto;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.util.ArrowDataFrame;

public interface MarketTrendServiceV2 {

	public Map<LocalDate,String> generateMarketTrend(List<MarketRegimeDto> marketRegimes, Map<String, ArrowDataFrame> marketTrendMap, PriceDataV2 priceData);
}
