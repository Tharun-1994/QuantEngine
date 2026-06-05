package com.backtest.engine.service.impl;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.backtest.engine.config.StaticConfig;
import com.backtest.engine.dto.request.EntrySignalsRequestDto;
import com.backtest.engine.dto.request.ExitSignalsRequestDto;
import com.backtest.engine.dto.request.LimitEntrySignalDto;
import com.backtest.engine.dto.request.TdomFilterDto;
import com.backtest.engine.dto.request.VolFilterDto;
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.LimitOrder;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.entity.StrategyDataV2;
import com.backtest.engine.service.BacktestServiceV2;
import com.backtest.engine.service.PortfolioServiceV2;
import com.backtest.engine.service.StrategyBuilderServiceV2;
import com.backtest.engine.service.safetynet.SafetyNetPolicy;
import com.backtest.engine.util.ArrowDataFrame;

@Service
public class BacktestServiceImplV2 implements BacktestServiceV2 {

	private final PortfolioServiceImpl portfolioServiceImpl;

	private final PortfolioServiceImplV2 portfolioServiceImplV2;

	@Autowired
	PortfolioServiceV2 portfolioService;

	StrategyBuilderServiceV2 strategyBuilderService;

	BacktestServiceImplV2(StrategyBuilderServiceImplV2 strategyBuilderServiceImpl,
			PortfolioServiceImplV2 portfolioServiceImplV2, PortfolioServiceImpl portfolioServiceImpl) {
		this.strategyBuilderService = strategyBuilderServiceImpl;
		this.portfolioServiceImplV2 = portfolioServiceImplV2;
		this.portfolioServiceImpl = portfolioServiceImpl;
	}

	/**
	 * Recalculates vol/turnover thresholds once per year. Trigger: first trading
	 * date of the backtest OR tdom==0 and month==January. Matches Python
	 * trade_open_1: if (d == trading_dates[0]) or (today_month==1 and
	 * today_tdom==0): if spy > spy_ma: bull pcts else bear pcts
	 *
	 * Uses YESTERDAY's avg_volume/avg_turnover across today's active universe,
	 * sorted ascending, threshold = value at floor(size * pct). After setting,
	 * every entry candidate is filtered per-day in signalsForTheDayV1.
	 */
	private void computeVolThresholds(LocalDate date, LocalDate previousDate, BuySellDataV2 buySellData,
			PriceDataV2 priceData, Map<LocalDate, Integer> tdomMap) {

		StrategyDataV2 sd = buySellData.getStrategyData();
		VolFilterDto vf = sd.getVolFilter();
		if (vf == null || !vf.isEnabled())
			return;
		if (sd.getAvgVolume() == null || sd.getAvgTurnover() == null || sd.getSpyCloses() == null)
			return;

		List<LocalDate> tradingDates = priceData.getTrading_dates();
		boolean isFirstDay = !tradingDates.isEmpty() && date.equals(tradingDates.get(0));
		int tdom = tdomMap.getOrDefault(date, -1);
		boolean isJanTdom0 = (tdom == 0 && date.getMonthValue() == 1);
		if (!isFirstDay && !isJanTdom0)
			return;

		// Need yesterday's date for avg_volume/avg_turnover lookup
		if (previousDate == null)
			return;

		// SPY bull/bear: use yesterday's SPY close vs SPY SMA(200)
		// Build SPY SMA(200) on-the-fly from the closes_spy ArrowDataFrame
		ArrowDataFrame spyDf = sd.getSpyCloses();
		// Derive SPY SMA(200) on-the-fly.
		// spyDf is a single-column frame (one ticker: "spy").
		// Use getTickers().iterator().next() — no getColumns() method exists.
		if (spyDf.getTickers().isEmpty())
			return;
		String spyCol = spyDf.getTickers().iterator().next();
		List<LocalDate> allDates = priceData.getAll_dates();
		int prevIdx = allDates.indexOf(previousDate);
		if (prevIdx < 0)
			return;
		int smaLookback = 200;
		int startIdx = Math.max(0, prevIdx - smaLookback + 1);
		double spySum = 0;
		int spyCount = 0;
		for (int k = startIdx; k <= prevIdx; k++) {
			LocalDate d = allDates.get(k);
			try {
				if (spyDf.hasValue(d, spyCol)) {
					Float v = spyDf.getValue(d, spyCol);
					if (v != null) {
						spySum += v;
						spyCount++;
					}
				}
			} catch (Exception ignored) {
			}
		}
		if (spyCount == 0)
			return;
		double spyMa200 = spySum / spyCount;
		Float spyClose = null;
		try {
			if (spyDf.hasValue(previousDate, spyCol))
				spyClose = spyDf.getValue(previousDate, spyCol);
		} catch (Exception ignored) {
		}
		if (spyClose == null)
			return;
		boolean bull = spyClose > spyMa200;

		// Active universe yesterday
		java.util.Set<String> universe = priceData.getDaily_universes().getRow(previousDate);
		if (universe == null || universe.isEmpty())
			return;

		// Compute vol threshold
		java.util.List<Float> vols = new java.util.ArrayList<>();
		for (String ticker : universe) {
			try {
				if (sd.getAvgVolume().hasValue(previousDate, ticker)) {
					Float v = sd.getAvgVolume().getValue(previousDate, ticker);
					if (v != null)
						vols.add(v);
				}
			} catch (Exception ignored) {
			}
		}
		if (!vols.isEmpty()) {
			java.util.Collections.sort(vols);
			float pct = bull ? vf.getVolPctBull() : vf.getVolPctBear();
			int idx = (int) Math.floor(vols.size() * pct);
			idx = Math.min(idx, vols.size() - 1);
			sd.setVolThreshold(vols.get(idx));
		}

		// Compute turnover threshold
		java.util.List<Float> turnovers = new java.util.ArrayList<>();
		for (String ticker : universe) {
			try {
				if (sd.getAvgTurnover().hasValue(previousDate, ticker)) {
					Float v = sd.getAvgTurnover().getValue(previousDate, ticker);
					if (v != null)
						turnovers.add(v);
				}
			} catch (Exception ignored) {
			}
		}
		if (!turnovers.isEmpty()) {
			java.util.Collections.sort(turnovers);
			float pct = bull ? vf.getTurnoverPctBull() : vf.getTurnoverPctBear();
			int idx = (int) Math.floor(turnovers.size() * pct);
			idx = Math.min(idx, turnovers.size() - 1);
			sd.setTurnoverThreshold(turnovers.get(idx));
		}
	}

