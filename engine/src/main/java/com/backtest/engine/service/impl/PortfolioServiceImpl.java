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

import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Service;
import org.springframework.web.context.WebApplicationContext;

import com.backtest.engine.config.StaticConfig;
import com.backtest.engine.dto.request.EntrySignalsRequestDto;
import com.backtest.engine.dto.request.ExitSignalsRequestDto;
import com.backtest.engine.dto.request.LimitEntrySignalDto;
import com.backtest.engine.dto.request.TradeEnterRequestDto;
import com.backtest.engine.dto.request.TradeExitRequestDto;
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.EquityLog;
import com.backtest.engine.entity.LimitOrder;
import com.backtest.engine.entity.LiveHoldingsTracker;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.entity.TradeLog;
import com.backtest.engine.service.PortfolioServiceV2;

@Service
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class PortfolioServiceImpl implements PortfolioServiceV2 {

	private PriceDataV2 priceData;

	private Map<String, TradeLog> tradeLogger;
	private Map<LocalDate, EquityLog> equityLogger;
	private Map<String, List<LiveHoldingsTracker>> liveHoldingsLogger;

	private float maxEquity;

	private final AtomicLong tradeCounter = new AtomicLong();

	private float unusedCapital;

	private float startingCapital;
	private int maxSlots;

	private LocalDate maxEquityDate;

	private float stoplossPct;
	private float takeProfitPct;

	@Override
	public BacktestReponseDto getPortfolio() {

		return BacktestReponseDto.builder().equityLogger(this.equityLogger).tradeLogger(this.tradeLogger).build();
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
			double yesterdayClosePrice = this.priceData.getDaily_closes().getValue(previousDate, tick);
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

					double openPrice = this.priceData.getDaily_opens().getValue(tradeDate, tick);

					trade.setEntryprice((float) openPrice);
				}

				int quantity = (int) Math.floor(entrySignalsRequest.getSlotCapital() / yesterdayClosePrice);

				if (quantity > entrySignalsRequest.getMaxQuantitites()
						&& yesterdayClosePrice > entrySignalsRequest.getMinStockPricePerSlot()) {
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

					float openPrice = this.priceData.getDaily_opens().getValue(tradeDate, tick);
					trade.setExitPrice(openPrice);
					trade.setPriceUsed(exitSignalsRequest.getExitTime());
				} else if (exitSignalsRequest.getExitTime().equals("close")) {
					float closePrice = this.priceData.getDaily_closes().getValue(tradeDate, tick);
					trade.setExitPrice(closePrice);
					trade.setPriceUsed(exitSignalsRequest.getExitTime());
				}

				trade.setExitReason(exitSignalsRequest.getReasonForExit());

				this.exitTrade(trade);
			}

		}

	}

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
			if ("SHORT".equals(tradeLog.getDirection())) {
				tradeLog.setProfit(tradeLog.getEntryValue() - tradeLog.getExitValue());
			} else {
				tradeLog.setProfit(tradeLog.getExitValue() - tradeLog.getEntryValue());
			}
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
				Float closePrice = this.priceData.getDaily_closes().getValue(tradeDate, symbol);

