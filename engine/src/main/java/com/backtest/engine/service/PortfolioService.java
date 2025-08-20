package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.backtest.engine.dto.request.EntrySignalsRequestDto;
import com.backtest.engine.dto.request.ExitSignalsRequestDto;
import com.backtest.engine.dto.request.TradeEnterRequestDto;
import com.backtest.engine.dto.request.TradeExitRequestDto;
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.LiveHoldingsTracker;
import com.backtest.engine.entity.PriceData;

public interface PortfolioService {

	public BacktestReponseDto getPortfolio();
	public void setPriceDate(PriceData priceData, float startingCapital, int maxSlots);

	public void executeEntrySignals(EntrySignalsRequestDto entrySignalsRequest);
	
	public void executeExitSignals(ExitSignalsRequestDto exitSignalsRequest);

	public void enterTrade(TradeEnterRequestDto tradeEnterRequest);

	public void exitTrade(TradeExitRequestDto tradeExitRequest);

	public void markToMarket(LocalDate tradeDate);

	public void endOfBacktest(LocalDate tradeDate);

	
	public Set<String> getLiveHoldingsLogger();
	public void checkLivePositionsOnTommorow(LocalDate date);
	
	

}
