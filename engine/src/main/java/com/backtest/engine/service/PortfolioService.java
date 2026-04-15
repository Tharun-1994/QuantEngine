package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.dto.request.EntrySignalsRequestDto;
import com.backtest.engine.dto.request.ExitSignalsRequestDto;
import com.backtest.engine.dto.request.LimitEntrySignalDto;
import com.backtest.engine.dto.request.TradeEnterRequestDto;
import com.backtest.engine.dto.request.TradeExitRequestDto;
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.LiveHoldingsTracker;
import com.backtest.engine.entity.PriceData;
import com.backtest.engine.entity.PriceDataV2;

public interface PortfolioService {

	public BacktestReponseDto getPortfolio();

	public void executeEntrySignals(EntrySignalsRequestDto entrySignalsRequest);
	
	public void executeExitSignals(ExitSignalsRequestDto exitSignalsRequest);

	public void enterTrade(TradeEnterRequestDto tradeEnterRequest);

	public void exitTrade(TradeExitRequestDto tradeExitRequest);

	public void markToMarket(LocalDate tradeDate);

	public void endOfBacktest(LocalDate tradeDate);

	
	public Set<String> getLiveHoldingsLogger();
	public void checkLivePositionsOnTommorow(LocalDate date);
	
	
	public void checkStoplossHit(LocalDate date, String systemType,String timing);
	public void checkTakeProfit(LocalDate date, String systemType,String timing);
	void setPriceDate(PriceData priceData, float startingCapital, int maxSlots, int stoplossPct, int takeProfitPct);

	public void executeLimitOrdersLong(LimitEntrySignalDto entrySignals);

	void checkMaxTime(LocalDate tradeDate, int maxTime, PriceDataV2 priceData);

	void updateTradeDayCount(LocalDate tradeDate);

	void setBasicDeatils(PriceDataV2 priceData, float startingCapital, int maxSlots, float stoplossPct,
			float takeProfitPct);

	void closeAllPositionsOnOpenPrice(LocalDate tradeDate, PriceDataV2 priceData, String reasonOfExit);

	Map<String, Long> getLiveHoldingsTickerCounts();

	void setPriceDate(PriceDataV2 priceData, float startingCapital, int maxSlots, float stoplossPct,
			float takeProfitPct);
	
	

}
