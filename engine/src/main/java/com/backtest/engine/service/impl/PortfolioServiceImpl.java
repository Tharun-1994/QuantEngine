package com.backtest.engine.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.commons.math3.util.Precision;
import org.springframework.stereotype.Service;

import com.backtest.engine.dto.request.EntrySignalsRequestDto;
import com.backtest.engine.dto.request.ExitSignalsRequestDto;
import com.backtest.engine.dto.request.TradeEnterRequestDto;
import com.backtest.engine.dto.request.TradeExitRequestDto;
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.EquityLog;
import com.backtest.engine.entity.LiveHoldingsTracker;
import com.backtest.engine.entity.PriceData;
import com.backtest.engine.entity.TradeLog;
import com.backtest.engine.service.PortfolioService;

@Service
public class PortfolioServiceImpl implements PortfolioService {

	private PriceData priceData;

	private Map<String, TradeLog> tradeLogger = new LinkedHashMap<>();
	private Map<LocalDate, EquityLog> equityLogger = new LinkedHashMap<>();
	private Map<String, List<LiveHoldingsTracker>> liveHoldingsLogger = new HashMap<>();



	private float maxEquity = Float.MIN_VALUE;
	private LocalDate maxEquityDate = null;

	private final AtomicLong tradeCounter = new AtomicLong();

	private float unusedCapital;

	private float startingCapital;
	private int maxSlots;
	
	
	@Override
	public Set<String> getLiveHoldingsLogger() {
		return liveHoldingsLogger.keySet().stream().map(key -> key.split("_")[0]).collect(Collectors.toSet());
	}
	

	@Override
	public void setPriceDate(PriceData priceData, float startingCapital, int maxSlots) {
		this.priceData = priceData;
		this.startingCapital = startingCapital;
		this.unusedCapital = startingCapital;
		this.maxSlots = maxSlots;

	}

	@Override
	public void executeEntrySignals(EntrySignalsRequestDto entrySignalsRequest) {

		if (entrySignalsRequest.getEntries().isEmpty() || entrySignalsRequest.getEntries() == null) {
			return;
		}
		LocalDate tradeDate = entrySignalsRequest.getTradeDate();
		LocalDate previousDate = entrySignalsRequest.getPreviousDate();
		List<LocalDate> allDates = priceData.getAll_dates();
		

		// if it's the last bar, skip
		if (tradeDate.equals(allDates.get(allDates.size() - 1))) {
			return;
		}

		for (String tick : entrySignalsRequest.getEntries()) {
			double yesterdayClosePrice = this.priceData.getDaily_closes().get(previousDate).get(tick);
			try {
				if (this.liveHoldingsLogger.size() >= this.maxSlots) {
					break;
				}
				
				
				TradeEnterRequestDto trade = new TradeEnterRequestDto();
				trade.setTradeDate(tradeDate);
				trade.setTicker(tick);
				trade.setReason(entrySignalsRequest.getReasonForEntry());
				trade.setDirection(entrySignalsRequest.getDirection());

				if (entrySignalsRequest.getEntryTime().equals("open")) {
					trade.setEntryTiming(entrySignalsRequest.getEntryTime());
					trade.setPriceUsed(entrySignalsRequest.getEntryTime());

					double openPrice = this.priceData.getDaily_opens().get(tradeDate).get(tick);

					trade.setEntryprice((float)openPrice);
				}

				int quantity = (int) Math.floor(entrySignalsRequest.getSlotCapital() / yesterdayClosePrice);

				if (quantity > entrySignalsRequest.getMaxQuantitites()) {
					trade.setQuantity(quantity);
					this.enterTrade(trade);
				}

				
			} catch (Exception e) {
				System.err.println(e);
			}

		}

	}

