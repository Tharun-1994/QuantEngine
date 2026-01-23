package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.Map;

import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.PriceDataV2;

public interface BacktestServiceV3 {
	
	
	public BacktestReponseDto runBacktestV2(PriceDataV2 priceData, BuySellDataV2 buySellData);
	
	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, BuySellDataV2 buySellData);

	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, Map<LocalDate, String> marketTrends,
			Map<String, BuySellDataV2> rulesOfDayRegimes);
}
