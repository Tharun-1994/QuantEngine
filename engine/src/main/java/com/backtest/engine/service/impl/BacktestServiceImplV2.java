package com.backtest.engine.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import com.backtest.engine.dto.request.LiveHoldingsSeedDto;
import com.backtest.engine.dto.request.TdomFilterDto;
import com.backtest.engine.dto.request.TradeEnterRequestDto;
import com.backtest.engine.dto.request.TradeExitRequestDto; // LRA Patch 24
import com.backtest.engine.dto.request.VolFilterDto;
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.LimitOrder;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.entity.StrategyDataV2;
import com.backtest.engine.entity.TradeLog;
import com.backtest.engine.entity.TradePair; // LRA Patch 29
import com.backtest.engine.service.BacktestServiceV2;
import com.backtest.engine.service.ForceCloseExitProcessor; // LRA Patch 22b
import com.backtest.engine.service.PairExitContext; // LRA Patch 24
import com.backtest.engine.service.PairProfitExitProcessor; // LRA Patch 22b
import com.backtest.engine.service.PairingContext; // LRA Patch 29
import com.backtest.engine.service.PairingService; // LRA Patch 22b
import com.backtest.engine.service.PortfolioServiceV2;
import com.backtest.engine.service.SizingContext; // LRA Patch 29
import com.backtest.engine.service.SizingPolicyResolver; // LRA Patch 29
import com.backtest.engine.service.StrategyBuilderServiceV2;
import com.backtest.engine.service.safetynet.SafetyNetPolicy;
import com.backtest.engine.util.ArrowDataFrame;

@Service
public class BacktestServiceImplV2 implements BacktestServiceV2 {

	private final PortfolioServiceImpl portfolioServiceImpl;

	private final PortfolioServiceImplV2 portfolioServiceImplV2;

	@Autowired
	PortfolioServiceV2 portfolioService;

	// LRA Patch 22b: Phase-2 pair-trading services. Wired here so Spring
	// resolves them at startup; consumed only by runBacktestLongShortV2.
	@Autowired
	PairingService pairingService;

	@Autowired
	PairProfitExitProcessor pairProfitExitProcessor;

	@Autowired
	ForceCloseExitProcessor forceCloseExitProcessor;

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
		// Patch 115: trigger month/tdom are configurable; null-coalesce to
		// legacy defaults (January, TDOM 0). isFirstDay stays unconditional,
		// matching Python: (d == trading_dates[0]) or (month==1 and tdom==0).
		int triggerMonth = (vf.getTriggerMonth() != null && vf.getTriggerMonth() >= 1 && vf.getTriggerMonth() <= 12)
				? vf.getTriggerMonth()
				: 1;
		int triggerTdom = (vf.getTriggerTdom() != null && vf.getTriggerTdom() >= 0) ? vf.getTriggerTdom() : 0;
		boolean isJanTdom0 = (tdom == triggerTdom && date.getMonthValue() == triggerMonth);
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
		// Patch 115: SPY SMA lookback configurable; null/invalid → legacy 200.
		int smaLookback = (vf.getSpySmaLookback() != null && vf.getSpySmaLookback() > 0) ? vf.getSpySmaLookback() : 200;
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

	/**
	 * Existing callers use this 4-arg overload — defaults to entry_timing="open".
	 */
	private void processNormalOrders(LocalDate date, LocalDate previousDate, Map<String, List<String>> entryExitMap,
			BuySellDataV2 buySellData) {
		processNormalOrders(date, previousDate, entryExitMap, buySellData, "open");
	}

