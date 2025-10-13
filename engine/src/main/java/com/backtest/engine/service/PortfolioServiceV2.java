package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.Set;

import com.backtest.engine.dto.request.EntrySignalsRequestDto;
import com.backtest.engine.dto.request.ExitSignalsRequestDto;
import com.backtest.engine.dto.request.LimitEntrySignalDto;
import com.backtest.engine.dto.request.TradeEnterRequestDto;
import com.backtest.engine.dto.request.TradeExitRequestDto;
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.PriceDataV2;

public interface PortfolioServiceV2 {


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
	void setPriceDate(PriceDataV2 priceData, float startingCapital, int maxSlots, float stoplossPct, float takeProfitPct);

	public void executeLimitOrdersLong(LimitEntrySignalDto entrySignals);
	
	
	public void setBasicDeatils(PriceDataV2 priceData, float startingCapital, int maxSlots, float stoplossPct,
			float takeProfitPct);
	
	


}
