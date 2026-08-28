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

import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Service;
import org.springframework.web.context.WebApplicationContext;

import com.backtest.engine.config.StaticConfig;
import com.backtest.engine.dto.request.EntrySignalsRequestDto;
import com.backtest.engine.dto.request.ExitSignalsRequestDto;
import com.backtest.engine.dto.request.LimitEntrySignalDto;
import com.backtest.engine.dto.request.LiveHoldingsSeedDto;
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
public class PortfolioServiceImplV2 implements PortfolioServiceV2 {

	private PriceDataV2 priceData;

	private Map<String, TradeLog> tradeLogger;
	private Map<LocalDate, EquityLog> equityLogger;
	private Map<String, List<LiveHoldingsTracker>> liveHoldingsLogger;

	private float maxEquity;

	private final AtomicLong tradeCounter = new AtomicLong();

	private float unusedCapital;

	private float startingCapital;
	private int maxSlots;

	// Patch 11: collects limit orders rejected by the skip-last-bar guard in
	// executeLimitOrdersLong / executeLimitOrdersShort. Read by the execution-
	// mode endpoint as PROPOSED orders for D+1. Empty for backtest runs (the
	// last bar is end of historical data; no one reads this).
	// @Scope(SCOPE_REQUEST) → field re-initialised per request automatically.
	private java.util.List<LimitOrder> lastBarUnfilledOrders = new ArrayList<>();

	private LocalDate maxEquityDate;

	private float stoplossPct;
	private float takeProfitPct;

	// Patch 64: DOLLAR_BASED needs the absolute $ value separately from pct.
	// Set by setStoplossDollar; consumed by checkStoplossHitDollar.
	private float stoplossDollar;

	// Patch 64: PORTFOLIO halt state. Set by checkPortfolioStoplossHit when
	// drawdown threshold breached; read by the day-loop guard at the top of
	// each iteration to skip all further processing. Reset only by a fresh
	// setPriceDate (i.e. fresh backtest run).
	// Patch 64: PORTFOLIO halt state. Set by checkPortfolioStoplossHit when
	// drawdown threshold breached; read by the day-loop guard at the top of
	// each iteration to skip all further processing. Reset only by a fresh
	// setPriceDate (i.e. fresh backtest run).
	private boolean portfolioStoplossTripped;
	private String portfolioStoplossReason;

	// Patch 72o.1: drawdown anchor mode for PORTFOLIO stoploss.
	// "PEAK" — drawdown vs all-time peak equity (default).
	// "DAILY" — single-day drop vs previous trading day's close.
	// Null/empty treated as "PEAK" in checkPortfolioStoplossHit.
	private String portfolioStoplossAnchor;

	// Patch 72o.1: O(1) tracking of yesterday's logged equity for the DAILY
	// anchor branch. lastEquityValue is today's logged equity after the most
	// recent markToMarket; previousEquityValue is the one logged before that.
	// markToMarket rolls these forward before storing today's EquityLog so
	// checkPortfolioStoplossHit (which runs after markToMarket) reads the
	// correct "yesterday" value. previousEquityValue stays 0 on day 1 — the
	// DAILY check short-circuits when there is no prior day.
	private float previousEquityValue;
	private float lastEquityValue;

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
		if (tradeDate.getYear() == 2000 && tradeDate.getMonthValue() == 1) {
			System.out.println("[exec] tradeDate=" + tradeDate + " " + tradeDate.getDayOfWeek() + " nEntries="
					+ entrySignalsRequest.getEntries().size());
		}
		LocalDate previousDate = entrySignalsRequest.getPreviousDate();
		List<LocalDate> allDates = priceData.getAll_dates();

		// if it's the last bar, skip
//		if (tradeDate.equals(allDates.get(allDates.size() - 1))) {
//			return;
//		}

