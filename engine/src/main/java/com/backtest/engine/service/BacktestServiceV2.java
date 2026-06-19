package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.backtest.engine.dto.request.LiveHoldingsSeedDto;
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.PriceDataV2;

public interface BacktestServiceV2 {
	public BacktestReponseDto runBacktestV2(PriceDataV2 priceData, BuySellDataV2 buySellData);

	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, BuySellDataV2 buySellData);

	// Patch 11: 4-arg overload accepts a seedHoldings list for execution mode.
	// Backtest callers continue using the 3-arg version (delegates with null).
	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, Map<LocalDate, String> marketTrends,
			Map<String, BuySellDataV2> rulesOfDayRegimes, List<LiveHoldingsSeedDto> seedHoldings);

	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, Map<LocalDate, String> marketTrends,
			Map<String, BuySellDataV2> rulesOfDayRegimes);

}
