package com.backtest.engine.service;

import com.backtest.engine.entity.BuySellData;
import com.backtest.engine.entity.PriceData;

public interface BacktestService {
	
	public void runBacktest(PriceData priceData, BuySellData buySellData);

}