	/**
	 * Execute NORMAL (market) entry orders at the specified timing. entryTime is
	 * "open" or "close" — passed through to executeEntrySignals which picks the
	 * right price column.
	 */
	private void processNormalOrders(LocalDate date, LocalDate previousDate, Map<String, List<String>> entryExitMap,
			BuySellDataV2 buySellData, String entryTime) {

		if (entryExitMap != null && entryExitMap.get("entry") != null && !entryExitMap.get("entry").isEmpty()) {

			int maxQuantity = buySellData.getStrategyData().getMinQuantity() > 0
					? (int) buySellData.getStrategyData().getMinQuantity()
					: 1;

			float minStockPricePerSlot = buySellData.getStrategyData().getMinPrice() > 0
					? buySellData.getStrategyData().getMinPrice()
					: 0;

			EntrySignalsRequestDto entrySignals = EntrySignalsRequestDto.builder().tradeDate(date)
					.entries(entryExitMap.get("entry")).maxSingleStock(buySellData.getStrategyData().getMaxSameTicker())
					.reasonForEntry("Entries").entryTime(entryTime)
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

		// LRA Patch 22b: early branch for LONGSHORT pair-trading strategies.
		// ROC and any other LONG/SHORT strategy skips this branch and runs the
		// existing code path below unchanged — provably byte-identical because
		// StaticConfig.systemType.get("long_short") returns "LONGSHORT" which
		// never matches the systemType of a LONG or SHORT strategy.
		if (StaticConfig.systemType.get("long_short").equals(buySellData.getStrategyData().getSystemType())) {
			return runBacktestLongShortV2(priceData, buySellData);
		}

		this.portfolioService.setPriceDate(priceData, buySellData.getStrategyData().getStartingCapital(),
				buySellData.getStrategyData().getSlots(), buySellData.getStrategyData().getStopLossPct(),
				buySellData.getStrategyData().getTakeProfitPct());
		this.portfolioService.setPortfolioStoplossAnchor(buySellData.getStrategyData().getPortfolioStoplossAnchor());
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

			// Patch 65d (revised): PORTFOLIO halt guard. Once the trip flag is
			// set (by checkPortfolioStoplossHit on a prior close), this guard:
			// 1. Closes any remaining live positions at this day's open
			// (one-shot on the first halt day; liveHoldings is empty
			// on subsequent halt days).
			// 2. Calls markToMarket so the equity series stays continuous —
			// without this, equityLogger would have a gap on every halt
			// day and downstream performance/drawdown breaks.
			// 3. Runs endOfBacktest if this is the final bar (otherwise the
			// wrap-up never fires for trip-halted strategies).
			// 4. Updates previousDate and continues — skipping signals,
			// entries, per-position stops, takeprofit, safety-net dispatch.
			// No-op when stoploss_type != PORTFOLIO (flag never set in that case).
			if (this.portfolioService.isPortfolioStoplossTripped()) {
				LocalDate firstTradingDate = priceData.getTrading_dates().get(0);
				LocalDate endDate = buySellData.getStrategyData().getEndDate();
				boolean inRange = !date.isBefore(firstTradingDate) && !date.isAfter(endDate);
				if (inRange) {
					if (!this.portfolioService.getLiveHoldingsLogger().isEmpty()) {
						this.portfolioService.closeAllPositionsOnOpenPrice(date, priceData,
								this.portfolioService.getPortfolioStoplossReason());
					}
					this.portfolioService.markToMarket(date);
					if (date.isEqual(endDate)) {
						this.portfolioService.endOfBacktest(date);
					}

					// Patch 73c.1: circuit-breaker resume. Trading resumes from
					// the NEXT bar — today's continue still skips entries and
					// signal-builder for this flush day.
					this.portfolioService.clearPortfolioStoplossTrip(date);
				}

				previousDate = date;
				continue;
			}

			if (date.equals(LocalDate.of(2026, 6, 26))) {
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
				// Patch 65a: added dollar_based + portfolio branches.
				// Note: PORTFOLIO check fires AFTER markToMarket so equity is fresh.
				// On trip, isPortfolioStoplossTripped() returns true and the
				// guard at the top of the next iteration (Patch 65d) closes all
				// at next-day open + skips all subsequent dates.
				if (StaticConfig.stoplossType.get("nrml").equals(buySellData.getStrategyData().getStoplossType())
						&& buySellData.getStrategyData().getStopLossPct() > 0) {
					this.portfolioService.checkStoplossHit(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				} else if (StaticConfig.stoplossType.get("atr_based")
						.equals(buySellData.getStrategyData().getStoplossType())
						&& buySellData.getStrategyData().getStopLossPct() > 0) {
					this.portfolioService.checkStoplossHitAtr(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				} else if (StaticConfig.stoplossType.get("dollar_based")
						.equals(buySellData.getStrategyData().getStoplossType())) {
					this.portfolioService.checkStoplossHitDollar(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				} else if (StaticConfig.stoplossType.get("portfolio")
						.equals(buySellData.getStrategyData().getStoplossType())
						&& buySellData.getStrategyData().getStopLossPct() > 0) {
					this.portfolioService.checkPortfolioStoplossHit(date);
				}

				// Takeprofit
				if (StaticConfig.takeProfitType.get("nrml").equals(buySellData.getStrategyData().getTakeprofitType())
						&& buySellData.getStrategyData().getTakeProfitPct() > 0) {
					this.portfolioService.checkTakeProfit(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getTakeprofitTiming());
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
		// Patch 72p.2: wire anchor for PORTFOLIO stoploss.
		this.portfolioService.setPortfolioStoplossAnchor(buySellData.getStrategyData().getPortfolioStoplossAnchor());

		Map<String, List<String>> entryExitMap = null;

		Map<String, List<LimitOrder>> limitOrderMap = null;

		LocalDate previousDate = null;

		// Volatility-cut (freeze/resume) suspension state — persists across days.
		// Decided on YESTERDAY's close (previousDate), executed at TODAY's open.
		boolean suspended = false;
		// Stage 3b: safety-net policies replace the legacy freezeDays/resumeDays.
		java.util.List<SafetyNetPolicy> safetyPolicies = buySellData.getStrategyData().getSafetyPolicies();
		if (safetyPolicies == null)
			safetyPolicies = java.util.Collections.emptyList();

		Map<LocalDate, Integer> tdomMap = computeTdomMap(priceData.getAll_dates());

		for (LocalDate date : priceData.getAll_dates()) {

			// Patch 65d (revised): PORTFOLIO halt guard. Once the trip flag is
			// set (by checkPortfolioStoplossHit on a prior close), this guard:
			// 1. Closes any remaining live positions at this day's open
			// (one-shot on the first halt day; liveHoldings is empty
			// on subsequent halt days).
			// 2. Calls markToMarket so the equity series stays continuous —
			// without this, equityLogger would have a gap on every halt
			// day and downstream performance/drawdown breaks.
			// 3. Runs endOfBacktest if this is the final bar (otherwise the
			// wrap-up never fires for trip-halted strategies).
			// 4. Updates previousDate and continues — skipping signals,
			// entries, per-position stops, takeprofit, safety-net dispatch.
			// No-op when stoploss_type != PORTFOLIO (flag never set in that case).
			if (this.portfolioService.isPortfolioStoplossTripped()) {
				LocalDate firstTradingDate = priceData.getTrading_dates().get(0);
				LocalDate endDate = buySellData.getStrategyData().getEndDate();
				boolean inRange = !date.isBefore(firstTradingDate) && !date.isAfter(endDate);
				if (inRange) {
					if (!this.portfolioService.getLiveHoldingsLogger().isEmpty()) {
						this.portfolioService.closeAllPositionsOnOpenPrice(date, priceData,
								this.portfolioService.getPortfolioStoplossReason());
					}
					this.portfolioService.markToMarket(date);
					if (date.isEqual(endDate)) {
						this.portfolioService.endOfBacktest(date);
					}
					this.portfolioService.clearPortfolioStoplossTrip(date);
				}
				previousDate = date;
				continue;
			}

			if (date.equals(LocalDate.of(2000, 1, 3))) {
				System.err.println();
			}

			if (((date.isEqual(priceData.getTrading_dates().get(0))
					|| date.isAfter(priceData.getTrading_dates().get(0)))
					&& date.isBefore(buySellData.getStrategyData().getEndDate()))

					|| date.isEqual(buySellData.getStrategyData().getEndDate())) {

				if (priceData.getTrading_dates().contains(date)) {

					// Stage 3b: dispatch through the policy list at open phase.
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

				// StopLoss — Patch 65b: added dollar_based + portfolio branches.
				if (StaticConfig.stoplossType.get("nrml").equals(buySellData.getStrategyData().getStoplossType())
						&& buySellData.getStrategyData().getStopLossPct() > 0) {
					this.portfolioService.checkStoplossHit(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				} else if (StaticConfig.stoplossType.get("dollar_based")
						.equals(buySellData.getStrategyData().getStoplossType())) {
					this.portfolioService.checkStoplossHitDollar(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				} else if (StaticConfig.stoplossType.get("portfolio")
						.equals(buySellData.getStrategyData().getStoplossType())
						&& buySellData.getStrategyData().getStopLossPct() > 0) {
					this.portfolioService.checkPortfolioStoplossHit(date);
				}

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

	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, Map<LocalDate, String> marketTrends,
			Map<String, BuySellDataV2> rulesOfDayRegimes) {
		// Patch 11: 3-arg signature preserved — existing callers (runbacktestv3
		// "simple" branch) unchanged. Delegates to the 4-arg overload with
		// seedHoldings=null, which keeps backtest behavior byte-identical
		// (no seed step runs).
		return runBacktestSimpleV2(priceData, marketTrends, rulesOfDayRegimes, null);
	}

	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, Map<LocalDate, String> marketTrends,
			Map<String, BuySellDataV2> rulesOfDayRegimes, List<LiveHoldingsSeedDto> seedHoldings) {
		// LRA Patch 25a: early branch for LONGSHORT multi-regime pair-trading.
		// systemType is shared across regimes (set at strategy level), so we
		// probe any regime to learn it. ROC and other LONG/SHORT multi-regime
		// strategies skip this branch and run the existing code path unchanged.
		if (rulesOfDayRegimes != null && !rulesOfDayRegimes.isEmpty()) {
			BuySellDataV2 anyRegime = rulesOfDayRegimes.values().iterator().next();
			this.portfolioService.setPriceDate(priceData, anyRegime.getStrategyData().getStartingCapital(),
					anyRegime.getStrategyData().getSlots(), anyRegime.getStrategyData().getStopLossPct(),
					anyRegime.getStrategyData().getTakeProfitPct());
			// Patch 72p.3: wire anchor for PORTFOLIO stoploss.
			this.portfolioService.setPortfolioStoplossAnchor(anyRegime.getStrategyData().getPortfolioStoplossAnchor());
		}

		Map<String, List<String>> entryExitMap = null;

		Map<String, List<LimitOrder>> limitOrderMap = null;

		LocalDate previousDate = null;

		BuySellDataV2 buySellData = null;

		String marketTrendOfDay = "";
		String previousDayMarketTrend = "";

		Map<LocalDate, Integer> tdomMap = computeTdomMap(priceData.getAll_dates());

		// ── Eager portfolio init (Bug B fix) ─────────────────────────────────
		// markToMarket() runs every trading day regardless of regime activity.
		// For single-regime strategies whose market trend rule is false during
		// warmup (e.g. ROC_SP500 bear-only with 2000 bull warmup), the lazy
		// init at the bottom of the loop never fires before the first
		// markToMarket call → NPE on liveHoldingsLogger.
		// Initialise upfront using any regime's StrategyData. setBasicDeatils
		// later in the loop keeps slot-count in sync as regimes activate.
		if (rulesOfDayRegimes != null && !rulesOfDayRegimes.isEmpty()) {
			BuySellDataV2 anyRegime = rulesOfDayRegimes.values().iterator().next();
			this.portfolioService.setPriceDate(priceData, anyRegime.getStrategyData().getStartingCapital(),
					anyRegime.getStrategyData().getSlots(), anyRegime.getStrategyData().getStopLossPct(),
					anyRegime.getStrategyData().getTakeProfitPct());
		}

		// Patch 11: seed liveHoldingsLogger from caller-supplied positions BEFORE
		// the day loop. No-op when seedHoldings is null — backtest mode unchanged.
		if (seedHoldings != null && !seedHoldings.isEmpty()) {
			this.portfolioService.seedLiveHoldings(seedHoldings);
		}

		for (LocalDate date : priceData.getAll_dates()) {

			// Patch 65d (revised): PORTFOLIO halt guard. Once the trip flag is
			// set (by checkPortfolioStoplossHit on a prior close), this guard:
			// 1. Closes any remaining live positions at this day's open
			// (one-shot on the first halt day; liveHoldings is empty
			// on subsequent halt days).
			// 2. Calls markToMarket so the equity series stays continuous —
			// without this, equityLogger would have a gap on every halt
			// day and downstream performance/drawdown breaks.
			// 3. Runs endOfBacktest if this is the final bar (otherwise the
			// wrap-up never fires for trip-halted strategies).
			// 4. Updates previousDate and continues — skipping signals,
			// entries, per-position stops, takeprofit.
			// No-op when stoploss_type != PORTFOLIO (flag never set in that case).
			if (this.portfolioService.isPortfolioStoplossTripped()) {
				LocalDate firstTradingDate = priceData.getTrading_dates().get(0);
				LocalDate endDate = priceData.getEndDate();
				boolean inRange = !date.isBefore(firstTradingDate) && !date.isAfter(endDate);
				if (inRange) {
					if (!this.portfolioService.getLiveHoldingsLogger().isEmpty()) {
						this.portfolioService.closeAllPositionsOnOpenPrice(date, priceData,
								this.portfolioService.getPortfolioStoplossReason());
					}
					this.portfolioService.markToMarket(date);
					if (date.isEqual(endDate)) {
						this.portfolioService.endOfBacktest(date);
					}
					this.portfolioService.clearPortfolioStoplossTrip(date);
				}
				previousDate = date;
				continue;
			}

//			System.err.println(date);

			if (date.equals(LocalDate.of(2025, 1, 3))) {
				System.err.println();
				continue;
			}
			if (date.equals(LocalDate.of(2026, 6, 12))) {
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

					// StopLoss — Patch 65c: added dollar_based + portfolio branches.
					if (StaticConfig.stoplossType.get("nrml").equals(buySellData.getStrategyData().getStoplossType())
							&& buySellData.getStrategyData().getStopLossPct() > 0) {
						this.portfolioService.checkStoplossHit(date, buySellData.getStrategyData().getSystemType(),
								buySellData.getStrategyData().getStoplossTiming());
					} else if (StaticConfig.stoplossType.get("dollar_based")
							.equals(buySellData.getStrategyData().getStoplossType())) {
						this.portfolioService.checkStoplossHitDollar(date,
								buySellData.getStrategyData().getSystemType(),
								buySellData.getStrategyData().getStoplossTiming());
					} else if (StaticConfig.stoplossType.get("portfolio")
							.equals(buySellData.getStrategyData().getStoplossType())
							&& buySellData.getStrategyData().getStopLossPct() > 0) {
						this.portfolioService.checkPortfolioStoplossHit(date);
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
				// Capture whether the market-trend rule is TRUE for today specifically.
				// This is independent of buySellData stickiness — buySellData stays set
				// to the last activated regime for position management (exits, stops,
				// max-time still need to fire on inactive days for open positions),
				// but NEW ENTRIES are gated on whether the regime is active TODAY.
				boolean regimeActiveToday = (newTrend != null);
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

					// Patch 72p.4: anchor follows regime swap. Multi-regime strategies
					// can carry different anchors per regime, though the trip flag is
					// sticky once set so a swap mid-trip doesn't restart evaluation.
					this.portfolioService
							.setPortfolioStoplossAnchor(buySellData.getStrategyData().getPortfolioStoplossAnchor());

//					long start = System.nanoTime();
					entryExitMap = strategyBuilderService.signalsForTheDayV1(date, priceData, buySellData,
							this.portfolioService);

					// If exit_timing is "close", execute exits immediately at today's close.
					// NOT gated on regimeActiveToday — open positions need exit management
					// even when the regime gate is off (Python QAS behaviour: existing
					// positions exit via their own signals/stops/max-time regardless of
					// whether the market trend rule is currently passing).
					if (buySellData.getStrategyData().getExitTiming().equals("close")) {
						if (entryExitMap != null && entryExitMap.get("exit") != null
								&& !entryExitMap.get("exit").isEmpty()) {
							ExitSignalsRequestDto exitSignals = ExitSignalsRequestDto.builder().tradeDate(date)
									.exits(entryExitMap.get("exit")).reasonForExit("Exits").exitTime("close").build();
							this.portfolioService.executeExitSignals(exitSignals);
						}
					}

					// If entry_timing is "close" AND order_type is NORMAL, execute entries at
					// today's close.
					// GATED on regimeActiveToday — new entries only fire when the market
					// trend rule is currently TRUE. Without this gate, single-regime
					// strategies (e.g. ROC_SP500 bear-only) keep firing entries on every
					// day after first activation because buySellData stays sticky.
					if (regimeActiveToday && buySellData.getStrategyData().getEntryTiming().equals("close")
							&& buySellData.getStrategyData().getOrderType()
									.equals(StaticConfig.orderType.get("normal"))) {
						processNormalOrders(date, previousDate, entryExitMap, buySellData, "close");
					}

				}

//				long end = System.nanoTime();

//				double elapsedSeconds = (end - start) / 1_000_000_000.0;
//				System.err.printf("⏱️ Signals For the Day: %.3f seconds%n", elapsedSeconds);

				this.portfolioService.updateTradeDayCount(date);

				if (buySellData != null && entryExitMap != null) {
					// Patch 12: in execution mode on the last bar, lift the slot cap so the
					// signal builder produces the FULL ranked candidate list. Middleware
					// splits into PROPOSED (top free_slots) + SUBSTITUTE_POOL (next M per
					// marketregime.substitute_pool_size). Backtest mode unchanged because
					// seedHoldings is null when called from runbacktestv3.
					boolean isLastBarExecMode = (seedHoldings != null)
							&& date.equals(priceData.getAll_dates().get(priceData.getAll_dates().size() - 1));
					if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit_atr"))) {

						Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();

						int targetSec = buySellData.getStrategyData().getSlots() - livePositions.size();
						if (isLastBarExecMode) {
							targetSec = Integer.MAX_VALUE;
						}
						limitOrderMap = new HashMap<>();

						List<LimitOrder> limitOrdersList = new LinkedList<>();
						// Patch 15: ranking row for execution-mode rankingValue field. Null-safe;
						// not all strategies have ranking. Computed once per bar, reused per entry.
						Map<String, Float> rankRow_LA = null;
						if (isLastBarExecMode && buySellData.getStrategyData().getRanking() != null) {
							rankRow_LA = buySellData.getStrategyData().getRanking().getRow(date);
						}
						for (String entry : entryExitMap.get("entry")) {

							if (!livePositions.contains(entry) && targetSec > 0) {

								float atrValue = priceData.getDaily_atr().getValue(date, entry);
								float closePrice = priceData.getDaily_closes().getValue(date, entry);

								float limit_price = closePrice
										- (buySellData.getStrategyData().getLimitPct() * atrValue);
								limit_price = Math.round(limit_price * 100f) / 100f;

								// Patch 15: populate rich fields in execution mode only.
								// Builder pattern: start from minimal (ticker + limitPrice) and
								// conditionally add execution-mode fields. Backtest mode emits the
								// same JSON as before Patch 15 because all extended fields stay null.
								LimitOrder.LimitOrderBuilder lb_LA = LimitOrder.builder().ticker(entry)
										.limitPrice(limit_price);
								if (isLastBarExecMode) {
									float intCap = (float) (buySellData.getStrategyData().getStartingCapital()
											/ buySellData.getStrategyData().getSlots());
									int intQty = (int) Math.floor(intCap / closePrice);
									float stopPct = buySellData.getStrategyData().getStopLossPct();
									float tpPct = buySellData.getStrategyData().getTakeProfitPct();
									String sysType = buySellData.getStrategyData().getSystemType();
									Float stopPrice = null;
									Float tpPrice = null;
									if (StaticConfig.systemType.get("short").equals(sysType)) {
										if (stopPct > 0f)
											stopPrice = closePrice * (1f + stopPct);
										if (tpPct > 0f)
											tpPrice = closePrice * (1f - tpPct);
									} else {
										if (stopPct > 0f)
											stopPrice = closePrice * (1f - stopPct);
										if (tpPct > 0f)
											tpPrice = closePrice * (1f + tpPct);
									}
									Float rv = (rankRow_LA != null) ? rankRow_LA.get(entry) : null;
									lb_LA.direction(sysType).rank(limitOrdersList.size() + 1).intendedQty(intQty)
											.intendedCapital(intCap).referenceClose(closePrice)
											.initialStopPrice(stopPrice).initialTpPrice(tpPrice).rankingValue(rv);
								}
								limitOrdersList.add(lb_LA.build());

								targetSec--;

							}

						}
						limitOrderMap.put("limit_orders", limitOrdersList);
						// Patch 12: capture tomorrow's orders on the last bar in execution mode.
						if (isLastBarExecMode) {
							this.portfolioService.recordProposedOrders(limitOrdersList);
						}

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
						if (isLastBarExecMode) {
							targetSec = Integer.MAX_VALUE;
						}
						limitOrderMap = new HashMap<>();
						List<LimitOrder> limitOrdersList = new LinkedList<>();
						// Patch 15: ranking row for execution-mode rankingValue field. Null-safe.
						Map<String, Float> rankRow_L = null;
						if (isLastBarExecMode && buySellData.getStrategyData().getRanking() != null) {
							rankRow_L = buySellData.getStrategyData().getRanking().getRow(date);
						}
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

								// Patch 15: rich fields populated only in execution mode.
								// Mirrors the LIMIT_ATR branch above so middleware sees a uniform
								// LimitOrder shape regardless of which engine branch produced it.
								LimitOrder.LimitOrderBuilder lb_L = LimitOrder.builder().ticker(entry)
										.limitPrice(limit_price);
								if (isLastBarExecMode) {
									float intCap = (float) (buySellData.getStrategyData().getStartingCapital()
											/ buySellData.getStrategyData().getSlots());
									int intQty = (int) Math.floor(intCap / closePrice);
									float stopPct = buySellData.getStrategyData().getStopLossPct();
									float tpPct = buySellData.getStrategyData().getTakeProfitPct();
									String sysType = buySellData.getStrategyData().getSystemType();
									Float stopPrice = null;
									Float tpPrice = null;
									if (StaticConfig.systemType.get("short").equals(sysType)) {
										if (stopPct > 0f)
											stopPrice = closePrice * (1f + stopPct);
										if (tpPct > 0f)
											tpPrice = closePrice * (1f - tpPct);
									} else {
										if (stopPct > 0f)
											stopPrice = closePrice * (1f - stopPct);
										if (tpPct > 0f)
											tpPrice = closePrice * (1f + tpPct);
									}
									Float rv = (rankRow_L != null) ? rankRow_L.get(entry) : null;
									lb_L.direction(sysType).rank(limitOrdersList.size() + 1).intendedQty(intQty)
											.intendedCapital(intCap).referenceClose(closePrice)
											.initialStopPrice(stopPrice).initialTpPrice(tpPrice).rankingValue(rv);
								}
								limitOrdersList.add(lb_L.build());
								targetSec--;

							}
						}
						limitOrderMap.put("limit_orders", limitOrdersList);
						// Patch 12: capture tomorrow's orders on the last bar in execution mode.
						if (isLastBarExecMode) {
							this.portfolioService.recordProposedOrders(limitOrdersList);
						}

					} else if (buySellData.getStrategyData().getOrderType()
							.equals(StaticConfig.orderType.get("normal"))) {
						// ── Patch 16: NORMAL/MKT proposed orders capture ──
						// Symmetric with the LIMIT_ATR / LIMIT branches above. NORMAL
						// strategies execute at market open/close (no limit price);
						// during backtest, processNormalOrders enters trades immediately.
						// In execution mode the last-bar guard in executeEntrySignals
						// prevents the actual entry, but middleware still needs the
						// ranked candidate list as proposedOrders.
						//
						// Differences vs LIMIT branches:
						// - limitPrice = 0f (no limit; informational only — middleware
						// C2.6 persists as 0 on the tradelist row)
						// - referenceClose = today's close — sizing reference, same as
						// LIMIT branches
						// - initialStopPrice / initialTpPrice both gated on pct>0
						// (so PullBack_X3_Sp500 with stoploss_pct=0 emits null
						// instead of degenerating to closePrice). LIMIT branches
						// above have the same gating-asymmetry pre-existing bug
						// for stopPrice — separate cleanup patch needed there.
						if (isLastBarExecMode) {
							Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();
							List<LimitOrder> mktOrdersList = new LinkedList<>();
							Map<String, Float> rankRow_N = null;
							if (buySellData.getStrategyData().getRanking() != null) {
								rankRow_N = buySellData.getStrategyData().getRanking().getRow(date);
							}
							for (String entry : entryExitMap.get("entry")) {
								if (livePositions.contains(entry)) {
									continue;
								}
								float closePrice = priceData.getDaily_closes().getValue(date, entry);
								float intCap = (float) (buySellData.getStrategyData().getStartingCapital()
										/ buySellData.getStrategyData().getSlots());
								int intQty = (int) Math.floor(intCap / closePrice);
								float stopPct = buySellData.getStrategyData().getStopLossPct();
								float tpPct = buySellData.getStrategyData().getTakeProfitPct();
								String sysType = buySellData.getStrategyData().getSystemType();
								Float stopPrice = null;
								Float tpPrice = null;
								if (StaticConfig.systemType.get("short").equals(sysType)) {
									if (stopPct > 0f)
										stopPrice = closePrice * (1f + stopPct);
									if (tpPct > 0f)
										tpPrice = closePrice * (1f - tpPct);
								} else {
									if (stopPct > 0f)
										stopPrice = closePrice * (1f - stopPct);
									if (tpPct > 0f)
										tpPrice = closePrice * (1f + tpPct);
								}
								Float rv = (rankRow_N != null) ? rankRow_N.get(entry) : null;
								LimitOrder mkt = LimitOrder.builder().ticker(entry).limitPrice(0f).direction(sysType)
										.rank(mktOrdersList.size() + 1).intendedQty(intQty).intendedCapital(intCap)
										.referenceClose(closePrice).initialStopPrice(stopPrice).initialTpPrice(tpPrice)
										.rankingValue(rv).build();
								mktOrdersList.add(mkt);
							}
							this.portfolioService.recordProposedOrders(mktOrdersList);
						}
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
						// Patch 72p.5: wire anchor on lazy init.
						this.portfolioService
								.setPortfolioStoplossAnchor(buySellData.getStrategyData().getPortfolioStoplossAnchor());
					}
				}

			}

		}
		BacktestReponseDto response = this.portfolioService.getPortfolio();
		// Patch 11: in execution mode (seedHoldings != null), attach the orders
		// captured by the skip-last-bar guard. Backtest mode leaves
		// response.proposedOrders null → @JsonInclude(NON_NULL) omits the field.
		if (seedHoldings != null) {
			response.setProposedOrders(this.portfolioService.getLastBarUnfilledOrders());
			response.setActiveRegimeOnLastBar(marketTrendOfDay);
		}
		return response;
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
				String reason = (d.getReason() == null || d.getReason().isBlank()) ? "Volatility Cut"
						: "Volatility Cut: " + d.getReason();
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
				String reason = (d.getReason() == null || d.getReason().isBlank()) ? "Volatility Cut"
						: "Volatility Cut: " + d.getReason();
				this.portfolioServiceImplV2.closeAllPositionsAtClose(date, reason);
				suspended = true;
			}
		}
		return suspended;
	}

	/**
	 * Dispatch arm for LONGSHORT (pair-trading) strategies. Reached only when
	 * buySellData.strategyData.systemType == "LONGSHORT" (mapped from
	 * StaticConfig.systemType.get("long_short")).
	 *
	 * Current scope (Patch 23): day-loop scaffolding with mark-to-market only. No
	 * entries or exits issued yet.
	 *
	 * Phase-2 roadmap — to be built incrementally: - Patch 24: PairProfit +
	 * ForceClose exit processors + partner-close - Patch 25: market trend
	 * evaluation + per-leg entry candidate filter - Patch 26: IBS rank + slice +
	 * RSI carve-outs - Patch 27: PairingService + SizingPolicyResolver + enterTrade
	 * with pairId
	 *
	 * @param priceData   universe prices + trading dates
	 * @param buySellData regime + strategy data (LONGSHORT-specific fields
	 *                    populated by Patch 22a)
	 */
	private BacktestReponseDto runBacktestLongShortV2(PriceDataV2 priceData, BuySellDataV2 buySellData) {
		// Initialise portfolio For LRA strategies,stopLossPct and takeProfitPct
		// are 0 (pair exits via pair_exit_policy, not per-leg stops).
		this.portfolioService.setPriceDate(priceData, buySellData.getStrategyData().getStartingCapital(),
				buySellData.getStrategyData().getSlots(), buySellData.getStrategyData().getStopLossPct(),
				buySellData.getStrategyData().getTakeProfitPct());
		this.portfolioService.setPortfolioStoplossAnchor(buySellData.getStrategyData().getPortfolioStoplossAnchor());

		// Patch 24: precompute trading-date → index for O(1) sessions-held arithmetic.
		List<LocalDate> tradingDates = priceData.getTrading_dates();
		Map<LocalDate, Integer> dateIndex = new HashMap<>();
		for (int i = 0; i < tradingDates.size(); i++) {
			dateIndex.put(tradingDates.get(i), i);
		}

		LocalDate endDate = buySellData.getStrategyData().getEndDate();
		Map<String, Object> pairExitPolicy = buySellData.getStrategyData().getPairExitPolicy();

		for (LocalDate date : tradingDates) {
			if (endDate != null && date.isAfter(endDate)) {
				break;
			}

			this.portfolioService.markToMarket(date);
			this.portfolioService.updateTradeDayCount(date);

			// Patch 24: pair-level exit processing.
			// Profit exits run first (preferred reason), then force-close
			// picks up anything that aged out. Both no-op when policy is null
			// or no live pair positions exist.
			if (pairExitPolicy != null) {
				processPairProfitExits(date, priceData, dateIndex, pairExitPolicy,
						buySellData.getStrategyData().getExitTiming());
				processForceCloseExits(date, priceData, dateIndex, pairExitPolicy,
						buySellData.getStrategyData().getExitTiming());
			}
		}

		return this.portfolioService.getPortfolio();
	}

	/**
	 * Dispatch arm for LONGSHORT multi-regime strategies. Reached via the LONGSHORT
	 * early branch in runBacktestSimpleV2 (multi-regime overload).
	 *
	 * Current scope (Patch 27): init portfolio + day loop with mark-to-market,
	 * active regime selection per day, per-regime pair exit processing, and per-leg
	 * candidate evaluation hook. Real evaluator + pairing + entry land in Patch 28.
	 *
	 * @param priceData         universe prices + trading dates
	 * @param marketTrends      per-date active regime label (from
	 *                          MarketTrendServiceV2)
	 * @param rulesOfDayRegimes regime label -> BuySellData (one per regime)
	 */
	private BacktestReponseDto runBacktestLongShortSimpleV2(PriceDataV2 priceData, Map<LocalDate, String> marketTrends,
			Map<String, BuySellDataV2> rulesOfDayRegimes) {

		// Init portfolio using any regime's data. All regimes share base
		// capital, slots, and end date in a single-strategy multi-regime config.
		LocalDate endDate = null;
		if (rulesOfDayRegimes != null && !rulesOfDayRegimes.isEmpty()) {
			BuySellDataV2 anyRegime = rulesOfDayRegimes.values().iterator().next();
			this.portfolioService.setPriceDate(priceData, anyRegime.getStrategyData().getStartingCapital(),
					anyRegime.getStrategyData().getSlots(), anyRegime.getStrategyData().getStopLossPct(),
					anyRegime.getStrategyData().getTakeProfitPct());
			this.portfolioService.setPortfolioStoplossAnchor(anyRegime.getStrategyData().getPortfolioStoplossAnchor());
			endDate = anyRegime.getStrategyData().getEndDate();
		}

		// Precompute trading-date → index for O(1) sessions-held arithmetic.
		List<LocalDate> tradingDates = priceData.getTrading_dates();
		Map<LocalDate, Integer> dateIndex = new HashMap<>();
		for (int i = 0; i < tradingDates.size(); i++) {
			dateIndex.put(tradingDates.get(i), i);
		}

		// LRA Patch 31: timing-aware day loop mirroring runBacktestSimpleV2's
		// open vs close positioning. "open" timing runs at the TOP of the
		// loop (before markToMarket, uses yesterday's signals → no forward
		// bias); "close" timing runs at the BOTTOM (after markToMarket, uses
		// today's signals).
		//
		// stickyRegime — last activated regime. Continues to govern exit
		// management on inactive days (live pairs still get force-close
		// processing even when no regime is currently passing the market
		// trend rule). Mirrors buySellData stickiness in runBacktestSimpleV2.
		BuySellDataV2 stickyRegime = null;
		List<String> previousLongCandidates = new ArrayList<>();
		List<String> previousShortCandidates = new ArrayList<>();
		// LRA Patch 36: cache yesterday's pre-reduction pools alongside the
		// reduced candidate lists so open-timing entries get backtracking too.
		List<String> previousLongPreReduction = new ArrayList<>();
		List<String> previousShortPreReduction = new ArrayList<>();
		// LRA Patch 32: globally unique pairIds across the backtest run.
		// PairingServiceImpl previously reset to 0 every call, causing
		// expandToPartners to falsely group trades from different days.
		int nextPairId = 0;

		for (LocalDate date : tradingDates) {
			if (endDate != null && date.isAfter(endDate)) {
				break;
			}

			// ──────────────────────────────────────────────────────────────
			// TOP OF LOOP — "open" timing exits + entries
			// State reflects yesterday's close. Execution at today's open.
			// ──────────────────────────────────────────────────────────────
			if (stickyRegime != null) {
				StrategyDataV2 stickySd = stickyRegime.getStrategyData();
				String stickyExitTiming = stickySd.getExitTiming();
				String stickyEntryTiming = stickySd.getEntryTiming();

				if ("open".equals(stickyExitTiming)) {
					Map<String, Object> exitPolicy = stickySd.getPairExitPolicy();
					if (exitPolicy != null) {
						processPairProfitExits(date, priceData, dateIndex, exitPolicy, "open");
						processForceCloseExits(date, priceData, dateIndex, exitPolicy, "open");
					}
				}

				// Open-timing entries use YESTERDAY's signals — prevents
				// forward bias by deciding on previousDate and executing at
				// today's open. Empty caches on day 1 → no entries.
				if ("open".equals(stickyEntryTiming) && !previousLongCandidates.isEmpty()
						&& !previousShortCandidates.isEmpty()) {
					nextPairId = enterPairs(date, stickySd, previousLongCandidates, previousShortCandidates,
							previousLongPreReduction, previousShortPreReduction, priceData, nextPairId);
				}
			}

			// ──────────────────────────────────────────────────────────────
			// MIDDLE — mark to market
			// ──────────────────────────────────────────────────────────────
			this.portfolioService.markToMarket(date);

			// ──────────────────────────────────────────────────────────────
			// REGIME SELECTION + SIGNAL COMPUTATION
			// ──────────────────────────────────────────────────────────────
			String activeRegimeKey = (marketTrends != null) ? marketTrends.get(date) : null;
			boolean regimeActiveToday = (activeRegimeKey != null);
			if (regimeActiveToday) {
				BuySellDataV2 newRegime = rulesOfDayRegimes.get(activeRegimeKey);
				if (newRegime != null) {
					stickyRegime = newRegime;
				}
			}

			// Compute today's per-leg candidates — used for close-timing
			// entries today AND for open-timing entries tomorrow (cached at
			// end of loop iteration). LRA Patch 36 also returns pre-reduction
			// pools for PairingService backtracking.
			List<String> todayLongCandidates = new ArrayList<>();
			List<String> todayShortCandidates = new ArrayList<>();
			List<String> todayLongPreReduction = new ArrayList<>();
			List<String> todayShortPreReduction = new ArrayList<>();
			if (stickyRegime != null && regimeActiveToday) {
				StrategyDataV2 sd = stickyRegime.getStrategyData();
				LegEvaluation longEval = computeLegCandidates(sd.getEntryRulesTreeLong(), date, stickyRegime,
						priceData);
				LegEvaluation shortEval = computeLegCandidates(sd.getEntryRulesTreeShort(), date, stickyRegime,
						priceData);
				todayLongCandidates = longEval.reduced;
				todayShortCandidates = shortEval.reduced;
				todayLongPreReduction = longEval.preReduction;
				todayShortPreReduction = shortEval.preReduction;
			}

			// ──────────────────────────────────────────────────────────────
			// BOTTOM OF LOOP — "close" timing exits + entries
			// State reflects today's close. Execution at today's close.
			// ──────────────────────────────────────────────────────────────
			if (stickyRegime != null) {
				StrategyDataV2 sd = stickyRegime.getStrategyData();
				String exitTiming = sd.getExitTiming();
				String entryTiming = sd.getEntryTiming();

				// Default (non-"open") = close. Exits run even on inactive
				// days so live pairs still get force-close processing.
				if (!"open".equals(exitTiming)) {
					Map<String, Object> exitPolicy = sd.getPairExitPolicy();
					if (exitPolicy != null) {
						processPairProfitExits(date, priceData, dateIndex, exitPolicy, "close");
						processForceCloseExits(date, priceData, dateIndex, exitPolicy, "close");
					}
				}

				// Close-timing entries gated on regime-active-today
				// (mirrors runBacktestSimpleV2's regimeActiveToday gate).
				if (regimeActiveToday && !"open".equals(entryTiming) && !todayLongCandidates.isEmpty()
						&& !todayShortCandidates.isEmpty()) {
					nextPairId = enterPairs(date, sd, todayLongCandidates, todayShortCandidates, todayLongPreReduction,
							todayShortPreReduction, priceData, nextPairId);
				}
			}

			// Session count last — pairs entered today register as session 0,
			// matching computeSessionsHeld's dateIndex arithmetic.
			this.portfolioService.updateTradeDayCount(date);

			previousLongCandidates = todayLongCandidates;
			previousShortCandidates = todayShortCandidates;
			previousLongPreReduction = todayLongPreReduction;
			previousShortPreReduction = todayShortPreReduction;
		}

		return this.portfolioService.getPortfolio();
	}

	// ──────────────────────────────────────────────────────────────────────
	// LRA Patch 28 — per-leg candidate evaluator (recursive rule-tree walker)
	// ──────────────────────────────────────────────────────────────────────

	/**
	 * LRA Patch 36: returns BOTH the reduced (post-top_n_universe slice) and
	 * pre-reduction (everything passing the non-top_n filters) candidate lists.
	 * PairingService backtracking draws from pre-reduction when a candidate pair is
	 * disallowed and a replacement must be found outside the top-N.
	 *
	 * Order is preserved via LinkedHashSet — the top_n_universe leaf inserts
	 * tickers in ranked order (best first), and AND-group intersections retain the
	 * receiver's order, so the final list reflects the IBS ranking.
	 */
	private LegEvaluation computeLegCandidates(Map<String, Object> treeJson, LocalDate date, BuySellDataV2 activeRegime,
			PriceDataV2 priceData) {
		if (treeJson == null) {
			return new LegEvaluation(new ArrayList<>(), new ArrayList<>());
		}
		Set<String> universe = (priceData.getDaily_universes() != null) ? priceData.getDaily_universes().getRow(date)
				: new LinkedHashSet<>();
		if (universe == null || universe.isEmpty()) {
			return new LegEvaluation(new ArrayList<>(), new ArrayList<>());
		}
		Set<String> reduced = evaluateNode(treeJson, date, activeRegime, universe, false);
		Set<String> preReduction = evaluateNode(treeJson, date, activeRegime, universe, true);
		return new LegEvaluation(new ArrayList<>(reduced), new ArrayList<>(preReduction));
	}

	/**
	 * Dispatches a node by its type ("group" or leaf). skipTopN=true makes every
	 * top_n_universe leaf pass-all (used for pre-reduction pool).
	 */
	private Set<String> evaluateNode(Map<String, Object> node, LocalDate date, BuySellDataV2 activeRegime,
			Set<String> universe, boolean skipTopN) {
		if (node == null) {
			return new LinkedHashSet<>(universe); // null = no filter
		}
		String type = (String) node.get("type");
		if ("group".equals(type)) {
			return evaluateGroup(node, date, activeRegime, universe, skipTopN);
		}
		return evaluateLeaf(node, date, activeRegime, universe, skipTopN);
	}

	/**
	 * Combines children's candidate sets via AND (intersection) or OR (union).
	 * LinkedHashSet preserves the order of the first child — important when the
	 * first child is top_n_universe (the ranking carries through the intersection).
	 */
	@SuppressWarnings("unchecked")
	private Set<String> evaluateGroup(Map<String, Object> node, LocalDate date, BuySellDataV2 activeRegime,
			Set<String> universe, boolean skipTopN) {
		String logic = (String) node.get("logic");
		List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
		if (children == null || children.isEmpty()) {
			return new LinkedHashSet<>(universe);
		}
		Set<String> result = null;
		for (Map<String, Object> child : children) {
			Set<String> childResult = evaluateNode(child, date, activeRegime, universe, skipTopN);
			if (result == null) {
				result = new LinkedHashSet<>(childResult);
			} else if ("OR".equalsIgnoreCase(logic)) {
				result.addAll(childResult);
			} else {
				// Default AND
				result.retainAll(childResult);
			}
		}
		return result == null ? new LinkedHashSet<>(universe) : result;
	}

	/**
	 * Leaf dispatcher — top_n_universe is special, everything else is a comparison.
	 * When skipTopN=true, top_n_universe yields the full universe (no ranking
	 * slice) so the AND-group result carries the pre-reduction set forward.
	 */
	private Set<String> evaluateLeaf(Map<String, Object> leaf, LocalDate date, BuySellDataV2 activeRegime,
			Set<String> universe, boolean skipTopN) {
		String operator = (String) leaf.get("operator");
		if ("top_n_universe".equals(operator)) {
			if (skipTopN) {
				return new LinkedHashSet<>(universe); // pre-reduction pass-all
			}
			return evaluateTopNUniverse(leaf, date, activeRegime, universe);
		}
		return evaluateComparison(leaf, date, activeRegime, universe);
	}

	/** Ranks the universe by indicator value and returns the top-N tickers. */
	@SuppressWarnings("unchecked")
	private Set<String> evaluateTopNUniverse(Map<String, Object> leaf, LocalDate date, BuySellDataV2 activeRegime,
			Set<String> universe) {
		String indicator = (String) leaf.get("indicator");
		Map<String, Object> params = (Map<String, Object>) leaf.get("params");
		if (params == null || params.get("N") == null) {
			return new HashSet<>();
		}
		int n = ((Number) params.get("N")).intValue();
		String direction = (String) params.getOrDefault("direction", "asc");
		boolean asc = "asc".equalsIgnoreCase(direction);

		StrategyDataV2 sd = activeRegime.getStrategyData();
		String key = lookupKey(indicator, leaf.get("lookback"));
		ArrowDataFrame frame = (sd.getEntryIndicators() != null) ? sd.getEntryIndicators().get(key) : null;
		if (frame == null) {
			return new HashSet<>();
		}

		// Collect (ticker, value) pairs for tickers with valid data today
		// Collect (ticker, value) pairs for tickers with valid data today
		List<String> tickersOrdered = new ArrayList<>();
		List<Float> values = new ArrayList<>();
		for (String ticker : universe) {
			Float val = frame.getValue(date, ticker);
			if (val == null || Float.isNaN(val)) {
				continue;
			}
			tickersOrdered.add(ticker);
			values.add(val);
		}

		// Indices sorted by value
		Integer[] indices = new Integer[tickersOrdered.size()];
		for (int i = 0; i < indices.length; i++)
			indices[i] = i;
		java.util.Arrays.sort(indices, (a, b) -> asc ? Float.compare(values.get(a), values.get(b))
				: Float.compare(values.get(b), values.get(a)));

		// LRA Patch 36: LinkedHashSet preserves the ranked insertion order
		Set<String> result = new LinkedHashSet<>();
		for (int i = 0; i < Math.min(n, indices.length); i++) {
			result.add(tickersOrdered.get(indices[i]));
		}
		return result;
	}

	/**
	 * Filters the universe by leaf comparison: indicator OP
	 * value-or-classification-field.
	 */
	@SuppressWarnings("unchecked")
	private Set<String> evaluateComparison(Map<String, Object> leaf, LocalDate date, BuySellDataV2 activeRegime,
			Set<String> universe) {
		String indicator = (String) leaf.get("indicator");
		String operator = (String) leaf.get("operator");
		Object valueObj = leaf.get("value");
		String valueIndicator = (String) leaf.get("value_indicator");

		StrategyDataV2 sd = activeRegime.getStrategyData();
		String key = lookupKey(indicator, leaf.get("lookback"));
		ArrowDataFrame frame = (sd.getEntryIndicators() != null) ? sd.getEntryIndicators().get(key) : null;
		if (frame == null) {
			return new HashSet<>();
		}
		Map<String, Object> classification = sd.getTickerClassification();

		// LRA Patch 36: LinkedHashSet preserves universe iteration order
		Set<String> result = new LinkedHashSet<>();
		for (String ticker : universe) {
			Float left = frame.getValue(date, ticker);
			if (left == null || Float.isNaN(left)) {
				continue;
			}
			Float right = null;
			if (valueObj instanceof Number) {
				right = ((Number) valueObj).floatValue();
			} else if (valueIndicator != null && classification != null) {
				Object meta = classification.get(ticker);
				if (meta instanceof Map) {
					Object thr = ((Map<String, Object>) meta).get(valueIndicator);
					if (thr instanceof Number) {
						right = ((Number) thr).floatValue();
					}
				}
			}
			if (right == null) {
				continue;
			}
			if (compareFloats(left, right, operator)) {
				result.add(ticker);
			}
		}
		return result;
	}

	/**
	 * Builds the indicator key in the entryIndicators map: "indicator_lookback".
	 */
	private String lookupKey(String indicator, Object lookback) {
		int lb = (lookback instanceof Number) ? ((Number) lookback).intValue() : 0;
		return indicator + "_" + lb;
	}

	/** Standard float comparison by operator string. */
	private boolean compareFloats(float left, float right, String operator) {
		if (operator == null) {
			return false;
		}
		switch (operator) {
		case ">":
			return left > right;
		case "<":
			return left < right;
		case ">=":
			return left >= right;
		case "<=":
			return left <= right;
		case "==":
			return left == right;
		case "!=":
			return left != right;
		default:
			return false;
		}
	}

	// ──────────────────────────────────────────────────────────────────────
	// LRA Patch 24 helpers — pair exit orchestration
	// ──────────────────────────────────────────────────────────────────────

	/**
	 * Profit-exit step: identify pairs whose combined signed P&L exceeds the
	 * threshold and close both legs at today's close. Reason: "pair_profit".
	 */
	private void processPairProfitExits(LocalDate date, PriceDataV2 priceData, Map<LocalDate, Integer> dateIndex,
			Map<String, Object> pairExitPolicy, String exitTiming) {

		Map<String, TradeLog> liveTrades = collectLiveTrades();
		if (liveTrades.isEmpty()) {
			return;
		}

		PairExitContext ctx = PairExitContext.builder().liveTrades(liveTrades)
				.currentPrices(buildCurrentPrices(priceData, date, liveTrades, exitTiming))
				.sessionsHeldByTradeId(computeSessionsHeld(liveTrades, dateIndex, date)).pairExitPolicy(pairExitPolicy)
				.date(date).build();

		List<String> closures = this.pairProfitExitProcessor.identifyClosures(ctx);
		if (closures.isEmpty()) {
			return;
		}

		// Pair-level integrity: even though the profit processor returns both
		// legs of each profitable pair, expand defensively to cover any case
		// where only one leg shows up (e.g. missing price → partner without P&L).
		Set<String> closuresWithPartners = expandToPartners(new HashSet<>(closures), liveTrades);
		for (String tradeId : closuresWithPartners) {
			closeTradeAt(tradeId, date, priceData, liveTrades, "pair_profit", exitTiming);
		}
	}

	/**
	 * Force-close step: positions that have reached max_hold_sessions close at
	 * today's close. Reason: "force_close". Partners close together for pair
	 * integrity even if their own sessionsHeld < max_hold.
	 */
	private void processForceCloseExits(LocalDate date, PriceDataV2 priceData, Map<LocalDate, Integer> dateIndex,
			Map<String, Object> pairExitPolicy, String exitTiming) {

		Map<String, TradeLog> liveTrades = collectLiveTrades();
		if (liveTrades.isEmpty()) {
			return;
		}

		PairExitContext ctx = PairExitContext.builder().liveTrades(liveTrades)
				.sessionsHeldByTradeId(computeSessionsHeld(liveTrades, dateIndex, date)).pairExitPolicy(pairExitPolicy)
				.date(date).build();

		List<String> closures = this.forceCloseExitProcessor.identifyClosures(ctx);
		if (closures.isEmpty()) {
			return;
		}

		Set<String> closuresWithPartners = expandToPartners(new HashSet<>(closures), liveTrades);
		for (String tradeId : closuresWithPartners) {
			closeTradeAt(tradeId, date, priceData, liveTrades, "force_close", exitTiming);
		}
	}

	/**
	 * Snapshot of currently-open trades (exitDate == null). Built fresh each step
	 * so closures from a previous step are excluded automatically.
	 */
	private Map<String, TradeLog> collectLiveTrades() {
		Map<String, TradeLog> result = new HashMap<>();
		Map<String, TradeLog> all = this.portfolioService.getPortfolio().getTradeLogger();
		if (all == null) {
			return result;
		}
		for (Map.Entry<String, TradeLog> e : all.entrySet()) {
			if (e.getValue() != null && e.getValue().getExitDate() == null) {
				result.put(e.getKey(), e.getValue());
			}
		}
		return result;
	}

	/** Trading sessions between each live trade's entryDate and today. */
	private Map<String, Integer> computeSessionsHeld(Map<String, TradeLog> liveTrades,
			Map<LocalDate, Integer> dateIndex, LocalDate today) {

		Map<String, Integer> result = new HashMap<>();
		Integer todayIdx = dateIndex.get(today);
		if (todayIdx == null) {
			return result;
		}
		for (Map.Entry<String, TradeLog> e : liveTrades.entrySet()) {
			Integer entryIdx = dateIndex.get(e.getValue().getEntryDate());
			if (entryIdx != null) {
				result.put(e.getKey(), todayIdx - entryIdx);
			}
		}
		return result;
	}

	/**
	 * Today's open or close price for each distinct symbol referenced by live
	 * trades. "open" reads daily_opens (pair P&L evaluated at the open for
	 * top-of-loop exit decisions); default ("close" or null) reads daily_closes
	 * (bottom-of-loop exit decisions). Symbols with missing prices (delisted, etc.)
	 * are simply absent.
	 */
	private Map<String, Double> buildCurrentPrices(PriceDataV2 priceData, LocalDate date,
			Map<String, TradeLog> liveTrades, String exitTiming) {

		ArrowDataFrame priceFrame = "open".equals(exitTiming) ? priceData.getDaily_opens()
				: priceData.getDaily_closes();
		Map<String, Double> result = new HashMap<>();
		for (TradeLog t : liveTrades.values()) {
			String symbol = t.getSymbol();
			if (result.containsKey(symbol)) {
				continue;
			}
			Float price = priceFrame.getValue(date, symbol);
			if (price != null) {
				result.put(symbol, price.doubleValue());
			}
		}
		return result;
	}

	/**
	 * Pair-level integrity: for every tradeId in the closure set, add any other
	 * live trade sharing the same pairId. Trades without a pairId are left
	 * untouched (they're single-direction trades that shouldn't be here).
	 */
	private Set<String> expandToPartners(Set<String> closures, Map<String, TradeLog> liveTrades) {
		Set<String> result = new HashSet<>(closures);

		// Collect distinct pairIds present in the closure set
		Set<Integer> closingPairIds = new HashSet<>();
		for (String tradeId : closures) {
			TradeLog t = liveTrades.get(tradeId);
			if (t != null && t.getPairId() != null) {
				closingPairIds.add(t.getPairId());
			}
		}

		// Add any other live trade carrying those pairIds
		for (Map.Entry<String, TradeLog> e : liveTrades.entrySet()) {
			Integer pid = e.getValue().getPairId();
			if (pid != null && closingPairIds.contains(pid)) {
				result.add(e.getKey());
			}
		}
		return result;
	}

	private void closeTradeAt(String tradeId, LocalDate date, PriceDataV2 priceData, Map<String, TradeLog> liveTrades,
			String reason, String exitTiming) {

		TradeLog t = liveTrades.get(tradeId);
		if (t == null) {
			return;
		}
		// Patch 30: honor regime-configured exitTiming. Default to "close"
		// when unspecified (matches the previous hardcoded behaviour).
		ArrowDataFrame exitPriceFrame = "open".equals(exitTiming) ? priceData.getDaily_opens()
				: priceData.getDaily_closes();
		Float exitPrice = exitPriceFrame.getValue(date, t.getSymbol());
		if (exitPrice == null) {
			return;
		}
		TradeExitRequestDto dto = new TradeExitRequestDto();
		dto.setTradeId(tradeId);
		dto.setTradeDate(date);
		dto.setExitPrice(exitPrice);
		dto.setExitReason(reason);
		dto.setPriceUsed((exitTiming != null) ? exitTiming : "close");
		this.portfolioService.exitTrade(dto);
	}

	// ──────────────────────────────────────────────────────────────────────
	// LRA Patch 29 — pair construction + sizing + entry orchestration
	// ──────────────────────────────────────────────────────────────────────

	/**
	 * Returns the updated nextPairId — increment by the number of pairs that were
	 * actually constructed. Callers thread this through the day loop so pairIds
	 * stay globally unique. LRA Patch 32. LRA Patch 36 wires pre-reduction pools.
	 */
	private int enterPairs(LocalDate date, StrategyDataV2 sd, List<String> longCandidates, List<String> shortCandidates,
			List<String> longPreReduction, List<String> shortPreReduction, PriceDataV2 priceData, int nextPairId) {

		PairingContext ctx = PairingContext.builder().longCandidates(longCandidates).shortCandidates(shortCandidates)
				// LRA Patch 36: real pre-reduction pools (everything passing the
				// non-top_n_universe filters). PairingService backtracking can now
				// reach outside the top-N when a candidate pair hits a disallowed combo.
				.preReductionLongPool(longPreReduction).preReductionShortPool(shortPreReduction)
				.pairingRules(sd.getPairingEntryRules()).tickerClassification(asTickerClassificationMap(sd))
				.maxPairs(deriveMaxPairs(sd)).pairIdStart(nextPairId).build();

		List<TradePair> pairs = this.pairingService.constructPairs(ctx);
		if (pairs.isEmpty()) {
			return nextPairId;
		}

		SizingPolicyResolver resolver = pickResolver(sd.getSizingPolicy());
		for (TradePair pair : pairs) {
			enterLeg(date, sd, priceData, pair, "long", resolver);
			enterLeg(date, sd, priceData, pair, "short", resolver);
		}
		return nextPairId + pairs.size();
	}

	/**
	 * Size one leg of a pair via the resolver, compute share quantity, and issue an
	 * enterTrade with pairId stamped. Skips silently on any missing data (entry
	 * price, VIX, etc.).
	 */
	private void enterLeg(LocalDate date, StrategyDataV2 sd, PriceDataV2 priceData, TradePair pair, String legSide,
			SizingPolicyResolver resolver) {

		String symbol = "long".equals(legSide) ? pair.getLongLeg() : pair.getShortLeg();
		if (symbol == null) {
			return;
		}
		// Patch 30: honor regime-configured entryTiming. Default to "close"
		// when the regime didn't specify (matches the previous hardcoded path).
		String entryTiming = sd.getEntryTiming();
		ArrowDataFrame entryPriceFrame = "open".equals(entryTiming) ? priceData.getDaily_opens()
				: priceData.getDaily_closes();
		Float entryPrice = entryPriceFrame.getValue(date, symbol);
		if (entryPrice == null || entryPrice <= 0f || Float.isNaN(entryPrice)) {
			return;
		}

		// Patch 33: conditional indicator is no longer hardcoded — name comes
		// from sizing_policy.params.conditional_on. Field on SizingContext is
		// still called vixClose since FixedDollarPerLegResolver consumes it
		// that way; rename can land in a future cleanup.
		Double conditionalValue = lookupSizingConditional(date, sd);
		SizingContext sctx = SizingContext.builder().pair(pair).legSide(legSide).date(date).policy(sd.getSizingPolicy())
				.tickerClassification(asTickerClassificationMap(sd)).vixClose(conditionalValue)
				.capital((double) sd.getStartingCapital()).slots(sd.getSlots()).build();

		Double notional;
		try {
			notional = resolver.resolve(sctx);
		} catch (Exception e) {
			// Resolver threw (missing VIX, invalid policy band, etc.). Skip.
			return;
		}
		if (notional == null || notional <= 0d) {
			return;
		}

		int quantity = (int) (notional / entryPrice);
		if (quantity <= 0) {
			return;
		}

		String direction = "long".equals(legSide) ? "LONG" : "SHORT";
		TradeEnterRequestDto dto = new TradeEnterRequestDto();
		dto.setTicker(symbol);
		dto.setTradeDate(date);
		dto.setDirection(direction);
		dto.setQuantity(quantity);
		dto.setReason("pair_entry");
		// Patch 30: timing string mirrors the regime's entry_timing config.
		String timingStr = (entryTiming != null) ? entryTiming : "close";
		dto.setPriceUsed(timingStr);
		dto.setEntryTiming(timingStr);
		dto.setEntryprice(entryPrice);
		dto.setCapital(notional.intValue());
		dto.setPairId(pair.getPairId());
		this.portfolioService.enterTrade(dto);
	}

	/**
	 * Pairing slot capacity: (slots − liveCount) / 2 pairs. Subtracts already-live
	 * positions so the strategy can't over-allocate when prior pairs are still
	 * held. Mirrors runBacktestSimpleV2's targetSec = slots − livePositions.size().
	 * Returns 0 when capacity is exhausted (PairingService will form no pairs).
	 *
	 * LRA Patch 32.
	 */
	private int deriveMaxPairs(StrategyDataV2 sd) {
		int liveCount = this.portfolioService.getLiveHoldingsLogger().size();
		int remainingSlots = Math.max(0, sd.getSlots() - liveCount);
		return remainingSlots / 2;
	}

	/**
	 * Convert tickerClassification from Map&lt;String, Object&gt; (Jackson default)
	 * to Map&lt;String, Map&lt;String, Object&gt;&gt; — the shape consumed by
	 * PairingContext and SizingContext.
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Map<String, Object>> asTickerClassificationMap(StrategyDataV2 sd) {
		Map<String, Object> raw = sd.getTickerClassification();
		if (raw == null) {
			return null;
		}
		Map<String, Map<String, Object>> result = new HashMap<>();
		for (Map.Entry<String, Object> e : raw.entrySet()) {
			if (e.getValue() instanceof Map) {
				result.put(e.getKey(), (Map<String, Object>) e.getValue());
			}
		}
		return result;
	}

	/** Look up the sizing resolver implementation by the policy's "mode" key. */
	private SizingPolicyResolver pickResolver(Map<String, Object> sizingPolicy) {
		if (sizingPolicy != null) {
			String mode = (String) sizingPolicy.get("mode");
			if (FixedDollarPerLegResolver.MODE.equals(mode)) {
				return new FixedDollarPerLegResolver();
			}
		}
		// Default: capital / slots
		return new CapitalDivSlotsResolver();
	}

	/**
	 * Read today's value of the sizing policy's conditional indicator.
	 *
	 * Indicator name comes from sizing_policy.params.conditional_on (the same field
	 * FixedDollarPerLegResolver consumes for band selection). Lookback defaults to
	 * 0 and can be overridden via params.conditional_on_lookback. Returns null when
	 * the policy doesn't specify a conditional, when the indicator wasn't loaded,
	 * or when there's no value for today — the resolver then either uses a sensible
	 * default or throws, and the catch site in enterLeg silently skips the leg.
	 *
	 * LRA Patch 33 — was lookupVixClose, hardcoded to "vix_close_0".
	 */
	@SuppressWarnings("unchecked")
	private Double lookupSizingConditional(LocalDate date, StrategyDataV2 sd) {
		if (sd.getEntryIndicators() == null) {
			return null;
		}
		Map<String, Object> policy = sd.getSizingPolicy();
		if (policy == null) {
			return null;
		}
		Map<String, Object> params = (Map<String, Object>) policy.get("params");
		if (params == null) {
			return null;
		}
		Object conditionalOn = params.get("conditional_on");
		if (!(conditionalOn instanceof String) || ((String) conditionalOn).isEmpty()) {
			return null;
		}
		String indicatorName = (String) conditionalOn;
		int lookback = 0;
		Object lb = params.get("conditional_on_lookback");
		if (lb instanceof Number) {
			lookback = ((Number) lb).intValue();
		}
		String key = indicatorName + "_" + lookback;
		ArrowDataFrame frame = sd.getEntryIndicators().get(key);
		if (frame == null) {
			return null;
		}
		Map<String, Float> row = frame.getRow(date);
		if (row == null || row.isEmpty()) {
			return null;
		}
		Float val = row.values().iterator().next();
		if (val == null || Float.isNaN(val)) {
			return null;
		}
		return val.doubleValue();
	}

	/**
	 * LRA Patch 36: small holder for a leg's two candidate sets returned by
	 * computeLegCandidates. reduced is the post-top_n_universe slice the dispatch
	 * arm uses to construct pairs; preReduction is everything passing the non-top_n
	 * filters, used by PairingService backtracking when a candidate pair hits a
	 * disallowed combo and needs a replacement.
	 */
	private static final class LegEvaluation {
		final List<String> reduced;
		final List<String> preReduction;

		LegEvaluation(List<String> reduced, List<String> preReduction) {
			this.reduced = reduced;
			this.preReduction = preReduction;
		}
	}

}