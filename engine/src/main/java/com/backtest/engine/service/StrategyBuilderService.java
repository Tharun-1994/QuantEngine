package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.entity.BuySellData;
import com.backtest.engine.entity.PriceData;
import com.backtest.engine.entity.StrategyData;

public interface StrategyBuilderService {

	public BuySellData generateSignals(StrategyData strategyData);

	public Map<String, List<String>> signalsForTheDay(LocalDate date, PriceData priceData, BuySellData buySellData, PortfolioService portfolioService);


}