	private Map<LocalDate, Integer> computeTdomMap(List<LocalDate> allDates) {
		// Matches Python: split=list(all_dates), filtered by year+month, .index(x)
		// Using all_dates ensures mid-month start dates get correct offset, not reset
		// to 0.
		Map<LocalDate, Integer> tdomMap = new HashMap<>();
		int tdom = 0;
		int prevYear = -1;
		int prevMonth = -1;
		for (LocalDate date : allDates) {
			if (date.getYear() != prevYear || date.getMonthValue() != prevMonth) {
				tdom = 0;
				prevYear = date.getYear();
				prevMonth = date.getMonthValue();
			} else {
				tdom++;
			}
			tdomMap.put(date, tdom);
		}
		return tdomMap;
	}

	private boolean isTdomBlocked(LocalDate date, int tdom, List<TdomFilterDto> tdomFilters) {
		// Matches Python: (tdom==N and month in banned_tdomN) or (weekday==4 and month
		// in banned_fridays)
		if (tdomFilters == null || tdomFilters.isEmpty())
			return false;
		int month = date.getMonthValue();
		int pythonWeekday = date.getDayOfWeek().getValue() - 1; // Java 1=Mon->0, 7=Sun->6; 4=Fri
		for (TdomFilterDto filter : tdomFilters) {
			List<Integer> bannedMonths = filter.getBannedMonths();
			if (bannedMonths == null || !bannedMonths.contains(month))
				continue;
			if (filter.getTdom() != null && filter.getTdom() == tdom)
				return true;
			if (filter.getWeekday() != null && filter.getWeekday() == pythonWeekday)
				return true;
		}
		return false;
	}

	private void processLimitOrdersLong(LocalDate date, LocalDate previousDate,

			Map<String, List<LimitOrder>> limitOrderMap, BuySellDataV2 buySellData) {
		if (limitOrderMap != null && limitOrderMap.get("limit_orders") != null
				&& !limitOrderMap.get("limit_orders").isEmpty()) {

			int maxQuantity = buySellData.getStrategyData().getMinQuantity() > 0
					? (int) buySellData.getStrategyData().getMinQuantity()
					: 1;

			float minStockPricePerSlot = buySellData.getStrategyData().getMinPrice() > 0
					? buySellData.getStrategyData().getMinPrice()
					: 0;

			LimitEntrySignalDto entrySignals = LimitEntrySignalDto.builder().tradeDate(date)
					.limitOrders(limitOrderMap.get("limit_orders"))
					.maxSingleStock(buySellData.getStrategyData().getMaxSameTicker()).reasonForEntry("Entries")
					.entryTime("open")
					.slotCapital((int) buySellData.getStrategyData().getStartingCapital()
							/ buySellData.getStrategyData().getSlots())
					.maxSlots(buySellData.getStrategyData().getSlots())
//	                 Max Quantities set to 5 default.
					.maxQuantitites(maxQuantity).direction(buySellData.getStrategyData().getSystemType())
					.minStockPricePerSlot(minStockPricePerSlot)
					.gapFilterPct(buySellData.getStrategyData().getGapFilterPct()).previousDate(previousDate).build();

			this.portfolioService.executeLimitOrdersLong(entrySignals);

		}
	}

	private void processLimitOrdersShort(LocalDate date, LocalDate previousDate,
			Map<String, List<LimitOrder>> limitOrderMap, BuySellDataV2 buySellData) {
		if (limitOrderMap != null && limitOrderMap.get("limit_orders") != null
				&& !limitOrderMap.get("limit_orders").isEmpty()) {

			int maxQuantity = buySellData.getStrategyData().getMinQuantity() > 0
					? (int) buySellData.getStrategyData().getMinQuantity()
					: 1;

			float minStockPricePerSlot = buySellData.getStrategyData().getMinPrice() > 0
					? buySellData.getStrategyData().getMinPrice()
					: 0;

			LimitEntrySignalDto entrySignals = LimitEntrySignalDto.builder().tradeDate(date)
					.limitOrders(limitOrderMap.get("limit_orders"))
					.maxSingleStock(buySellData.getStrategyData().getMaxSameTicker()).reasonForEntry("Entries")
					.entryTime("open")
					.slotCapital((int) buySellData.getStrategyData().getStartingCapital()
							/ buySellData.getStrategyData().getSlots())
					.maxSlots(buySellData.getStrategyData().getSlots()).maxQuantitites(maxQuantity)
					.direction(buySellData.getStrategyData().getSystemType()).minStockPricePerSlot(minStockPricePerSlot)
					.gapFilterPct(buySellData.getStrategyData().getGapFilterPct()).previousDate(previousDate).build();

			this.portfolioService.executeLimitOrdersShort(entrySignals);
		}
	}