		for (String tick : entrySignalsRequest.getEntries()) {
			Float yesterdayCloseBox = this.priceData.getDaily_closes().getValue(previousDate, tick);
			if (yesterdayCloseBox == null || yesterdayCloseBox.isNaN() || yesterdayCloseBox.isInfinite()) {
				// No close price available for (ticker, previousDate) — skip this entry
				continue;
			}
			double yesterdayClosePrice = yesterdayCloseBox;
			try {
				if (this.liveHoldingsLogger.size() >= this.maxSlots) {
					break;
				}

				TradeEnterRequestDto trade = new TradeEnterRequestDto();
				trade.setTradeDate(tradeDate);
				trade.setTicker(tick);
				trade.setReason(entrySignalsRequest.getReasonForEntry());
				trade.setDirection(entrySignalsRequest.getDirection());

				// Track the fill price for quantity calculation. For open-timing entries
				// we size based on yesterday's close (you don't know the open until it
				// prints). For close-timing entries we know today's close at the moment
				// the order executes, so we size using today's close — matches Python's
				// order_value(amount) behaviour where shares = amount / fill_price.
				double fillPrice = 0.0;
				double sizingPrice = yesterdayClosePrice;

				if (entrySignalsRequest.getEntryTime().equals("open")) {
					trade.setEntryTiming(entrySignalsRequest.getEntryTime());
					trade.setPriceUsed(entrySignalsRequest.getEntryTime());

					double openPrice = this.priceData.getDaily_opens().getValue(tradeDate, tick);

					// Gap filter: skip if stock gaps beyond threshold
					if (entrySignalsRequest.getGapFilterPct() > 0) {
						float gapPct = (float) ((openPrice - yesterdayClosePrice) / yesterdayClosePrice * 100);
						if (Math.abs(gapPct) > entrySignalsRequest.getGapFilterPct()) {
							continue;
						}
					}

					trade.setEntryprice((float) openPrice);
					fillPrice = openPrice;
					// sizingPrice stays at yesterdayClosePrice for open entries
				} else if (entrySignalsRequest.getEntryTime().equals("close")) {
					// Bug 4 fix: handle close-timing entries.
					trade.setEntryTiming(entrySignalsRequest.getEntryTime());
					trade.setPriceUsed(entrySignalsRequest.getEntryTime());

					Float closeBox = this.priceData.getDaily_closes().getValue(tradeDate, tick);
					if (closeBox == null || closeBox.isNaN() || closeBox.isInfinite() || closeBox <= 0f) {
						// Today's close unavailable — skip this entry rather than recording
						// a zero-priced trade that produces Infinity P&L downstream.
						continue;
					}
					double closePrice = closeBox;
					trade.setEntryprice((float) closePrice);
					fillPrice = closePrice;
					sizingPrice = closePrice; // size at the fill price for close entries
				}

				int quantity = (int) Math.floor(entrySignalsRequest.getSlotCapital() / sizingPrice);

				if (quantity > entrySignalsRequest.getMaxQuantitites()
						&& sizingPrice > entrySignalsRequest.getMinStockPricePerSlot()) {
					trade.setQuantity(quantity);
					this.enterTrade(trade);
				}

			} catch (Exception e) {
				System.err.println(e);
			}

		}

	}

	@Override
	public Map<String, Long> getLiveHoldingsTickerCounts() {
		return liveHoldingsLogger.keySet().stream().map(key -> key.split("_")[0])
				.collect(Collectors.groupingBy(t -> t, Collectors.counting()));
	}

	// Hold Blackout — scan the trade logger (which retains closed trades with
	// exitDate stamped) and return each symbol's most recent exit date.
	@Override
	public Map<String, LocalDate> getLastExitDateByTicker() {
		Map<String, LocalDate> lastExit = new HashMap<>();
		for (TradeLog t : this.tradeLogger.values()) {
			if (t == null)
				continue;
			LocalDate ex = t.getExitDate();
			String sym = t.getSymbol();
			if (ex == null || sym == null)
				continue;
			LocalDate cur = lastExit.get(sym);
			if (cur == null || ex.isAfter(cur)) {
				lastExit.put(sym, ex);
			}
		}
		return lastExit;
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
//		if (tradeDate.equals(allDates.get(allDates.size() - 1))) {
//			return;
//		}

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

	// Patch 77: return trades closed today by checkMaxTime, so the sector cap
	// in signalsForTheDayV1 can count their sectors as still occupied.
	// checkMaxTime removes them from liveHoldingsLogger before signalsForTheDayV1
	// runs; this restores their sector presence for the cap computation only.
	@Override
	public List<TradeLog> getTodaysMaxTimeExits(LocalDate date) {
		return tradeLogger.values().stream().filter(t -> date.equals(t.getExitDate()) && t.getExitReason() != null
				&& t.getExitReason().startsWith("MaxTime")).collect(java.util.stream.Collectors.toList());
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
					.quantity(tradeEnterRequest.getQuantity()).capital(tradeEnterRequest.getCapital())
					.pairId(tradeEnterRequest.getPairId()) // LRA Patch 20: null for non-pair trades
					.build();

			String id = tradeEnterRequest.getTicker() + "_" + System.currentTimeMillis() + "_"
					+ tradeCounter.incrementAndGet();

			this.tradeLogger.put(id, tradeLog);
			List<LiveHoldingsTracker> liveHoldings = new ArrayList<>();
			this.liveHoldingsLogger.put(id, liveHoldings);

			this.unusedCapital -= Math.round(tradeEnterRequest.getEntryprice() * tradeEnterRequest.getQuantity());

		}

	}

	// Patch 10: batch-seed liveHoldingsLogger + tradeLogger + unusedCapital
	// from an existing list of live positions. Used ONLY by the execution-mode
	// endpoint to hydrate state before the day loop, instead of replaying full
	// backtest history. Never called from runbacktestv3 — backtest mode is
	// unchanged.
	//
	// Mirrors enterTrade's bookkeeping (TradeLog + empty LiveHoldings list +
	// unusedCapital deduction) but with caller-supplied tradeIds so middleware
	// can round-trip them back to tradelist.id stably across nightly runs.
	@Override
	public void seedLiveHoldings(List<LiveHoldingsSeedDto> seedHoldings) {
		if (seedHoldings == null || seedHoldings.isEmpty()) {
			return;
		}
		for (LiveHoldingsSeedDto h : seedHoldings) {
			TradeLog tradeLog = TradeLog.builder().symbol(h.getSymbol()).direction(h.getDirection())
					.entryDate(h.getEntryDate()).entryPrice(h.getEntryprice()).entryReason(h.getEntryReason())
					.entryTiming(h.getEntryTiming()).entryValue(h.getQuantity() * h.getEntryprice())
					.quantity(h.getQuantity()).capital(h.getCapital()).pairId(h.getPairId()) // null for Phase 1
																								// single-direction
					.currentStopPrice(h.getCurrentStopPrice()) // D3 — null falls through to recompute
					.build();

			boolean isDigitId = NumberUtils.isDigits(h.getTradeId());
			String id = isDigitId ? "%s_%s".formatted(h.getSymbol(), h.getTradeId()) : h.getTradeId();

			this.tradeLogger.put(id, tradeLog);
			this.liveHoldingsLogger.put(id, new ArrayList<>());
			this.unusedCapital -= Math.round(h.getEntryprice() * h.getQuantity());
		}
	}

	// Patch 11: getter for the unfilled-orders accumulator.
	// Returns the orders the skip-last-bar guard rejected — the PROPOSED orders
	// for D+1 in execution mode.
	@Override
	public java.util.List<com.backtest.engine.entity.LimitOrder> getLastBarUnfilledOrders() {
		return this.lastBarUnfilledOrders;
	}

	// Patch 12: setter used by runBacktestSimpleV2 to overwrite the field
	// with the freshly-built last-bar limit orders. Defensive copy.
	@Override
	public void recordProposedOrders(java.util.List<com.backtest.engine.entity.LimitOrder> orders) {
		this.lastBarUnfilledOrders = orders;
	}

	// DualStopPct: override the per-position stop % for the current bar.
	@Override
	public void setStoplossPctForDay(float pct) {
		this.stoplossPct = pct;
	}

	// ─────────────────────────────────────────────────────────────────────
	// Patch 64: DOLLAR_BASED — per-position stop at entry − stoplossDollar.
	// Mirrors checkStoplossHit; the only change is the formula on lines
	// where pct was used. Long: stop = entry − dollar. Short: stop = entry +
	// dollar.
	// ─────────────────────────────────────────────────────────────────────
	@Override
	public void checkStoplossHitDollar(LocalDate date, String systemType, String timing) {
		if (systemType.equals(StaticConfig.systemType.get("long"))) {
			stoplossHitLongDollar(date, timing);
		} else if (systemType.equals(StaticConfig.systemType.get("short"))) {
			stoplossHitShortDollar(date, timing);
		}
	}

	private void stoplossHitLongDollar(LocalDate date, String timing) {
		if (this.liveHoldingsLogger.isEmpty())
			return;
		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			TradeLog tradeRow = this.tradeLogger.get(tradeId);
			String symbol = tradeRow.getSymbol();
			float entryPrice = tradeRow.getEntryPrice();
			float stopLossPrice = (tradeRow.getCurrentStopPrice() != null) ? tradeRow.getCurrentStopPrice()
					: (entryPrice - this.stoplossDollar);

			if ("eod".equalsIgnoreCase(timing)) {
				float closePrice = this.priceData.getDaily_closes().getValue(date, symbol);
				if (closePrice < stopLossPrice) {
					TradeExitRequestDto req = new TradeExitRequestDto();
					req.setExitPrice(closePrice);
					req.setTradeDate(date);
					req.setTradeId(tradeId);
					req.setExitReason(String.format("StopLoss Hit (Dollar): %.2f", stopLossPrice));
					req.setPriceUsed("close");
					this.exitTrade(req);
				}
			} else if ("intraday".equalsIgnoreCase(timing)) {
				float low = this.priceData.getDaily_lows().getValue(date, symbol);
				float high = this.priceData.getDaily_highs().getValue(date, symbol);
				float open = this.priceData.getDaily_opens().getValue(date, symbol);
				if (open <= stopLossPrice) {
					TradeExitRequestDto req = new TradeExitRequestDto();
					req.setExitPrice(open);
					req.setTradeDate(date);
					req.setTradeId(tradeId);
					req.setExitReason(String.format("StopLoss Hit (Dollar): %.2f", stopLossPrice));
					req.setPriceUsed("open");
					this.exitTrade(req);
				} else if (low <= stopLossPrice && stopLossPrice <= high) {
					TradeExitRequestDto req = new TradeExitRequestDto();
					req.setExitPrice(stopLossPrice);
					req.setTradeDate(date);
					req.setTradeId(tradeId);
					req.setExitReason(String.format("StopLoss Hit (Dollar): %.2f", stopLossPrice));
					req.setPriceUsed("stoploss price");
					this.exitTrade(req);
				}
			}
		}
	}

	private void stoplossHitShortDollar(LocalDate date, String timing) {
		if (this.liveHoldingsLogger.isEmpty())
			return;
		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			TradeLog tradeRow = this.tradeLogger.get(tradeId);
			String symbol = tradeRow.getSymbol();
			float entryPrice = tradeRow.getEntryPrice();
			float stopLossPrice = (tradeRow.getCurrentStopPrice() != null) ? tradeRow.getCurrentStopPrice()
					: (entryPrice + this.stoplossDollar);

			if ("eod".equalsIgnoreCase(timing)) {
				float closePrice = this.priceData.getDaily_closes().getValue(date, symbol);
				if (closePrice >= stopLossPrice) {
					TradeExitRequestDto req = new TradeExitRequestDto();
					req.setExitPrice(closePrice);
					req.setTradeDate(date);
					req.setTradeId(tradeId);
					req.setExitReason(String.format("StopLoss Hit (Dollar): %.2f", stopLossPrice));
					req.setPriceUsed("close");
					this.exitTrade(req);
				}
			} else if ("intraday".equalsIgnoreCase(timing)) {
				float low = this.priceData.getDaily_lows().getValue(date, symbol);
				float high = this.priceData.getDaily_highs().getValue(date, symbol);
				float open = this.priceData.getDaily_opens().getValue(date, symbol);
				if (open >= stopLossPrice) {
					TradeExitRequestDto req = new TradeExitRequestDto();
					req.setExitPrice(open);
					req.setTradeDate(date);
					req.setTradeId(tradeId);
					req.setExitReason(String.format("StopLoss Hit (Dollar): %.2f", stopLossPrice));
					req.setPriceUsed("open");
					this.exitTrade(req);
				} else if (low <= stopLossPrice && stopLossPrice <= high) {
					TradeExitRequestDto req = new TradeExitRequestDto();
					req.setExitPrice(stopLossPrice);
					req.setTradeDate(date);
					req.setTradeId(tradeId);
					req.setExitReason(String.format("StopLoss Hit (Dollar): %.2f", stopLossPrice));
					req.setPriceUsed("stoploss price");
					this.exitTrade(req);
				}
			}
		}
	}

	// ─────────────────────────────────────────────────────────────────────
	// Patch 64: PORTFOLIO — portfolio-level kill switch.
	// Detection on close T using todayEquity from markToMarket(date).
	// Action (close-all) happens on T+1's open via the day-loop's top-of-iter
	// guard reading isPortfolioStoplossTripped(). See BacktestServiceImplV2
	// patches (65).
	// ─────────────────────────────────────────────────────────────────────
	// ─────────────────────────────────────────────────────────────────────
	// Patch 72o.4: anchor-aware drawdown check.
	// PEAK — drawdown vs maxEquity (all-time peak); kill-switch semantics.
	// DAILY — drawdown vs previousEquityValue (yesterday's close);
	// circuit-breaker semantics for single-day drops.
	// Anchor source: setPortfolioStoplossAnchor called by BacktestServiceImplV2
	// after every setPriceDate/setBasicDeatils. Null/empty defaults to PEAK.
	// Always called AFTER markToMarket for the day, so equityLogger,
	// maxEquity, and previousEquityValue all reflect today's state.
	// ─────────────────────────────────────────────────────────────────────
	@Override
	public boolean checkPortfolioStoplossHit(LocalDate date) {
		if (this.portfolioStoplossTripped)
			return true; // already tripped on a prior day
		EquityLog eq = this.equityLogger.get(date);
		if (eq == null)
			return false; // markToMarket not yet run for this date
		float todayEquity = eq.getEquityValue();

		String anchor = (this.portfolioStoplossAnchor == null || this.portfolioStoplossAnchor.isEmpty()) ? "PEAK"
				: this.portfolioStoplossAnchor.toUpperCase();

		float reference;
		String referenceLabel;
		if ("DAILY".equals(anchor)) {
			if (this.previousEquityValue <= 0f)
				return false; // no prior day yet
			reference = this.previousEquityValue;
			referenceLabel = "previous close";
		} else {
			// PEAK (default). maxEquity is Float.MIN_VALUE before first
			// markToMarket; guard so the formula doesn't blow up.
			if (this.maxEquity <= 0f || this.maxEquity == Float.MIN_VALUE)
				return false;
			reference = this.maxEquity;
			referenceLabel = String.format("peak on %s",
					this.maxEquityDate != null ? this.maxEquityDate.toString() : "n/a");
		}

		float pctDown = ((reference - todayEquity) / reference) * 100f;
		if (pctDown >= this.stoplossPct) {
			this.portfolioStoplossTripped = true;
			this.portfolioStoplossReason = String.format(
					"Portfolio Stoploss Hit: %.2f%% drop from %s (%.2f → %.2f, threshold %.2f%%, anchor=%s) on %s",
					pctDown, referenceLabel, reference, todayEquity, this.stoplossPct, anchor, date.toString());
			return true;
		}
		return false;
	}

	@Override
	public boolean isPortfolioStoplossTripped() {
		return this.portfolioStoplossTripped;
	}

	@Override
	public String getPortfolioStoplossReason() {
		return this.portfolioStoplossReason;
	}

	@Override
	public void setStoplossDollar(float stoplossDollar) {
		this.stoplossDollar = stoplossDollar;
	}

	@Override
	public void setPortfolioStoplossAnchor(String anchor) {
		// Patch 72o.2: store the anchor mode. Null/empty kept as-is — the
		// check method treats those as "PEAK" at evaluation time.
		this.portfolioStoplossAnchor = anchor;
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

			// AFTER
			if ("SHORT".equals(tradeLog.getDirection())) {
				this.unusedCapital += (2 * tradeLog.getEntryValue() - tradeLog.getExitValue());
			} else {
				this.unusedCapital += tradeLog.getExitValue();
			}
		}

	}

	public void markToMarket(LocalDate tradeDate) {
		float todayEquity = this.unusedCapital;
		if (!this.liveHoldingsLogger.isEmpty()) {

			for (String tradeId : this.liveHoldingsLogger.keySet()) {
				List<LiveHoldingsTracker> eachTradeList = this.liveHoldingsLogger.get(tradeId);
				TradeLog tradeRow = this.tradeLogger.get(tradeId);
				String symbol = tradeRow.getSymbol();
				int amount = tradeRow.getQuantity();
				Float closePrice = this.priceData.getDaily_closes().getValue(tradeDate, symbol);

				float positionValue;
				if ("SHORT".equals(tradeRow.getDirection())) {
					float unrealizedPnL = (tradeRow.getEntryPrice() - closePrice) * amount;
					positionValue = (tradeRow.getEntryPrice() * amount) + unrealizedPnL;
				} else {
					positionValue = amount * closePrice;
				}
				todayEquity += positionValue;

				eachTradeList.add(LiveHoldingsTracker.builder().symbol(symbol).endOfDayValue(positionValue)
						.tradeDate(tradeDate).build());
			}
		}

		// ── Log an equity point on EVERY trading day, flat days included. ──
		// On a flat day todayEquity == unusedCapital (the loop above is skipped),
		// which is the correct portfolio value when no positions are held.
		if (todayEquity > this.maxEquity) {
			this.maxEquity = todayEquity;
			this.maxEquityDate = tradeDate;
		}

		// Patch 72o.3: roll the previous-day equity tracker BEFORE storing
		// today's entry. Defensive on re-markToMarket of the same date —
		// only roll on first insertion for a given trading date.
		if (!this.equityLogger.containsKey(tradeDate)) {
			this.previousEquityValue = this.lastEquityValue;
			this.lastEquityValue = todayEquity;
		}

		EquityLog eqLog = new EquityLog();
		eqLog.setDailyDrawdown(this.maxEquity - todayEquity);
		eqLog.setEquityValue(todayEquity);
		eqLog.setDayEndUtility(this.liveHoldingsLogger.size());
		eqLog.setDayEndUtilityValue(this.liveHoldingsLogger.size() * (this.startingCapital / this.maxSlots));

		this.equityLogger.put(tradeDate, eqLog);
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

			// D3: same override path as the long branch — see comment there.
			float stopLossPrice;
			if (tradeRow.getCurrentStopPrice() != null) {
				stopLossPrice = tradeRow.getCurrentStopPrice();
			} else {
				stopLossPrice = tickerEntryPrice * (1 + (this.stoplossPct / 100));
			}

			if (timing.toLowerCase().equals("eod")) {
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

			} else if (timing.toLowerCase().equals("intraday")) {
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

			float stopLossPrice;
			if (tradeRow.getCurrentStopPrice() != null) {
				stopLossPrice = tradeRow.getCurrentStopPrice();
			} else {
				stopLossPrice = tickerEntryPrice * (1 - (this.stoplossPct / 100));
			}

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
		if (systemType.equals(StaticConfig.systemType.get("long"))) {
			takeProfitHitLong(date, timing);
		} else if (systemType.equals(StaticConfig.systemType.get("short"))) {
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

	// ───────────────── prior-trading-day ATR (Python iloc[index_day-1])
	// ─────────────────
	// Python reads a_t_r[ticker].iloc[index_day-1], i.e. the ATR row immediately
	// BEFORE
	// the evaluation date in the (shared) daily index. We resolve that by stepping
	// back
	// one row in the ATR frame's own date index. Returns null if unavailable.
	private Float priorDayAtr(LocalDate date, String symbol) {
		com.backtest.engine.util.ArrowDataFrame atr = this.priceData.getDaily_atr();
		Integer idx = atr.getDateIndex(date);
		if (idx == null || idx <= 0) {
			return null;
		}
		// find the date one row earlier, then read it via getValue (handles null/NaN)
		LocalDate priorDate = null;
		for (LocalDate d : atr.getDates()) {
			Integer di = atr.getDateIndex(d);
			if (di != null && di == idx - 1) {
				priorDate = d;
				break;
			}
		}
		if (priorDate == null) {
			return null;
		}
		return atr.getValue(priorDate, symbol);
	}

	private static float round2(float v) {
		return Math.round(v * 100f) / 100f;
	}

	public void checkStoplossHitAtr(LocalDate date, String systemType, String timing) {
		if (systemType.equals(StaticConfig.systemType.get("long"))) {
			stoplossHitLongAtr(date, timing);
		} else if (systemType.equals(StaticConfig.systemType.get("short"))) {
			stoplossHitShortAtr(date, timing);
		}
	}

	// LONG stop — matches Python check_stoploss_intraday_rptt
	// stop = round( entry - round(stoploss_pct * ATR[prev_day], 2), 2 )
	// entry-day: if entered AT the open (open==entry_price), suppress the open-gap
	// exit
	private void stoplossHitLongAtr(LocalDate date, String timing) {
		if (this.liveHoldingsLogger.isEmpty()) {
			return;
		}
		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			TradeLog tradeRow = this.tradeLogger.get(tradeId);
			String symbol = tradeRow.getSymbol();
			float tickerEntryPrice = tradeRow.getEntryPrice();

			Float atrValue = priorDayAtr(date, symbol);
			float stopLossPrice;
			if (tradeRow.getCurrentStopPrice() != null) {
				stopLossPrice = tradeRow.getCurrentStopPrice();
			} else {
				if (atrValue == null) {
					continue;
				}
				stopLossPrice = round2(tickerEntryPrice - round2(this.stoplossPct * atrValue));
			}

			float lowPrice = this.priceData.getDaily_lows().getValue(date, symbol);
			float highPrice = this.priceData.getDaily_highs().getValue(date, symbol);
			float openPrice = this.priceData.getDaily_opens().getValue(date, symbol);

			// entry-day open-gap suppression: if we entered at the open, ignore the open
			// branch
			boolean openIgnore = true;
			if (date.equals(tradeRow.getEntryDate()) && round2(openPrice) == round2(tradeRow.getEntryPrice())) {
				openIgnore = false;
			}

			float exitPrice;
			String priceUsed;
			boolean hit = false;
			if (openPrice <= stopLossPrice && openIgnore) {
				exitPrice = openPrice;
				priceUsed = "open";
				hit = true;
			} else if (lowPrice <= stopLossPrice && stopLossPrice <= highPrice) {
				exitPrice = stopLossPrice;
				priceUsed = "stoploss price";
				hit = true;
			} else {
				exitPrice = 0f;
				priceUsed = "";
			}

			if (hit) {
				TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
				tradeExitRequest.setExitPrice(round2(exitPrice));
				tradeExitRequest.setTradeDate(date);
				tradeExitRequest.setTradeId(tradeId);
				tradeExitRequest.setExitReason(String.format("StopLoss Hit: %.2f", stopLossPrice));
				tradeExitRequest.setPriceUsed(priceUsed);
				this.exitTrade(tradeExitRequest);
			}
		}
	}

	// SHORT stop — mirror (stop above entry; open-gap up, or intraday touch)
	private void stoplossHitShortAtr(LocalDate date, String timing) {
		if (this.liveHoldingsLogger.isEmpty()) {
			return;
		}
		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			TradeLog tradeRow = this.tradeLogger.get(tradeId);
			String symbol = tradeRow.getSymbol();
			float tickerEntryPrice = tradeRow.getEntryPrice();

			// D3: same override semantics as the long ATR branch — see comment there.
			Float atrValue = priorDayAtr(date, symbol);
			float stopLossPrice;
			if (tradeRow.getCurrentStopPrice() != null) {
				stopLossPrice = tradeRow.getCurrentStopPrice();
			} else {
				if (atrValue == null) {
					continue;
				}
				stopLossPrice = round2(tickerEntryPrice + round2(this.stoplossPct * atrValue));
			}

			float lowPrice = this.priceData.getDaily_lows().getValue(date, symbol);
			float highPrice = this.priceData.getDaily_highs().getValue(date, symbol);
			float openPrice = this.priceData.getDaily_opens().getValue(date, symbol);

			boolean openIgnore = true;
			if (date.equals(tradeRow.getEntryDate()) && round2(openPrice) == round2(tradeRow.getEntryPrice())) {
				openIgnore = false;
			}

			float exitPrice;
			String priceUsed;
			boolean hit = false;
			if (openPrice >= stopLossPrice && openIgnore) {
				exitPrice = openPrice;
				priceUsed = "open";
				hit = true;
			} else if (lowPrice <= stopLossPrice && stopLossPrice <= highPrice) {
				exitPrice = stopLossPrice;
				priceUsed = "stoploss price";
				hit = true;
			} else {
				exitPrice = 0f;
				priceUsed = "";
			}

			if (hit) {
				TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
				tradeExitRequest.setExitPrice(round2(exitPrice));
				tradeExitRequest.setTradeDate(date);
				tradeExitRequest.setTradeId(tradeId);
				tradeExitRequest.setExitReason(String.format("StopLoss Hit: %.2f", stopLossPrice));
				tradeExitRequest.setPriceUsed(priceUsed);
				this.exitTrade(tradeExitRequest);
			}
		}
	}

	public void checkTakeProfitAtr(LocalDate date, String systemType, String timing) {
		if (systemType.equals(StaticConfig.systemType.get("long"))) {
			takeProfitHitLongAtr(date, timing);
		} else if (systemType.equals(StaticConfig.systemType.get("short"))) {
			takeProfitHitShortAtr(date, timing);
		}
	}

	// LONG take-profit — matches Python check_profit_intraday_rptt
	// tp = round( entry + round(profit_pct * stoploss_pct * ATR[prev_day], 2), 2 )
	// entry-day: close-based exit if close>=tp OR (high>=tp and close≈high, 1%
	// tol),
	// otherwise the open-gap/intraday branch only runs if open<=entry_price
	private void takeProfitHitLongAtr(LocalDate date, String timing) {
		if (this.liveHoldingsLogger.isEmpty()) {
			return;
		}
		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			TradeLog tradeRow = this.tradeLogger.get(tradeId);
			String symbol = tradeRow.getSymbol();
			float tickerEntryPrice = tradeRow.getEntryPrice();

			Float atrValue = priorDayAtr(date, symbol);
			if (atrValue == null) {
				continue;
			}

			float profitAmount = round2(this.takeProfitPct * this.stoplossPct * atrValue);
			float profitPrice = round2(tickerEntryPrice + profitAmount);

			float lowPrice = this.priceData.getDaily_lows().getValue(date, symbol);
			float highPrice = this.priceData.getDaily_highs().getValue(date, symbol);
			float openPrice = this.priceData.getDaily_opens().getValue(date, symbol);
			float closePrice = this.priceData.getDaily_closes().getValue(date, symbol);

			boolean allowEveryTrade = true;

			if (date.equals(tradeRow.getEntryDate())) {
				allowEveryTrade = (openPrice <= tradeRow.getEntryPrice());

				boolean closeIsClose = Math.abs(closePrice - highPrice) <= 0.01f
						* Math.max(Math.abs(closePrice), Math.abs(highPrice));
				if (closePrice >= profitPrice || (highPrice >= profitPrice && closeIsClose)) {
					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(closePrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("Profit Taking: %.2f ATR : %s Close price : %s",
							profitPrice, atrValue, closePrice));
					tradeExitRequest.setPriceUsed("close price");
					this.exitTrade(tradeExitRequest);
					allowEveryTrade = false;
				}
			}

			if (allowEveryTrade) {
				float exitPrice;
				String priceUsed;
				boolean hit = false;
				if (openPrice >= profitPrice) {
					exitPrice = openPrice;
					priceUsed = "open";
					hit = true;
				} else if (highPrice >= profitPrice && profitPrice >= lowPrice) {
					exitPrice = profitPrice;
					priceUsed = "takeprofit price";
					hit = true;
				} else {
					exitPrice = 0f;
					priceUsed = "";
				}
				if (hit) {
					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(exitPrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("Profit Taking: %.2f ATR : %s Close price : %s",
							profitPrice, atrValue, closePrice));
					tradeExitRequest.setPriceUsed(priceUsed);
					this.exitTrade(tradeExitRequest);
				}
			}
		}
	}

	// SHORT take-profit — mirror (tp below entry)
	private void takeProfitHitShortAtr(LocalDate date, String timing) {
		if (this.liveHoldingsLogger.isEmpty()) {
			return;
		}
		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			TradeLog tradeRow = this.tradeLogger.get(tradeId);
			String symbol = tradeRow.getSymbol();
			float tickerEntryPrice = tradeRow.getEntryPrice();

			Float atrValue = priorDayAtr(date, symbol);
			if (atrValue == null) {
				continue;
			}

			float profitAmount = round2(this.takeProfitPct * this.stoplossPct * atrValue);
			float profitPrice = round2(tickerEntryPrice - profitAmount);

			float lowPrice = this.priceData.getDaily_lows().getValue(date, symbol);
			float highPrice = this.priceData.getDaily_highs().getValue(date, symbol);
			float openPrice = this.priceData.getDaily_opens().getValue(date, symbol);
			float closePrice = this.priceData.getDaily_closes().getValue(date, symbol);

			boolean allowEveryTrade = true;

			if (date.equals(tradeRow.getEntryDate())) {
				allowEveryTrade = (openPrice >= tradeRow.getEntryPrice());

				boolean closeIsClose = Math.abs(closePrice - lowPrice) <= 0.01f
						* Math.max(Math.abs(closePrice), Math.abs(lowPrice));
				if (closePrice <= profitPrice || (lowPrice <= profitPrice && closeIsClose)) {
					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(closePrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("Profit Taking: %.2f ATR : %s Close price : %s",
							profitPrice, atrValue, closePrice));
					tradeExitRequest.setPriceUsed("close price");
					this.exitTrade(tradeExitRequest);
					allowEveryTrade = false;
				}
			}

			if (allowEveryTrade) {
				float exitPrice;
				String priceUsed;
				boolean hit = false;
				if (openPrice <= profitPrice) {
					exitPrice = openPrice;
					priceUsed = "open";
					hit = true;
				} else if (lowPrice <= profitPrice && profitPrice <= highPrice) {
					exitPrice = profitPrice;
					priceUsed = "takeprofit price";
					hit = true;
				} else {
					exitPrice = 0f;
					priceUsed = "";
				}
				if (hit) {
					TradeExitRequestDto tradeExitRequest = new TradeExitRequestDto();
					tradeExitRequest.setExitPrice(exitPrice);
					tradeExitRequest.setTradeDate(date);
					tradeExitRequest.setTradeId(tradeId);
					tradeExitRequest.setExitReason(String.format("Profit Taking: %.2f ATR : %s Close price : %s",
							profitPrice, atrValue, closePrice));
					tradeExitRequest.setPriceUsed(priceUsed);
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
//		if (tradeDate.equals(allDates.get(allDates.size() - 1))) {
//
//			return;
//		}

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

				// Gap filter: skip if stock gaps beyond threshold
				if (limitEntrySignalsRequest.getGapFilterPct() > 0) {
					float gapPct = ((openPrice - yesterdayClosePrice) / yesterdayClosePrice) * 100;
					if (Math.abs(gapPct) > limitEntrySignalsRequest.getGapFilterPct()) {
						continue;
					}
				}

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
		// Patch 64: reset PORTFOLIO state for a fresh backtest run.
		this.portfolioStoplossTripped = false;
		this.portfolioStoplossReason = null;
		this.stoplossDollar = 0f;
		// Patch 72o.5: reset anchor + previous-day equity trackers.
		// Anchor is re-set by setPortfolioStoplossAnchor immediately after
		// this call from BacktestServiceImplV2 (Patch 72p).
		this.portfolioStoplossAnchor = null;
		this.previousEquityValue = 0f;
		this.lastEquityValue = 0f;
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
//		if (tradeDate.equals(allDates.get(allDates.size() - 1))) {
//			return;
//		}

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
	public void checkMaxTime(LocalDate tradeDate, int maxTime, PriceDataV2 priceData, String exitTiming) {
		List<LocalDate> allDates = priceData.getAll_dates();
//		if (tradeDate.equals(allDates.get(allDates.size() - 1))) {
//			return;
//		}

		if (!this.liveHoldingsLogger.isEmpty()) {
			List<String> liveHoldingsKeyList = this.liveHoldingsLogger.keySet().stream().collect(Collectors.toList());

			for (String tradeId : liveHoldingsKeyList) {

				TradeLog tradeRow = this.tradeLogger.get(tradeId);

				if (tradeRow.getDayCount() >= maxTime) {
					String tick = tradeId.split("_")[0];
					TradeExitRequestDto trade = new TradeExitRequestDto();
					trade.setTradeId(tradeId);
					trade.setTradeDate(tradeDate);

					boolean atOpen = "open".equalsIgnoreCase(exitTiming);
					float exitPx = atOpen ? this.priceData.getDaily_opens().getValue(tradeDate, tick)
							: this.priceData.getDaily_closes().getValue(tradeDate, tick);
					trade.setExitPrice(exitPx);
					trade.setPriceUsed(atOpen ? "open" : "close");

					String reasonForExit = String.format("MaxTime %s", maxTime);
					trade.setExitReason(reasonForExit);

					this.exitTrade(trade);
				}

			}
		}

	}

	@Override
	public void executeLimitOrdersShort(LimitEntrySignalDto limitEntrySignalsRequest) {

		if (limitEntrySignalsRequest.getLimitOrders().isEmpty() || limitEntrySignalsRequest.getLimitOrders() == null) {
			return;
		}
		LocalDate tradeDate = limitEntrySignalsRequest.getTradeDate();
		LocalDate previousDate = limitEntrySignalsRequest.getPreviousDate();
		List<LocalDate> allDates = priceData.getAll_dates();

		// if it's the last bar, skip
//		if (tradeDate.equals(allDates.get(allDates.size() - 1))) {
//
//			return;
//		}

		for (LimitOrder limitOrder : limitEntrySignalsRequest.getLimitOrders()) {

			float yesterdayClosePrice = this.priceData.getDaily_closes().getValue(previousDate, limitOrder.getTicker());
			try {
				if (this.liveHoldingsLogger.size() >= this.maxSlots) {
					break;
				}

				float openPrice = this.priceData.getDaily_opens().getValue(tradeDate, limitOrder.getTicker());
				float highPrice = this.priceData.getDaily_highs().getValue(tradeDate, limitOrder.getTicker());

				openPrice = Math.round(openPrice * 100f) / 100f;
				highPrice = Math.round(highPrice * 100f) / 100f;

				// Gap filter: skip if stock gaps beyond threshold
				if (limitEntrySignalsRequest.getGapFilterPct() > 0) {
					float gapPct = ((openPrice - yesterdayClosePrice) / yesterdayClosePrice) * 100;
					if (Math.abs(gapPct) > limitEntrySignalsRequest.getGapFilterPct()) {
						continue;
					}
				}

				// SHORT: limit price is ABOVE yesterday close — stock must rally UP to fill
				// Gap-up case : open >= limitPrice → stock opened above limit, fill at open
				if (openPrice >= limitOrder.getLimitPrice()) {
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

					// Intraday case: high >= limitPrice → stock rallied up to limit during day,
					// fill at limit
				} else if (highPrice >= limitOrder.getLimitPrice()) {

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
	public void closeAllPositionsAtEodClose(LocalDate date) {
		if (this.liveHoldingsLogger.isEmpty())
			return;

		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			String symbol = tradeId.split("_")[0];
			float closePrice = this.priceData.getDaily_closes().getValue(date, symbol);
			TradeExitRequestDto trade = new TradeExitRequestDto();
			trade.setTradeId(tradeId);
			trade.setTradeDate(date);
			trade.setExitPrice(closePrice);
			trade.setPriceUsed("close");
			trade.setExitReason("EOD Close");
			this.exitTrade(trade);
		}
	}

	/**
	 * Close every open position at the given date's close price with a custom
	 * reason. Used by close-timing volatility-cut: detect on today's close, act at
	 * today's close (matches Python `trade_every_day_close` flow).
	 */
	public void closeAllPositionsAtClose(LocalDate date, String reasonOfExit) {
		if (this.liveHoldingsLogger.isEmpty())
			return;
		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			String symbol = tradeId.split("_")[0];
			float closePrice = this.priceData.getDaily_closes().getValue(date, symbol);
			TradeExitRequestDto trade = new TradeExitRequestDto();
			trade.setTradeId(tradeId);
			trade.setTradeDate(date);
			trade.setExitPrice(closePrice);
			trade.setPriceUsed("close");
			trade.setExitReason(reasonOfExit);
			this.exitTrade(trade);
		}
	}

	@Override
	public void clearPortfolioStoplossTrip(LocalDate asOfDate) {
		// Patch 73b: circuit-breaker resume. Clear the trip flag and reset
		// the PEAK reference so the strategy doesn't immediately re-trip on
		// the next bar from a now-stale pre-crash peak.
		//
		// PEAK uses maxEquity as the trip reference. After close-all-on-open
		// + markToMarket has logged today's equity, reset maxEquity to that
		// value so today becomes the "new peak baseline". From the next bar
		// forward, drawdown is measured against this fresh baseline.
		//
		// DAILY uses previousEquityValue which is rolled by markToMarket on
		// every fresh date — already correct for tomorrow's comparison.
		//
		// Trip reason is cleared too; downstream consumers can still recover
		// the trip event by grepping TradeLog exit reasons for "Portfolio
		// Stoploss Hit", which the close-all-on-open call stamps on every
		// closed position.
		this.portfolioStoplossTripped = false;
		this.portfolioStoplossReason = null;
		EquityLog eq = this.equityLogger.get(asOfDate);
		if (eq != null) {
			this.maxEquity = eq.getEquityValue();
			this.maxEquityDate = asOfDate;
		}
	}

	// Patch 191 (GIVEBACK TP): profit-armed give-back take-profit. Faithful port of
	// Python find_X_Y / take_profit(). Long-only. For each live position, replay
	// the
	// close path [entryDate .. previousDate] (strictly before today):
	// arm (X): first close >= (1 + gainArm) * entryPrice
	// fire (Y): first LATER close <= (1 - giveBack) * X -> exit
	// X is pinned at the FIRST arming close (not trailed), matching find_X_Y
	// exactly.
	// Fill is today's open. Decision uses only closes through previousDate (the
	// Python
	// .iloc[:-1]) -> no forward bias. Runs in the open phase BEFORE markToMarket.
	@Override
	public void checkTakeProfitGiveback(LocalDate date, LocalDate previousDate, String systemType, float gainArmPct,
			float givebackPct) {

		if (!StaticConfig.systemType.get("long").equals(systemType)) {
			return; // long-only, mirroring the reference (gain = price up)
		}
		if (this.liveHoldingsLogger.isEmpty()) {
			return;
		}
		float gainArm = gainArmPct / 100f; // X_gain_thresh
		float giveBack = givebackPct / 100f; // Y_lose_thresh
		if (gainArm <= 0f || giveBack <= 0f) {
			return;
		}

		com.backtest.engine.util.ArrowDataFrame closes = this.priceData.getDaily_closes();
		com.backtest.engine.util.ArrowDataFrame opens = this.priceData.getDaily_opens();

		List<String> liveTradeIds = new ArrayList<>(this.liveHoldingsLogger.keySet());
		for (String tradeId : liveTradeIds) {
			TradeLog tradeRow = this.tradeLogger.get(tradeId);
			String symbol = tradeRow.getSymbol();
			float entryPrice = tradeRow.getEntryPrice();
			LocalDate entryDate = tradeRow.getEntryDate();
			if (entryDate == null) {
				continue;
			}

			float armPrice = (1f + gainArm) * entryPrice;
			float x = -1f;
			float y = -1f;
			boolean foundX = false;

			// Patch 191 (perf): ordered close path over the row range [entryDate, today).
			// Parquet rows are chronological and dateIndexMap is O(1), so iterate the
			// ticker vector directly by row index instead of rebuilding + sorting a
			// full-history Map every position every day. Semantics unchanged: window is
			// [entryDate, today) exclusive (== daily_closes[open:today].iloc[:-1]); null/
			// NaN closes skipped; x = first close >= armPrice, y = first later close
			// <= (1 - giveBack) * x.
			org.apache.arrow.vector.Float4Vector vec = closes.getVector(symbol);
			Integer entryIdx = closes.getDateIndex(entryDate);
			Integer todayIdx = closes.getDateIndex(date);
			if (vec == null || entryIdx == null || todayIdx == null) {
				continue;
			}
			for (int row = entryIdx; row < todayIdx; row++) {
				if (row >= vec.getValueCount() || vec.isNull(row)) {
					continue;
				}
				float c = vec.get(row);
				if (Float.isNaN(c)) {
					continue;
				}
				if (!foundX && c >= armPrice) {
					x = c;
					foundX = true;
				}
				if (foundX && c <= (1f - giveBack) * x) {
					y = c;
					break;
				}
			}

			if (x > 0f && y > 0f) {
				Float openObj = opens.getValue(date, symbol);
				if (openObj == null || openObj.isNaN()) {
					continue; // no open to fill against today; leave position (loud-fail-safe)
				}
				TradeExitRequestDto req = new TradeExitRequestDto();
				req.setExitPrice(openObj);
				req.setTradeDate(date);
				req.setTradeId(tradeId);
				req.setExitReason(String.format("TakeProfit Give-Back: armed %.2f gaveback %.2f", x, y));
				req.setPriceUsed("open");
				this.exitTrade(req);
			}
		}
	}

	@Override
	public java.util.Set<String> getRecentlyClosedSymbols(int n) {
		if (n <= 0) {
			return java.util.Collections.emptySet();
		}
		// Closed trades in ENTRY order (tradeLogger is a LinkedHashMap); take the last
		// N and collect symbols into a set (== legacy
		// set(trade_df[~close_date.isna()].iloc[-n:]['symbol'])). A symbol traded more
		// than once inside that window counts once.
		java.util.List<TradeLog> closed = new java.util.ArrayList<>();
		for (TradeLog t : this.tradeLogger.values()) {
			if (t.getExitDate() != null) {
				closed.add(t);
			}
		}
		java.util.Set<String> banned = new java.util.HashSet<>();
		for (int i = Math.max(0, closed.size() - n); i < closed.size(); i++) {
			banned.add(closed.get(i).getSymbol());
		}
		return banned;
	}

	// Patch 209: like getLastExitDateByTicker, but ONLY stop-loss and take-profit
	// exits — mirrors the legacy Portfolio_quantity_correction blacklist, which is
	// set on a stop OR a (non-giveback) take-profit, not on RSI/signal exits or
	// VIX.
	@Override
	public Map<String, LocalDate> getStopTakeProfitExitDateByTicker() {
		Map<String, LocalDate> lastExit = new HashMap<>();
		for (TradeLog t : this.tradeLogger.values()) {
			if (t == null)
				continue;
			LocalDate ex = t.getExitDate();
			String sym = t.getSymbol();
			String reason = t.getExitReason();
			if (ex == null || sym == null || reason == null)
				continue;
			if (!(reason.startsWith("StopLoss Hit") || reason.startsWith("TakeProfit Hit")))
				continue;
			LocalDate cur = lastExit.get(sym);
			if (cur == null || ex.isAfter(cur)) {
				lastExit.put(sym, ex);
			}
		}
		return lastExit;
	}

}