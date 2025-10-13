package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.PriceData;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.entity.StrategyDataV2;

public interface StrategyBuilderServiceV2 {

	public BuySellDataV2 generateSignals(StrategyDataV2 strategyData,PriceDataV2 priceData);

	public Map<String, List<String>> signalsForTheDay(LocalDate date, PriceDataV2 priceData, BuySellDataV2 buySellData,
			PortfolioServiceV2 portfolioService);
}