	@Override
	public void executeExitSignals(ExitSignalsRequestDto exitSignalsRequest) {

		if (exitSignalsRequest.getExits().isEmpty() || exitSignalsRequest.getExits() == null) {
			return;
		}

		LocalDate tradeDate = exitSignalsRequest.getTradeDate();
		List<LocalDate> allDates = priceData.getAll_dates();

		// find the index of the tradeDate

		// if it's the last bar, skip
		if (tradeDate.equals(allDates.get(allDates.size() - 1))) {
			return;
		}

		List<String> liveHoldingsKeyList = this.liveHoldingsLogger.keySet().stream().collect(Collectors.toList());

		for (String id : liveHoldingsKeyList) {

			String tick = id.split("_")[0];

			if (exitSignalsRequest.getExits().contains(tick)) {
				TradeExitRequestDto trade = new TradeExitRequestDto();
				trade.setTradeId(id);
				trade.setTradeDate(tradeDate);
				if (exitSignalsRequest.getExitTime().equals("open")) {
					System.err.println(tick);
					System.err.println(tradeDate);
					double openPrice = this.priceData.getDaily_opens().get(tradeDate).get(tick);
					trade.setExitPrice( openPrice);
					trade.setPriceUsed(exitSignalsRequest.getExitTime());
				}

				trade.setExitReason(exitSignalsRequest.getReasonForExit());

				this.exitTrade(trade);
			}

		}

	}
//	private static final Logger log = LoggerFactory.getLogger(PortfolioServiceImpl.class);

	@Override
	public void enterTrade(TradeEnterRequestDto tradeEnterRequest) {
		TradeLog tradeLog;
		if (tradeEnterRequest != null) {
			tradeLog = TradeLog.builder().entryDate(tradeEnterRequest.getTradeDate())
					.entryPrice(tradeEnterRequest.getEntryprice()).entryReason(tradeEnterRequest.getReason())
					.entryTiming(tradeEnterRequest.getEntryTiming())
					.entryValue((tradeEnterRequest.getQuantity() * tradeEnterRequest.getEntryprice()))
					.direction(tradeEnterRequest.getDirection()).symbol(tradeEnterRequest.getTicker())
					.quantity(tradeEnterRequest.getQuantity()).capital(tradeEnterRequest.getCapital()).build();

			String id = tradeEnterRequest.getTicker() + "_" + System.currentTimeMillis() + "_"
					+ tradeCounter.incrementAndGet();

			this.tradeLogger.put(id, tradeLog);
			List<LiveHoldingsTracker> liveHoldings = new ArrayList<>();
			this.liveHoldingsLogger.put(id, liveHoldings);

			this.unusedCapital -= Math.round(tradeEnterRequest.getEntryprice() * tradeEnterRequest.getQuantity());

		}

	}

	@Override
	public void exitTrade(TradeExitRequestDto tradeExitRequest) {
		String tradeId = tradeExitRequest.getTradeId();
		if (tradeId != null && this.tradeLogger.containsKey(tradeId)) {
			TradeLog tradeLog = this.tradeLogger.get(tradeId);

			if (tradeLog == null) {
//				log.warn("Trade not found for ID: {}", tradeId);
				return;
			}

			tradeLog.setExitDate(tradeExitRequest.getTradeDate());
			tradeLog.setExitPrice(tradeExitRequest.getExitPrice());
			tradeLog.setExitValue(Math.round(tradeExitRequest.getExitPrice() * tradeLog.getQuantity()));
			tradeLog.setExitReason(tradeExitRequest.getExitReason());
			tradeLog.setExitTiming(tradeExitRequest.getPriceUsed());
			tradeLog.setProfit(tradeLog.getExitValue() - tradeLog.getEntryValue());
			tradeLog.setProfitPercentage(tradeLog.getProfit() / tradeLog.getEntryValue());

			tradeLog.setValueTracker(new ConcurrentHashMap<>());
			tradeLog.getValueTracker().put(tradeId, this.liveHoldingsLogger.get(tradeId));

			this.liveHoldingsLogger.remove(tradeId);

			this.unusedCapital += tradeLog.getExitValue();
		}

	}