	private void processNormalOrders(LocalDate date, LocalDate previousDate, Map<String, List<String>> entryExitMap,
			BuySellDataV2 buySellData) {

		if (entryExitMap != null && entryExitMap.get("entry") != null && !entryExitMap.get("entry").isEmpty()) {

			int maxQuantity = buySellData.getStrategyData().getMinQuantity() > 0
					? (int) buySellData.getStrategyData().getMinQuantity()
					: 1;

			float minStockPricePerSlot = buySellData.getStrategyData().getMinPrice() > 0
					? buySellData.getStrategyData().getMinPrice()
					: 0;

			EntrySignalsRequestDto entrySignals = EntrySignalsRequestDto.builder().tradeDate(date)
					.entries(entryExitMap.get("entry")).maxSingleStock(buySellData.getStrategyData().getMaxSameTicker())
					.reasonForEntry("Entries").entryTime("open")
					.slotCapital((int) (buySellData.getStrategyData().getStartingCapital()
							/ buySellData.getStrategyData().getSlots()))
					.maxSlots(buySellData.getStrategyData().getSlots())
//	                 Max Quantities set to 5 default.
					.maxQuantitites(maxQuantity).direction(buySellData.getStrategyData().getSystemType())
					.minStockPricePerSlot(minStockPricePerSlot)
					.gapFilterPct(buySellData.getStrategyData().getGapFilterPct()).previousDate(previousDate).build();

			this.portfolioService.executeEntrySignals(entrySignals);

		}

	}

