package com.backtest.engine.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.dto.request.EntrySignalsRequestDto;
import com.backtest.engine.dto.request.ExitSignalsRequestDto;
import com.backtest.engine.dto.request.LimitEntrySignalDto;
import com.backtest.engine.dto.request.LiveHoldingsSeedDto;
import com.backtest.engine.dto.request.TradeEnterRequestDto;
import com.backtest.engine.dto.request.TradeExitRequestDto;
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.entity.TradeLog;

public interface PortfolioServiceV2 {

	public BacktestReponseDto getPortfolio();

	public void executeEntrySignals(EntrySignalsRequestDto entrySignalsRequest);

	public void executeExitSignals(ExitSignalsRequestDto exitSignalsRequest);

	public void enterTrade(TradeEnterRequestDto tradeEnterRequest);

	public void exitTrade(TradeExitRequestDto tradeExitRequest);

	public void markToMarket(LocalDate tradeDate);

	public void endOfBacktest(LocalDate tradeDate);

	public Set<String> getLiveHoldingsLogger();

	public Map<String, Long> getLiveHoldingsTickerCounts();

	public void checkLivePositionsOnTommorow(LocalDate date);

	public void checkStoplossHit(LocalDate date, String systemType, String timing);

	public void checkTakeProfit(LocalDate date, String systemType, String timing);

	void setPriceDate(PriceDataV2 priceData, float startingCapital, int maxSlots, float stoplossPct,
			float takeProfitPct);

	public void executeLimitOrdersLong(LimitEntrySignalDto entrySignals);

	public void executeLimitOrdersShort(LimitEntrySignalDto entrySignals);

	public void setBasicDeatils(PriceDataV2 priceData, float startingCapital, int maxSlots, float stoplossPct,
			float takeProfitPct);

	public void closeAllPositionsOnOpenPrice(LocalDate tradeDate, PriceDataV2 priceData, String reasonOfExit);

	public void updateTradeDayCount(LocalDate tradeDate);

	public void checkMaxTime(LocalDate date, int maxTime, PriceDataV2 priceData);

	public void closeAllPositionsAtEodClose(LocalDate date);

	public void closeAllPositionsAtClose(LocalDate date, String reasonOfExit);

	public void checkStoplossHitAtr(LocalDate date, String systemType, String stoplossTiming);

	public void checkTakeProfitAtr(LocalDate date, String systemType, String takeprofitTiming);

	// Patch 63: DOLLAR_BASED per-position stop using absolute $ from entry price.
	// ETF-only at the UI layer (regime gating). Mirrors checkStoplossHit
	// (PCT) but uses stoplossDollar instead of stoplossPct × entryPrice.
	public void checkStoplossHitDollar(LocalDate date, String systemType, String timing);

	// Patch 63: PORTFOLIO drawdown check. Returns true if portfolio equity
	// drop from startingCapital ≥ stoplossPct on the given date. Caller (the
	// day-loop) flips portfolioStoplossTripped and stops further processing.
	// Reads markToMarket equity from equityLogger; must be called AFTER
	// markToMarket(date).
	public boolean checkPortfolioStoplossHit(LocalDate date);

	// Patch 63: state inspection for the PORTFOLIO halt flag.
	public boolean isPortfolioStoplossTripped();

	public String getPortfolioStoplossReason();

	// Patch 63: setBasicDeatils overload to receive stoplossDollar (was zero
	// before).
	public void setStoplossDollar(float stoplossDollar);

	// Patch 72n: drawdown anchor for PORTFOLIO stoploss. PEAK uses maxEquity
	// (all-time peak); DAILY uses previous trading day's logged equity.
	// Null/empty defaults to PEAK in the implementation. Called by
	// BacktestServiceImplV2 immediately after setPriceDate/setBasicDeatils.
	public void setPortfolioStoplossAnchor(String anchor);

	// Patch 73a: clear the PORTFOLIO trip flag and reset PEAK reference so
	// trading resumes from the next bar. Called by BacktestServiceImplV2
	// halt guards after close-all-on-open + markToMarket. asOfDate is today's
	// trading date — used to look up today's logged equity and rebase
	// maxEquity. DAILY anchor needs no reference reset (previousEquityValue
	// rolls naturally per markToMarket).
	public void clearPortfolioStoplossTrip(LocalDate asOfDate);

	// Patch 10: seed portfolio state from existing live positions for the
	// execution-mode endpoint. Call AFTER setBasicDeatils() and BEFORE the
	// day loop. Never called from runbacktestv3 — backtest mode keeps starting
	// from zero-state as before.
	public void seedLiveHoldings(java.util.List<LiveHoldingsSeedDto> seedHoldings);

	// Patch 11/12: getter for the captured tomorrow-orders list. Set by
	// runBacktestSimpleV2 at the end of the last bar's signal builder.
	public java.util.List<com.backtest.engine.entity.LimitOrder> getLastBarUnfilledOrders();

	// Patch 12: overwrite the captured-orders field. Called by
	// runBacktestSimpleV2 in execution mode on the last bar.
	public void recordProposedOrders(java.util.List<com.backtest.engine.entity.LimitOrder> orders);
	
	// Patch 77: trades closed today by checkMaxTime — for sector cap accounting
	List<TradeLog> getTodaysMaxTimeExits(LocalDate date);

}