	@Override
	public void markToMarket(LocalDate tradeDate) {
		float todayEquity = this.unusedCapital;
		if (!this.liveHoldingsLogger.isEmpty()) {

			for (String tradeId : this.liveHoldingsLogger.keySet()) {
				List<LiveHoldingsTracker> eachTradeList = this.liveHoldingsLogger.get(tradeId);
				TradeLog tradeRow = this.tradeLogger.get(tradeId);
				String symbol = tradeRow.getSymbol();
				int amount = tradeRow.getQuantity();
				Double closePrice = this.priceData.getDaily_closes().get(tradeDate).get(symbol);

				todayEquity += amount * closePrice;

				eachTradeList.add(LiveHoldingsTracker.builder().symbol(symbol).endOfDayValue((float)(amount * closePrice))
						.tradeDate(tradeDate).build());

			}
			if (todayEquity > this.maxEquity) {
				this.maxEquity = todayEquity;
				this.maxEquityDate = tradeDate;
			}
			EquityLog eqLog = new EquityLog();
			eqLog.setDailyDrawdown(this.maxEquity - todayEquity);
			eqLog.setEquityValue(todayEquity);
			eqLog.setDayEndUtility(this.liveHoldingsLogger.size());
			eqLog.setDayEndUtilityValue(this.liveHoldingsLogger.size() * (this.startingCapital / this.maxSlots));

			this.equityLogger.put(tradeDate, eqLog);
		}

	}

	@Override
	public void endOfBacktest(LocalDate tradeDate) {

		Map<String, Double> closePriceSeries = this.priceData.getDaily_closes().get(tradeDate);
		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());

		for (String tradeId : liveTradeIds) {
			
			String symbol = tradeId.split("_")[0];
			
			Double todayClosePrice = closePriceSeries.get(symbol);
			TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
			tradeExitRequest.setExitPrice(todayClosePrice);
			tradeExitRequest.setTradeDate(tradeDate);
			tradeExitRequest.setTradeId(tradeId);
			tradeExitRequest.setExitReason("End Of Backtest");
			tradeExitRequest.setPriceUsed("close");

			this.exitTrade(tradeExitRequest);
		}
		EquityLog eqLog = new EquityLog();
		eqLog.setDailyDrawdown(this.maxEquity - this.unusedCapital);
		eqLog.setEquityValue(this.unusedCapital);
		eqLog.setDayEndUtility(this.liveHoldingsLogger.size());
		eqLog.setDayEndUtilityValue(this.liveHoldingsLogger.size() * (this.startingCapital / this.maxSlots));

		this.equityLogger.put(tradeDate, eqLog);

	}

	@Override
	public BacktestReponseDto getPortfolio() {

		return BacktestReponseDto.builder().equityLogger(this.equityLogger).tradeLogger(this.tradeLogger).build();
	}

	@Override
	public void checkLivePositionsOnTommorow(LocalDate date) {
		int idx = Collections.binarySearch(this.priceData.getTrading_dates(), date);


		if (idx >= 0 && idx + 1 < priceData.getTrading_dates().size()) {
			LocalDate nextDate = priceData.getTrading_dates().get(idx + 1);
			Set<String> nextDayUniverse = priceData.getDaily_universes().get(nextDate);
			
			Map<String, Double> tomorow_closes = priceData.getDaily_closes().get(nextDate);

			List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());

			for (String tradeId : liveTradeIds) {
				String symbol = tradeId.split("_")[0];
				
				

				if (tomorow_closes.get(symbol) == null) {

					TradeExitRequestDto trade = new TradeExitRequestDto();
					trade.setTradeId(tradeId);
					trade.setTradeDate(date);

					double closePrice = this.priceData.getDaily_closes().get(date).get(symbol);
					trade.setExitPrice( closePrice);
					trade.setPriceUsed("close");

					trade.setExitReason("No Price tommorow");

					this.exitTrade(trade);
				}

			}

		}
	}
	



}