	@Override
	public BacktestReponseDto runBacktestV2(PriceDataV2 priceData, BuySellDataV2 buySellData) {

		this.portfolioService.setPriceDate(priceData, buySellData.getStrategyData().getStartingCapital(),
				buySellData.getStrategyData().getSlots(), buySellData.getStrategyData().getStopLossPct(),
				buySellData.getStrategyData().getTakeProfitPct());

		Map<String, List<String>> entryExitMap = null;

		Map<String, List<LimitOrder>> limitOrderMap = null;

		LocalDate previousDate = null;

		// Volatility-cut (freeze/resume) suspension state — persists across days.
		// Decisions are made on YESTERDAY's close (previousDate) and executed at
		// TODAY's open, so there is no forward-looking bias.
		boolean suspended = false;
		// Stage 3b: safety-net policies replace the legacy freezeDays/resumeDays.
		java.util.List<com.backtest.engine.service.safetynet.SafetyNetPolicy> safetyPolicies = buySellData
				.getStrategyData().getSafetyPolicies();
		if (safetyPolicies == null)
			safetyPolicies = java.util.Collections.emptyList();

		Map<LocalDate, Integer> tdomMap = computeTdomMap(priceData.getAll_dates());

		for (LocalDate date : priceData.getAll_dates()) {

			if (date.equals(LocalDate.of(2000, 1, 3))) {
				System.err.println();
			}

			if (((date.isEqual(priceData.getTrading_dates().get(0))
					|| date.isAfter(priceData.getTrading_dates().get(0)))
					&& date.isBefore(buySellData.getStrategyData().getEndDate()))

					|| date.isEqual(buySellData.getStrategyData().getEndDate())) {

				// Max Time is Enabled
				if (buySellData.getStrategyData().getMaxTime() > 0) {
					this.portfolioService.checkMaxTime(date, buySellData.getStrategyData().getMaxTime(), priceData);
				}

				if (priceData.getTrading_dates().contains(date)) {

					// Volatility-cut (freeze/resume) — decided on YESTERDAY's close
					// (previousDate), executed at TODAY's open. No forward bias.
					// Freeze: close all positions at open + suspend new entries.
					// Resume: lift suspension (positions re-enter via normal signals).
					// Volatility-cut (freeze/resume) — OPEN-timing branch.
					// Decision on previousDate's close, action at today's open.
					// Stage 3b: dispatch through the policy list. Every active
					// policy gets a vote on freeze/resume at the open phase.
					suspended = dispatchSafetyNetsAtOpen(safetyPolicies, date, previousDate, suspended, priceData);

					// Exit Orders
					if (buySellData.getStrategyData().getExitTiming().equals("open")) {

						if (entryExitMap != null && entryExitMap.get("exit") != null
								&& !entryExitMap.get("exit").isEmpty()) {

							ExitSignalsRequestDto exitSignals = ExitSignalsRequestDto.builder().tradeDate(date)
									.exits(entryExitMap.get("exit")).reasonForExit("Exits").exitTime("open").build();

							this.portfolioService.executeExitSignals(exitSignals);
						}
					}

					// Entry Orders (blocked while volatility-cut suspension is active)

					if (!suspended && buySellData.getStrategyData().getEntryTiming().equals("open")) {
						if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("normal"))) {
							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("long"))
									|| buySellData.getStrategyData().getSystemType()
											.equals(StaticConfig.systemType.get("short"))) {
								processNormalOrders(date, previousDate, entryExitMap, buySellData);
							}

						} else if (buySellData.getStrategyData().getOrderType()
								.equals(StaticConfig.orderType.get("limit_atr"))
								|| buySellData.getStrategyData().getOrderType()
										.equals(StaticConfig.orderType.get("limit"))) {

							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("long"))
									|| buySellData.getStrategyData().getSystemType()
											.equals(StaticConfig.systemType.get("short"))) {
								int tdom = tdomMap.getOrDefault(date, -1);
								List<Integer> bannedMonths = buySellData.getStrategyData().getBannedMonths();
								boolean monthBanned = bannedMonths != null
										&& bannedMonths.contains(date.getMonthValue());

								if (!monthBanned
										&& !isTdomBlocked(date, tdom, buySellData.getStrategyData().getTdomFilters())) {
									if (buySellData.getStrategyData().getSystemType()
											.equals(StaticConfig.systemType.get("short"))) {
										processLimitOrdersShort(date, previousDate, limitOrderMap, buySellData);
									} else {
										processLimitOrdersLong(date, previousDate, limitOrderMap, buySellData);
									}
								}
							}

						}
					}
				}

				this.portfolioService.markToMarket(date);
				this.portfolioService.updateTradeDayCount(date);

				// StopLoss
				if (StaticConfig.stoplossType.get("nrml").equals(buySellData.getStrategyData().getStoplossType())
						&& buySellData.getStrategyData().getStopLossPct() > 0) {
					this.portfolioService.checkStoplossHit(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				} else if (StaticConfig.stoplossType.get("atr_based")
						.equals(buySellData.getStrategyData().getStoplossType())
						&& buySellData.getStrategyData().getStopLossPct() > 0) {
					this.portfolioService.checkStoplossHitAtr(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				}

				// Takeprofit
				if (StaticConfig.takeProfitType.get("nrml").equals(buySellData.getStrategyData().getTakeprofitType())
						&& buySellData.getStrategyData().getTakeProfitPct() > 0) {
					this.portfolioService.checkTakeProfit(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				} else if (StaticConfig.takeProfitType.get("atr_based")
						.equals(buySellData.getStrategyData().getTakeprofitType())
						&& buySellData.getStrategyData().getTakeProfitPct() > 0) {
					this.portfolioService.checkTakeProfitAtr(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getTakeprofitTiming());
				}

				// EOD Close
				if ("eod_close".equalsIgnoreCase(buySellData.getStrategyData().getExitTiming())) {
					this.portfolioService.closeAllPositionsAtEodClose(date);
				}

				this.portfolioService.checkLivePositionsOnTommorow(date);

				// Volatility-cut (freeze/resume) — CLOSE-timing branch.
				// Decision on today's close, action at today's close (matches
				// Python's trade_every_day_close ordering).
				// Stage 3b: dispatch through the policy list at the close phase.
				if (priceData.getTrading_dates().contains(date)) {
					suspended = dispatchSafetyNetsAtClose(safetyPolicies, date, suspended);
				}

				// Recalculate vol/turnover thresholds yearly (1st Jan trading day)
				computeVolThresholds(date, previousDate, buySellData, priceData, tdomMap);

				entryExitMap = strategyBuilderService.signalsForTheDayV1(date, priceData, buySellData,
						this.portfolioService);

				// If exit_timing is "close", execute exits immediately at today's close
				if (buySellData.getStrategyData().getExitTiming().equals("close")) {
					if (entryExitMap != null && entryExitMap.get("exit") != null
							&& !entryExitMap.get("exit").isEmpty()) {
						ExitSignalsRequestDto exitSignals = ExitSignalsRequestDto.builder().tradeDate(date)
								.exits(entryExitMap.get("exit")).reasonForExit("Exits").exitTime("close").build();
						this.portfolioService.executeExitSignals(exitSignals);
					}
				}

				if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit_atr"))) {

					Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();

					int targetSec = buySellData.getStrategyData().getSlots() - livePositions.size();
					limitOrderMap = new HashMap<>();

					List<LimitOrder> limitOrdersList = new LinkedList<>();
					for (String entry : entryExitMap.get("entry")) {

						if (!livePositions.contains(entry) && targetSec > 0) {

							float atrValue = priceData.getDaily_atr().getValue(date, entry);
							float closePrice = priceData.getDaily_closes().getValue(date, entry);

							float limit_price = closePrice - (buySellData.getStrategyData().getLimitPct() * atrValue);
							limit_price = Math.round(limit_price * 100f) / 100f;
							limitOrdersList.add(LimitOrder.builder().ticker(entry).limitPrice(limit_price).build());

							targetSec--;

						}

					}
					limitOrderMap.put("limit_orders", limitOrdersList);

				} else if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit"))) {
					Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();
					int targetSec = buySellData.getStrategyData().getSlots() - livePositions.size();
					limitOrderMap = new HashMap<>();
					List<LimitOrder> limitOrdersList = new LinkedList<>();
					for (String entry : entryExitMap.get("entry")) {
						if (!livePositions.contains(entry) && targetSec > 0) {
							float closePrice = priceData.getDaily_closes().getValue(date, entry);
							float limit_price;
							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("short"))) {
								// SHORT: limit ABOVE close — stock must rally up to fill
								limit_price = closePrice * (1 + (buySellData.getStrategyData().getLimitPct() / 100));
							} else {
								// LONG: limit BELOW close — stock must dip down to fill
								limit_price = closePrice * (1 - (buySellData.getStrategyData().getLimitPct() / 100));
							}
							limitOrdersList.add(LimitOrder.builder().ticker(entry).limitPrice(limit_price).build());
							targetSec--;

						}
					}
					limitOrderMap.put("limit_orders", limitOrdersList);

				}

			}

			if (date.isEqual(buySellData.getStrategyData().getEndDate())) {
				this.portfolioService.endOfBacktest(date);
			}

			previousDate = date;

		}
		return this.portfolioService.getPortfolio();
	}

	@Override
	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, BuySellDataV2 buySellData) {

		this.portfolioService.setPriceDate(priceData, buySellData.getStrategyData().getStartingCapital(),
				buySellData.getStrategyData().getSlots(), buySellData.getStrategyData().getStopLossPct(),
				buySellData.getStrategyData().getTakeProfitPct());

		Map<String, List<String>> entryExitMap = null;

		Map<String, List<LimitOrder>> limitOrderMap = null;

		LocalDate previousDate = null;

		// Volatility-cut (freeze/resume) suspension state — persists across days.
		// Decided on YESTERDAY's close (previousDate), executed at TODAY's open.
		boolean suspended = false;
		// Stage 3b: safety-net policies replace the legacy freezeDays/resumeDays.
		java.util.List<SafetyNetPolicy> safetyPolicies = buySellData
				.getStrategyData().getSafetyPolicies();
		if (safetyPolicies == null)
			safetyPolicies = java.util.Collections.emptyList();

		Map<LocalDate, Integer> tdomMap = computeTdomMap(priceData.getAll_dates());

		for (LocalDate date : priceData.getAll_dates()) {

			if (date.equals(LocalDate.of(2000, 1, 3))) {
				System.err.println();
			}

			if (((date.isEqual(priceData.getTrading_dates().get(0))
					|| date.isAfter(priceData.getTrading_dates().get(0)))
					&& date.isBefore(buySellData.getStrategyData().getEndDate()))

					|| date.isEqual(buySellData.getStrategyData().getEndDate())) {

				if (priceData.getTrading_dates().contains(date)) {

					// Stage 3b: dispatch through the policy list at open phase.
					suspended = dispatchSafetyNetsAtOpen(
							safetyPolicies, date, previousDate, suspended, priceData);

					// Exit Orders
					if (buySellData.getStrategyData().getExitTiming().equals("open")) {

						if (entryExitMap != null && entryExitMap.get("exit") != null
								&& !entryExitMap.get("exit").isEmpty()) {

							ExitSignalsRequestDto exitSignals = ExitSignalsRequestDto.builder().tradeDate(date)
									.exits(entryExitMap.get("exit")).reasonForExit("Exits").exitTime("open").build();

							this.portfolioService.executeExitSignals(exitSignals);
						}
					}

					// Entry Orders (blocked while volatility-cut suspension is active)

					if (!suspended && buySellData.getStrategyData().getEntryTiming().equals("open")) {
						if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("normal"))) {
							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("long"))
									|| buySellData.getStrategyData().getSystemType()
											.equals(StaticConfig.systemType.get("short"))) {
								processNormalOrders(date, previousDate, entryExitMap, buySellData);
							}

						} else if (buySellData.getStrategyData().getOrderType()
								.equals(StaticConfig.orderType.get("limit_atr"))
								|| buySellData.getStrategyData().getOrderType()
										.equals(StaticConfig.orderType.get("limit"))) {

							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("long"))
									|| buySellData.getStrategyData().getSystemType()
											.equals(StaticConfig.systemType.get("short"))) {
								int tdom = tdomMap.getOrDefault(date, -1);

								List<Integer> bannedMonths = buySellData.getStrategyData().getBannedMonths();
								boolean monthBanned = bannedMonths != null
										&& bannedMonths.contains(date.getMonthValue());

								if (!monthBanned
										&& !isTdomBlocked(date, tdom, buySellData.getStrategyData().getTdomFilters())) {
//									processLimitOrdersLong(date, previousDate, limitOrderMap, buySellData);

									if (buySellData.getStrategyData().getSystemType()
											.equals(StaticConfig.systemType.get("short"))) {
										System.err.println("=== LIMIT SHORT BRANCH HIT === " + date);
										processLimitOrdersShort(date, previousDate, limitOrderMap, buySellData);
									} else {
										processLimitOrdersLong(date, previousDate, limitOrderMap, buySellData);
									}

								}
							}

						}
					}
				}

				this.portfolioService.markToMarket(date);

				// StopLoss
				if (StaticConfig.stoplossType.get("nrml").equals(buySellData.getStrategyData().getStoplossType())
						&& buySellData.getStrategyData().getStopLossPct() > 0) {
					this.portfolioService.checkStoplossHit(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				}

				// Takeprofit
				if (StaticConfig.takeProfitType.get("nrml").equals(buySellData.getStrategyData().getTakeprofitType())
						&& buySellData.getStrategyData().getTakeProfitPct() > 0) {
					this.portfolioService.checkTakeProfit(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				}

				// EOD Close
				if ("eod_close".equalsIgnoreCase(buySellData.getStrategyData().getExitTiming())) {
					this.portfolioService.closeAllPositionsAtEodClose(date);
				}

				this.portfolioService.checkLivePositionsOnTommorow(date);
				// Stage 3b: close-phase safety-net dispatch (Simple regime path).
				// Previously absent in this loop — now symmetric with runBacktestV2.
				if (priceData.getTrading_dates().contains(date)) {
					suspended = dispatchSafetyNetsAtClose(safetyPolicies, date, suspended);
				}
				entryExitMap = strategyBuilderService.signalsForTheDay(date, priceData, buySellData,
						this.portfolioService);

				if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit_atr"))) {

					Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();

					int targetSec = buySellData.getStrategyData().getSlots() - livePositions.size();
					limitOrderMap = new HashMap<>();

					List<LimitOrder> limitOrdersList = new LinkedList<>();
					for (String entry : entryExitMap.get("entry")) {

						if (!livePositions.contains(entry) && targetSec > 0) {

							float atrValue = priceData.getDaily_atr().getValue(date, entry);
							float closePrice = priceData.getDaily_closes().getValue(date, entry);

							float limit_price = closePrice - (buySellData.getStrategyData().getLimitPct() * atrValue);
							limit_price = Math.round(limit_price * 100f) / 100f;
							limitOrdersList.add(LimitOrder.builder().ticker(entry).limitPrice(limit_price).build());

							targetSec--;

						}

					}
					limitOrderMap.put("limit_orders", limitOrdersList);

				} else if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit"))) {
					Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();
					int targetSec = buySellData.getStrategyData().getSlots() - livePositions.size();
					limitOrderMap = new HashMap<>();
					List<LimitOrder> limitOrdersList = new LinkedList<>();
					for (String entry : entryExitMap.get("entry")) {
						if (!livePositions.contains(entry) && targetSec > 0) {
							float closePrice = priceData.getDaily_closes().getValue(date, entry);
							float limit_price;
							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("short"))) {
								limit_price = closePrice * (1 + (buySellData.getStrategyData().getLimitPct() / 100));
							} else {
								limit_price = closePrice * (1 - (buySellData.getStrategyData().getLimitPct() / 100));
							}
							limitOrdersList.add(LimitOrder.builder().ticker(entry).limitPrice(limit_price).build());
							targetSec--;

						}
					}
					limitOrderMap.put("limit_orders", limitOrdersList);

				}

			}

			if (date.isEqual(buySellData.getStrategyData().getEndDate())) {
				this.portfolioService.endOfBacktest(date);
			}

			previousDate = date;

		}
		return this.portfolioService.getPortfolio();
	}

	@Override
	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, Map<LocalDate, String> marketTrends,
			Map<String, BuySellDataV2> rulesOfDayRegimes) {

		Map<String, List<String>> entryExitMap = null;

		Map<String, List<LimitOrder>> limitOrderMap = null;

		LocalDate previousDate = null;

		BuySellDataV2 buySellData = null;

		String marketTrendOfDay = "";
		String previousDayMarketTrend = "";

		Map<LocalDate, Integer> tdomMap = computeTdomMap(priceData.getAll_dates());

		for (LocalDate date : priceData.getAll_dates()) {
//			System.err.println(date);

			if (date.equals(LocalDate.of(2000, 1, 3))) {
				System.err.println();
				continue;
			}
			if (date.equals(LocalDate.of(2021, 8, 18))) {
				System.err.println();
			}
			if (((date.isEqual(priceData.getTrading_dates().get(0))
					|| date.isAfter(priceData.getTrading_dates().get(0))) && date.isBefore(priceData.getEndDate()))

					|| date.isEqual(priceData.getEndDate())) {

				if (priceData.getTrading_dates().contains(date) && buySellData != null) {

					// Exit Orders
					if (buySellData.getStrategyData().getExitTiming().equals("open")) {

						if (entryExitMap != null && entryExitMap.get("exit") != null
								&& !entryExitMap.get("exit").isEmpty()) {

							ExitSignalsRequestDto exitSignals = ExitSignalsRequestDto.builder().tradeDate(date)
									.exits(entryExitMap.get("exit")).reasonForExit("Exits").exitTime("open").build();

							this.portfolioService.executeExitSignals(exitSignals);
						}
					}

					// Max Time is Enabled
					if (buySellData.getStrategyData().getMaxTime() > 0) {
						this.portfolioService.checkMaxTime(date, buySellData.getStrategyData().getMaxTime(), priceData);
					}

					// REGIME SHIFT
					if (previousDayMarketTrend != null && marketTrendOfDay != null
							&& !previousDayMarketTrend.equalsIgnoreCase(marketTrendOfDay)) {

						// Only force-close positions if the OUTGOING regime requested it.
						// At this point in the loop, buySellData still references the
						// outgoing regime's StrategyData (the swap to the new regime
						// happens further down). So checking its flag is correct: this
						// is the regime whose positions would be closed.
						//
						// Default behaviour (flag = false) matches Python QAS, which lets
						// open trades exit via their own signals (RSI<30, stop loss, etc.)
						// even after the market trend flips.
						if (buySellData.getStrategyData().isClosePositionsOnRegimeExit()) {
							String reason = String.format("%s %s %s %s", "Market Shift", previousDayMarketTrend, "to",
									marketTrendOfDay);
							this.portfolioServiceImplV2.closeAllPositionsOnOpenPrice(date, priceData, reason);
						}

					}

					// Entry Orders
					if (buySellData.getStrategyData().getEntryTiming().equals("open")) {
						if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("normal"))) {
							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("long"))
									|| buySellData.getStrategyData().getSystemType()
											.equals(StaticConfig.systemType.get("short"))) {
//								System.err.println("=== Process Normal SHORT BRANCH HIT === " + date);
								processNormalOrders(date, previousDate, entryExitMap, buySellData);
							}

						} else if (buySellData.getStrategyData().getOrderType()
								.equals(StaticConfig.orderType.get("limit_atr"))
								|| buySellData.getStrategyData().getOrderType()
										.equals(StaticConfig.orderType.get("limit"))) {

							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("long"))
									|| buySellData.getStrategyData().getSystemType()
											.equals(StaticConfig.systemType.get("short"))) {

								int tdom = tdomMap.getOrDefault(date, -1);
								List<Integer> bannedMonths = buySellData.getStrategyData().getBannedMonths();
								boolean monthBanned = bannedMonths != null
										&& bannedMonths.contains(date.getMonthValue());

								if (!monthBanned
										&& !isTdomBlocked(date, tdom, buySellData.getStrategyData().getTdomFilters())) {
//									processLimitOrdersLong(date, previousDate, limitOrderMap, buySellData);
									if (buySellData.getStrategyData().getSystemType()
											.equals(StaticConfig.systemType.get("short"))) {
//										System.err.println("=== LIMIT SHORT BRANCH HIT === " + date);
										processLimitOrdersShort(date, previousDate, limitOrderMap, buySellData);
									} else {
										processLimitOrdersLong(date, previousDate, limitOrderMap, buySellData);
									}
								}

							}

						}
					}

				}

				this.portfolioService.markToMarket(date);

				if (buySellData != null) {

					// StopLoss
					if (StaticConfig.stoplossType.get("nrml").equals(buySellData.getStrategyData().getStoplossType())
							&& buySellData.getStrategyData().getStopLossPct() > 0) {
						this.portfolioService.checkStoplossHit(date, buySellData.getStrategyData().getSystemType(),
								buySellData.getStrategyData().getStoplossTiming());
					}

					// Takeprofit
					if (StaticConfig.takeProfitType.get("nrml")
							.equals(buySellData.getStrategyData().getTakeprofitType())
							&& buySellData.getStrategyData().getTakeProfitPct() > 0) {
						this.portfolioService.checkTakeProfit(date, buySellData.getStrategyData().getSystemType(),
								buySellData.getStrategyData().getStoplossTiming());
					}
				}

				// EOD Close
				if (buySellData != null
						&& "eod_close".equalsIgnoreCase(buySellData.getStrategyData().getExitTiming())) {
					this.portfolioService.closeAllPositionsAtEodClose(date);
				}

				this.portfolioService.checkLivePositionsOnTommorow(date);

				previousDayMarketTrend = marketTrendOfDay;
				String newTrend = marketTrends.get(date);
				if (newTrend != null) {
					marketTrendOfDay = newTrend;
					BuySellDataV2 newBuySellData = rulesOfDayRegimes.get(marketTrendOfDay);
					if (newBuySellData != null) {
						buySellData = newBuySellData;
					}
				}

				if (buySellData != null) {
					this.portfolioService.setBasicDeatils(priceData, buySellData.getStrategyData().getStartingCapital(),
							buySellData.getStrategyData().getSlots(), buySellData.getStrategyData().getStopLossPct(),
							buySellData.getStrategyData().getTakeProfitPct());

//					long start = System.nanoTime();
					entryExitMap = strategyBuilderService.signalsForTheDayV1(date, priceData, buySellData,
							this.portfolioService);

					// If exit_timing is "close", execute exits immediately at today's close
					if (buySellData.getStrategyData().getExitTiming().equals("close")) {
						if (entryExitMap != null && entryExitMap.get("exit") != null
								&& !entryExitMap.get("exit").isEmpty()) {
							ExitSignalsRequestDto exitSignals = ExitSignalsRequestDto.builder().tradeDate(date)
									.exits(entryExitMap.get("exit")).reasonForExit("Exits").exitTime("close").build();
							this.portfolioService.executeExitSignals(exitSignals);
						}
					}

				}