//				tradeRow.setDayCount(tradeRow.getDayCount()+1);

				float positionValue;
				if ("SHORT".equals(tradeRow.getDirection())) {
					float unrealizedPnL = (tradeRow.getEntryPrice() - closePrice) * amount;
					positionValue = (tradeRow.getEntryPrice() * amount) + unrealizedPnL;
				} else {
					positionValue = amount * closePrice;
				}
				todayEquity += positionValue;

				eachTradeList.add(LiveHoldingsTracker.builder().symbol(symbol)
						.endOfDayValue(positionValue).tradeDate(tradeDate).build());

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
	public void updateTradeDayCount(LocalDate tradeDate) {
		if (!this.liveHoldingsLogger.isEmpty()) {
			for (String tradeId : this.liveHoldingsLogger.keySet()) {
				TradeLog tradeRow = this.tradeLogger.get(tradeId);
				tradeRow.setDayCount(tradeRow.getDayCount() + 1);
			}
		}
	}

	@Override
	public void endOfBacktest(LocalDate tradeDate) {

		Map<String, Float> closePriceSeries = this.priceData.getDaily_closes().getRow(tradeDate);
		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());

		for (String tradeId : liveTradeIds) {

			String symbol = tradeId.split("_")[0];

			Float todayClosePrice = closePriceSeries.get(symbol);
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
	public Set<String> getLiveHoldingsLogger() {
		return liveHoldingsLogger.keySet().stream().map(key -> key.split("_")[0]).collect(Collectors.toSet());
	}

	@Override
	public void checkLivePositionsOnTommorow(LocalDate date) {
		int idx = Collections.binarySearch(this.priceData.getTrading_dates(), date);

		if (idx >= 0 && idx + 1 < priceData.getTrading_dates().size()) {
			LocalDate nextDate = priceData.getTrading_dates().get(idx + 1);
			Set<String> nextDayUniverse = priceData.getDaily_universes().getRow(nextDate);

			Map<String, Float> tomorow_closes = priceData.getDaily_closes().getRow(nextDate);

			List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());

			for (String tradeId : liveTradeIds) {
				String symbol = tradeId.split("_")[0];

				if (tomorow_closes.get(symbol) == null) {

					TradeExitRequestDto trade = new TradeExitRequestDto();
					trade.setTradeId(tradeId);
					trade.setTradeDate(date);

					float closePrice = this.priceData.getDaily_closes().getValue(date, symbol);
					trade.setExitPrice(closePrice);
					trade.setPriceUsed("close");

					trade.setExitReason("No Price tommorow");

					this.exitTrade(trade);
				}

			}

		}
	}

	@Override
	public void checkStoplossHit(LocalDate date, String systemType, String timing) {

		if (systemType.equals(StaticConfig.systemType.get("long"))) {
			stoplossHitLong(date, timing);
		} else if (systemType.equals(StaticConfig.systemType.get("short"))) {
			stoplossHitShort(date, timing);
		}

	}

	private void stoplossHitShort(LocalDate date, String timing) {
		if (this.liveHoldingsLogger.isEmpty()) {
			return;
		}
		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			TradeLog tradeRow = this.tradeLogger.get(tradeId);
			String symbol = tradeRow.getSymbol();
			float tickerEntryPrice = tradeRow.getEntryPrice();

			float stopLossPrice = tickerEntryPrice * (1 + (this.stoplossPct / 100));

			if (timing.equals("eod")) {
				float closePrice = this.priceData.getDaily_closes().getValue(date, symbol);

				if (closePrice >= stopLossPrice) {
					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(closePrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("StopLoss Hit: %.2f", stopLossPrice));
					tradeExitRequest.setPriceUsed("close");
					this.exitTrade(tradeExitRequest);
				}

			} else if (timing.equals("intraday")) {
				float lowPrice = this.priceData.getDaily_lows().getValue(date, symbol);
				float highPrice = this.priceData.getDaily_highs().getValue(date, symbol);
				float openPrice = this.priceData.getDaily_opens().getValue(date, symbol);

				if (openPrice >= stopLossPrice) {

					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(openPrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("StopLoss Hit: %.2f", stopLossPrice));
					tradeExitRequest.setPriceUsed("open");
					this.exitTrade(tradeExitRequest);

				} else if (lowPrice <= stopLossPrice && stopLossPrice <= highPrice) {

					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(stopLossPrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("StopLoss Hit: %.2f", stopLossPrice));
					tradeExitRequest.setPriceUsed("stoploss price");
					this.exitTrade(tradeExitRequest);

				}

			}

		}

	}

	private void stoplossHitLong(LocalDate date, String timing) {

		if (this.liveHoldingsLogger.isEmpty()) {
			return;
		}

		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			TradeLog tradeRow = this.tradeLogger.get(tradeId);
			String symbol = tradeRow.getSymbol();
			float tickerEntryPrice = tradeRow.getEntryPrice();

			float stopLossPrice = tickerEntryPrice * (1 - (this.stoplossPct / 100));

			if (timing.toLowerCase().equals("EOD".toLowerCase())) {
				float closePrice = this.priceData.getDaily_closes().getValue(date, symbol);

				if (closePrice < stopLossPrice) {
					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(closePrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("StopLoss Hit: %.2f", stopLossPrice));
					tradeExitRequest.setPriceUsed("close");
					this.exitTrade(tradeExitRequest);
				}

			} else if (timing.toLowerCase().equals("intraday".toLowerCase())) {
				float lowPrice = this.priceData.getDaily_lows().getValue(date, symbol);
				float highPrice = this.priceData.getDaily_highs().getValue(date, symbol);
				float openPrice = this.priceData.getDaily_opens().getValue(date, symbol);

				if (openPrice <= stopLossPrice) {

					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(openPrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("StopLoss Hit: %.2f", stopLossPrice));
					tradeExitRequest.setPriceUsed("open");
					this.exitTrade(tradeExitRequest);

				} else if (lowPrice <= stopLossPrice && stopLossPrice <= highPrice) {

					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(stopLossPrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("StopLoss Hit: %.2f", stopLossPrice));
					tradeExitRequest.setPriceUsed("stoploss price");
					this.exitTrade(tradeExitRequest);

				}

			}

		}

	}

	@Override
	public void checkTakeProfit(LocalDate date, String systemType, String timing) {
		if (systemType.equals(StaticConfig.systemType.get("LONG"))) {
			takeProfitHitLong(date, timing);
		} else if (systemType.equals(StaticConfig.systemType.get("SHORT"))) {
			takeProfitHitShort(date, timing);
		}
	}

	private void takeProfitHitShort(LocalDate date, String timing) {
		if (this.liveHoldingsLogger.isEmpty()) {
			return;
		}
		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			TradeLog tradeRow = this.tradeLogger.get(tradeId);
			String symbol = tradeRow.getSymbol();
			float tickerEntryPrice = tradeRow.getEntryPrice();

			float takeProfitPrice = tickerEntryPrice * (1 - (this.stoplossPct / 100));

			if (timing.toLowerCase().equals("eod".toLowerCase())) {
				float closePrice = this.priceData.getDaily_closes().getValue(date, symbol);

				if (closePrice <= takeProfitPrice) {
					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(closePrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("TakeProfit Hit: %.2f", takeProfitPrice));
					tradeExitRequest.setPriceUsed("close");
					this.exitTrade(tradeExitRequest);
				}

			} else if (timing.toLowerCase().equals("intraday".toLowerCase())) {
				float lowPrice = this.priceData.getDaily_lows().getValue(date, symbol);
				float highPrice = this.priceData.getDaily_highs().getValue(date, symbol);
				float openPrice = this.priceData.getDaily_opens().getValue(date, symbol);

				if (openPrice <= takeProfitPrice) {

					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(openPrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("TakeProfit Hit: %.2f", takeProfitPrice));
					tradeExitRequest.setPriceUsed("open");
					this.exitTrade(tradeExitRequest);

				} else if (lowPrice <= takeProfitPrice) {
					// Low price intraday
					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(takeProfitPrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("TakeProfit Hit: %.2f", takeProfitPrice));
					tradeExitRequest.setPriceUsed("TakeProfit price");
					this.exitTrade(tradeExitRequest);

				}

			}

		}

	}

	private void takeProfitHitLong(LocalDate date, String timing) {
		if (this.liveHoldingsLogger.isEmpty()) {
			return;
		}
		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			TradeLog tradeRow = this.tradeLogger.get(tradeId);
			String symbol = tradeRow.getSymbol();
			float tickerEntryPrice = tradeRow.getEntryPrice();

			float takeProfitPrice = tickerEntryPrice * (1 + (this.takeProfitPct / 100));

			if (timing.toLowerCase().equals("eod".toLowerCase())) {
				float closePrice = this.priceData.getDaily_closes().getValue(date, symbol);

				if (closePrice >= takeProfitPrice) {
					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(closePrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("TakeProfit Hit: %.2f", takeProfitPrice));
					tradeExitRequest.setPriceUsed("close");
					this.exitTrade(tradeExitRequest);
				}

			} else if (timing.toLowerCase().equals("intraday".toLowerCase())) {

				float highPrice = this.priceData.getDaily_highs().getValue(date, symbol);
				float openPrice = this.priceData.getDaily_opens().getValue(date, symbol);

				if (openPrice >= takeProfitPrice) {

					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(openPrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("StopLoss Hit: %.2f", takeProfitPrice));
					tradeExitRequest.setPriceUsed("open");
					this.exitTrade(tradeExitRequest);

				} else if (highPrice >= takeProfitPrice) {
//					Intraday TakeProfit for Long

					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(takeProfitPrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("StopLoss Hit: %.2f", takeProfitPrice));
					tradeExitRequest.setPriceUsed("stoploss price");
					this.exitTrade(tradeExitRequest);

				}

			}

		}

	}

	@Override
	public void executeLimitOrdersLong(LimitEntrySignalDto limitEntrySignalsRequest) {

		if (limitEntrySignalsRequest.getLimitOrders().isEmpty() || limitEntrySignalsRequest.getLimitOrders() == null) {
			return;
		}
		LocalDate tradeDate = limitEntrySignalsRequest.getTradeDate();
		LocalDate previousDate = limitEntrySignalsRequest.getPreviousDate();
		List<LocalDate> allDates = priceData.getAll_dates();

		// if it's the last bar, skip
		if (tradeDate.equals(allDates.get(allDates.size() - 1))) {
			return;
		}

		for (LimitOrder limitOrder : limitEntrySignalsRequest.getLimitOrders()) {

			float yesterdayClosePrice = this.priceData.getDaily_closes().getValue(previousDate, limitOrder.getTicker());
			try {
				if (this.liveHoldingsLogger.size() >= this.maxSlots) {
					break;
				}

				float openPrice = this.priceData.getDaily_opens().getValue(tradeDate, limitOrder.getTicker());
				float lowPrice = this.priceData.getDaily_lows().getValue(tradeDate, limitOrder.getTicker());

				openPrice = Math.round(openPrice * 100f) / 100f;
				lowPrice = Math.round(lowPrice * 100f) / 100f;

				// Limit Order hit on OPEN
				if (openPrice <= limitOrder.getLimitPrice()) {
					TradeEnterRequestDto trade = new TradeEnterRequestDto();
					trade.setTradeDate(tradeDate);
					trade.setTicker(limitOrder.getTicker());
					trade.setReason(limitEntrySignalsRequest.getReasonForEntry());
					trade.setDirection(limitEntrySignalsRequest.getDirection());

					if (limitEntrySignalsRequest.getEntryTime().equals("open")) {
						trade.setEntryTiming(limitEntrySignalsRequest.getEntryTime());
						trade.setPriceUsed(limitEntrySignalsRequest.getEntryTime());

						trade.setEntryprice((float) openPrice);
					}

					int quantity = (int) Math.floor(limitEntrySignalsRequest.getSlotCapital() / yesterdayClosePrice);

					if (quantity > limitEntrySignalsRequest.getMaxQuantitites()
							&& yesterdayClosePrice > limitEntrySignalsRequest.getMinStockPricePerSlot()) {
						trade.setQuantity(quantity);

						this.enterTrade(trade);
					}
				} else if (lowPrice <= limitOrder.getLimitPrice()) {

					TradeEnterRequestDto trade = new TradeEnterRequestDto();
					trade.setTradeDate(tradeDate);
					trade.setTicker(limitOrder.getTicker());
					trade.setReason(limitEntrySignalsRequest.getReasonForEntry());
					trade.setDirection(limitEntrySignalsRequest.getDirection());

					if (limitEntrySignalsRequest.getEntryTime().equals("open")) {
						trade.setEntryTiming("intraday");
						trade.setPriceUsed("limit price");

						trade.setEntryprice((float) limitOrder.getLimitPrice());
					}

					int quantity = (int) Math.floor(limitEntrySignalsRequest.getSlotCapital() / yesterdayClosePrice);

					if (quantity > limitEntrySignalsRequest.getMaxQuantitites()
							&& yesterdayClosePrice > limitEntrySignalsRequest.getMinStockPricePerSlot()) {
						trade.setQuantity(quantity);

						this.enterTrade(trade);
					}

				}

			} catch (Exception e) {
				System.err.println(e);
			}

		}

	}

	@Override
	public void setPriceDate(PriceDataV2 priceData, float startingCapital, int maxSlots, float stoplossPct,
			float takeProfitPct) {
		this.priceData = priceData;
		this.startingCapital = startingCapital;
		this.unusedCapital = startingCapital;
		this.maxSlots = maxSlots;
		this.tradeLogger = new LinkedHashMap<>();
		this.equityLogger = new LinkedHashMap<>();
		this.liveHoldingsLogger = new HashMap<>();
		this.maxEquity = Float.MIN_VALUE;
		this.stoplossPct = stoplossPct;
		this.takeProfitPct = takeProfitPct;

	}

	@Override
	public void setBasicDeatils(PriceDataV2 priceData, float startingCapital, int maxSlots, float stoplossPct,
			float takeProfitPct) {
		this.startingCapital = startingCapital;
		this.maxSlots = maxSlots;
		this.stoplossPct = stoplossPct;
		this.takeProfitPct = takeProfitPct;
	}

	@Override
	public void closeAllPositionsOnOpenPrice(LocalDate tradeDate, PriceDataV2 priceData, String reasonOfExit) {

		List<LocalDate> allDates = priceData.getAll_dates();

		// find the index of the tradeDate

		// if it's the last bar, skip
		if (tradeDate.equals(allDates.get(allDates.size() - 1))) {
			return;
		}

		List<String> liveHoldingsKeyList = this.liveHoldingsLogger.keySet().stream().collect(Collectors.toList());

		for (String id : liveHoldingsKeyList) {

			String tick = id.split("_")[0];
			TradeExitRequestDto trade = new TradeExitRequestDto();
			trade.setTradeId(id);
			trade.setTradeDate(tradeDate);

			float openPrice = this.priceData.getDaily_opens().getValue(tradeDate, tick);
			trade.setExitPrice(openPrice);
			trade.setPriceUsed("open");

			trade.setExitReason(reasonOfExit);

			this.exitTrade(trade);

		}

	}

	@Override
	public void checkMaxTime(LocalDate tradeDate, int maxTime, PriceDataV2 priceData) {
		List<LocalDate> allDates = priceData.getAll_dates();
		if (tradeDate.equals(allDates.get(allDates.size() - 1))) {
			return;
		}

		if (!this.liveHoldingsLogger.isEmpty()) {
			List<String> liveHoldingsKeyList = this.liveHoldingsLogger.keySet().stream().collect(Collectors.toList());

			for (String tradeId : liveHoldingsKeyList) {

				TradeLog tradeRow = this.tradeLogger.get(tradeId);

				if (tradeRow.getDayCount() >= maxTime) {
					String tick = tradeId.split("_")[0];
					TradeExitRequestDto trade = new TradeExitRequestDto();
					trade.setTradeId(tradeId);
					trade.setTradeDate(tradeDate);

					float openPrice = this.priceData.getDaily_opens().getValue(tradeDate, tick);
					trade.setExitPrice(openPrice);
					trade.setPriceUsed("open");

					String reasonForExit = String.format("MaxTime %s", maxTime);
					trade.setExitReason(reasonForExit);

					this.exitTrade(trade);
				}

			}
		}

	}

}