//				long end = System.nanoTime();

//				double elapsedSeconds = (end - start) / 1_000_000_000.0;
//				System.err.printf("⏱️ Signals For the Day: %.3f seconds%n", elapsedSeconds);

				this.portfolioService.updateTradeDayCount(date);

				if (buySellData != null && entryExitMap != null) {
					if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit_atr"))) {

						Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();

						int targetSec = buySellData.getStrategyData().getSlots() - livePositions.size();
						limitOrderMap = new HashMap<>();

						List<LimitOrder> limitOrdersList = new LinkedList<>();
						for (String entry : entryExitMap.get("entry")) {

							if (!livePositions.contains(entry) && targetSec > 0) {

								float atrValue = priceData.getDaily_atr().getValue(date, entry);
								float closePrice = priceData.getDaily_closes().getValue(date, entry);

								float limit_price = closePrice
										- (buySellData.getStrategyData().getLimitPct() * atrValue);
								limit_price = Math.round(limit_price * 100f) / 100f;
								limitOrdersList.add(LimitOrder.builder().ticker(entry).limitPrice(limit_price).build());

								targetSec--;

							}

						}
						limitOrderMap.put("limit_orders", limitOrdersList);

					} else if (buySellData.getStrategyData().getOrderType()
							.equals(StaticConfig.orderType.get("limit"))) {
						Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();
						int exitCount = 0;
						for (String live : livePositions) {
							if (entryExitMap.get("exit").contains(live)) {
								exitCount++;
							}
						}

						int targetSec = buySellData.getStrategyData().getSlots() - (livePositions.size() - exitCount);
						limitOrderMap = new HashMap<>();
						List<LimitOrder> limitOrdersList = new LinkedList<>();
						for (String entry : entryExitMap.get("entry")) {
							if (!livePositions.contains(entry) && targetSec > 0) {
								float closePrice = priceData.getDaily_closes().getValue(date, entry);
								float limit_price;
								if (buySellData.getStrategyData().getSystemType()
										.equals(StaticConfig.systemType.get("short"))) {
									limit_price = closePrice
											* (1 + (buySellData.getStrategyData().getLimitPct() / 100));
								} else {
									limit_price = closePrice
											* (1 - (buySellData.getStrategyData().getLimitPct() / 100));
								}
								limitOrdersList.add(LimitOrder.builder().ticker(entry).limitPrice(limit_price).build());
								targetSec--;

							}
						}
						limitOrderMap.put("limit_orders", limitOrdersList);

					}
				}

			}

			if (date.isEqual(priceData.getEndDate())) {
				this.portfolioService.endOfBacktest(date);
			}

			previousDate = date;

			if (buySellData == null) {
				previousDayMarketTrend = marketTrendOfDay;
				String initTrend = marketTrends.get(date);
				if (initTrend != null) {
					marketTrendOfDay = initTrend;
					buySellData = rulesOfDayRegimes.get(marketTrendOfDay);
					if (buySellData != null) {
						this.portfolioService.setPriceDate(priceData,
								buySellData.getStrategyData().getStartingCapital(),
								buySellData.getStrategyData().getSlots(),
								buySellData.getStrategyData().getStopLossPct(),
								buySellData.getStrategyData().getTakeProfitPct());
					}
				}

			}

		}
		return this.portfolioService.getPortfolio();
	}

	// ────────────────────────────────────────────────────────────────────
	// Stage 3b — Safety-net policy dispatch helpers.
	//
	// Each day, the loop asks every active SafetyNetPolicy what to do.
	// A freeze decision from any policy is enough to suspend; a resume
	// decision lifts suspension. The day-loop just calls these helpers
	// and updates its local `suspended` flag.
	//
	// These replace the inline freezeDays.contains(...)/resumeDays code
	// that used to live in every day-loop. The previous algorithm is
	// preserved inside SimpleFreezeResumePolicy, so behaviour is identical
	// for any strategy whose only policy is "simple".
	// ────────────────────────────────────────────────────────────────────

	/**
	 * Run all policies' open-phase evaluation. Returns the updated suspended flag
	 * after applying every policy's decision.
	 */
	private boolean dispatchSafetyNetsAtOpen(
			java.util.List<com.backtest.engine.service.safetynet.SafetyNetPolicy> policies, java.time.LocalDate date,
			java.time.LocalDate previousDate, boolean suspended, PriceDataV2 priceData) {
		if (policies == null || policies.isEmpty())
			return suspended;
		for (com.backtest.engine.service.safetynet.SafetyNetPolicy p : policies) {
			com.backtest.engine.service.safetynet.SafetyNetDecision d = p.evaluateAtOpen(date, previousDate);
			if (d.isResume()) {
				suspended = false;
			}
			if (d.isSuspend()) {
				// Suspend trading but leave positions intact (e.g. SPY-vol re-arm)
				suspended = true;
			}
			if (d.isFreeze()) {
				String reason = (d.getReason() == null || d.getReason().isBlank())
						? "Volatility Cut" : "Volatility Cut: " + d.getReason();
				this.portfolioServiceImplV2.closeAllPositionsOnOpenPrice(date, priceData, reason);
				suspended = true;
			}
		}
		return suspended;
	}

	/**
	 * Run all policies' close-phase evaluation. Returns the updated suspended flag
	 * after applying every policy's decision.
	 */
	private boolean dispatchSafetyNetsAtClose(
			java.util.List<com.backtest.engine.service.safetynet.SafetyNetPolicy> policies, java.time.LocalDate date,
			boolean suspended) {
		if (policies == null || policies.isEmpty())
			return suspended;
		for (com.backtest.engine.service.safetynet.SafetyNetPolicy p : policies) {
			com.backtest.engine.service.safetynet.SafetyNetDecision d = p.evaluateAtClose(date);
			if (d.isResume()) {
				suspended = false;
			}
			if (d.isSuspend()) {
				suspended = true;
			}
			if (d.isFreeze()) {
				String reason = (d.getReason() == null || d.getReason().isBlank())
						? "Volatility Cut" : "Volatility Cut: " + d.getReason();
				this.portfolioServiceImplV2.closeAllPositionsAtClose(date, reason);
				suspended = true;
			}
		}
		return suspended;
	}

}
