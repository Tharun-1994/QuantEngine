package com.backtest.engine.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;

import com.backtest.engine.dto.request.TdomFilterDto; // Patch 148
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.backtest.engine.context.BacktestContext;
import com.backtest.engine.context.BacktestContextFactory;
import com.backtest.engine.dto.request.ExecutionStepRequestDto;
import com.backtest.engine.dto.request.LiveHoldingsSeedDto;
import com.backtest.engine.dto.request.StrategyBucketRequestDto;
import com.backtest.engine.dto.response.ProposedEntryDto;
import com.backtest.engine.dto.response.ProposedExitDto;
import com.backtest.engine.dto.response.SingleBarSignalsResponseDto;
import com.backtest.engine.dto.response.StopUpdateDto;
import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.entity.StrategyDataV2;
import com.backtest.engine.service.MarketTrendServiceV2;
import com.backtest.engine.service.PortfolioServiceV2;
import com.backtest.engine.service.PriceDataLoaderService;
import com.backtest.engine.service.SingleBarEvaluator;
import com.backtest.engine.service.StrategyBuilderServiceV2;

/**
 * Patch 30: single-bar evaluator — Phase B architecture.
 *
 * Flow: 1. Open BacktestContext (one-shot, mirrors /api/execution/step/single)
 * 2. Load priceData + per-regime buySellData via the simple-branch loaders 3.
 * Identify last bar = priceData.trading_dates[last] 4. Resolve active regime on
 * last bar (synthesize for "Normal" strategies with empty
 * market_trend_rules_tree — same pattern as Patch 19) 5. Initialise
 * portfolioService (setPriceDate) and seed LIVE holdings 6. Call
 * signalsForTheDayV1(lastBar, ...) — the SAME method the day-loop uses for
 * every date; reuse guarantees rule-evaluation parity 7. Wrap ranked entries
 * into ProposedEntryDto list (full ranked list — middleware splits into
 * PROPOSED + SUBSTITUTE_POOL using its DB knowledge of
 * marketregime.substitute_pool_size) 8. Map exit tickers → ProposedExitDto via
 * LIVE-holdings lookup 9. Compute stop value for non-exiting LIVE positions →
 * stopUpdates
 *
 * NOT touched here: - Backtest day-loop (runBacktestV2, runBacktestSimpleV2):
 * completely unchanged. Engine startup binds this new service in PARALLEL — the
 * existing /api/execution/step/single keeps working until middleware cuts over.
 * - LIMIT / LIMIT_ATR order-type math: limitPrice / stopPrice null on
 * ProposedEntryDto for now. PullBack uses NORMAL/MKT so this is a non-issue for
 * Phase 1. - ATR-based stop recompute: computeStopValue handles pct + D3
 * override only. ATR stops will need a follow-up patch once the day-loop's
 * stoplossHitLongAtr/ShortAtr math is extracted from its side-effects.
 */
@Service
public class SingleBarEvaluatorImpl implements SingleBarEvaluator {

	@Autowired
	private BacktestContextFactory backtestContextFactory;
	@Autowired
	private PriceDataLoaderService priceDataService;
	@Autowired
	private StrategyBuilderServiceV2 strategyBuilderServiceV2;
	@Autowired
	private MarketTrendServiceV2 marketTrendServiceV2;
	@Autowired
	private PortfolioServiceV2 portfolioService;
	// Patch 160: vol-filter threshold seeding reuses the day-loop's own
	// method (BacktestServiceImplV2.computeVolThresholds, made public) —
	// no cycle: that service never references this one.
	@Autowired
	private BacktestServiceImplV2 backtestServiceImplV2;

	@Value("${backtest.data.path}")
	private String backtestDataPath;

	@Override
	public SingleBarSignalsResponseDto evaluate(ExecutionStepRequestDto request) {
		StrategyBucketRequestDto strategy = request.getStrategy();

		try (BacktestContext context = this.backtestContextFactory.create(this.priceDataService,
				this.strategyBuilderServiceV2, this.marketTrendServiceV2)) {

			context.setExecutionDataRoot(request.getDataRoot());

			// ── 1. Load price data and per-regime BuySellData ────────────────
			PriceDataV2 priceData = context.getSimplePriceData(strategy, backtestDataPath);
			Map<String, BuySellDataV2> regimeSignals = context.getSimpleBuySellDataMap(strategy, priceData,
					backtestDataPath);

			// ── 2. Identify last bar = data date ─────────────────────────────
			List<LocalDate> tradingDates = priceData.getTrading_dates();
			if (tradingDates == null || tradingDates.isEmpty()) {
				throw new IllegalStateException("priceData.trading_dates is empty for strategy " + strategy.getName());
			}
			LocalDate lastBar = tradingDates.get(tradingDates.size() - 1);

			// ── 3. Resolve active regime on last bar ─────────────────────────
			Map<LocalDate, String> marketTrends;
			if ("normal".equalsIgnoreCase(strategy.getMarketRegimeType())) {
				if (regimeSignals.isEmpty()) {
					throw new IllegalStateException(
							"Normal strategy " + strategy.getName() + " produced no regime signals");
				}
				String soleLabel = regimeSignals.keySet().iterator().next();
				marketTrends = new HashMap<>();
				for (LocalDate d : priceData.getAll_dates()) {
					marketTrends.put(d, soleLabel);
				}
			} else {
				marketTrends = context.getMarketTrends(strategy, priceData, backtestDataPath);
			}

			String activeRegime = marketTrends.get(lastBar);
			if (activeRegime == null) {
				// Regime gate off on last bar → no signals, no actions
				System.err.println("[single-bar] " + strategy.getName() + " lastBar=" + lastBar
						+ " — no active regime, returning empty response");
				return SingleBarSignalsResponseDto.builder().dataDate(lastBar).runDate(request.getRunDate())
						.activeRegimeOnLastBar(null).proposedEntries(new ArrayList<>()).proposedExits(new ArrayList<>())
						.stopUpdates(new ArrayList<>()).build();
			}

			BuySellDataV2 buySellData = regimeSignals.get(activeRegime);
			if (buySellData == null) {
				throw new IllegalStateException("Active regime '" + activeRegime + "' not in regimeSignals map");
			}
			StrategyDataV2 sd = buySellData.getStrategyData();

			// Patch 148: execution-path TDOM / banned-month gate, tested on the
			// INTENDED trade date. The backtest applies these bans on the ENTRY
			// day inside the day loop (BacktestServiceImplV2:478-483); the
			// single-bar path must therefore test the day the orders will
			// actually trade — request.runDate, which middleware sets to
			// next_trading_day(dataDate) (payload_builder step 4) — NOT lastBar.
			// A Friday run has runDate=Monday, so weekday-0 filters empty the
			// proposed-entry list. Exits and stop updates are NOT gated (the
			// backtest only gates the entry block).
			boolean entriesBanned = false;
			String banReason = null;
			LocalDate intendedTradeDate = request.getRunDate();
			if (intendedTradeDate == null) {
				System.err.println("[single-bar] " + strategy.getName()
						+ " request.runDate missing — tdom/banned-month gate SKIPPED");
			} else {
				List<Integer> strategyBannedMonths = sd.getBannedMonths();
				if (strategyBannedMonths != null
						&& strategyBannedMonths.contains(intendedTradeDate.getMonthValue())) {
					entriesBanned = true;
					banReason = "banned_months contains month "
							+ intendedTradeDate.getMonthValue();
				}
				if (!entriesBanned && sd.getTdomFilters() != null) {
					// Patch 173: numbered-TDOM support for the execution path.
					// runDate = next_trading_day(lastBar) by construction
					// (payload_builder step 4), so its trading-day-of-month is
					// exactly: same month as lastBar -> tdom(lastBar)+1, else 1
					// (first session of a new month). tdom(lastBar) comes from
					// the SAME public computeTdomMap the vol seeding uses, so
					// backtest and execution share one calendar definition.
					Integer tdomOfRunDate = null;
					for (TdomFilterDto f : sd.getTdomFilters()) {
						if (f == null)
							continue;
						if (f.getTdom() != null) {
							if (tdomOfRunDate == null) {
								List<LocalDate> allDatesBan = priceData.getAll_dates();
								LocalDate lastBarBan = allDatesBan.get(allDatesBan.size() - 1);
								Map<LocalDate, Integer> tdomMapBan = backtestServiceImplV2
										.computeTdomMap(allDatesBan);
								Integer lastBarTdom = tdomMapBan.get(lastBarBan);
								if (lastBarTdom == null) {
									throw new IllegalStateException("[single-bar] "
											+ strategy.getName() + ": computeTdomMap has no entry"
											+ " for lastBar " + lastBarBan
											+ " — cannot derive tdom of runDate.");
								}
								tdomOfRunDate = (intendedTradeDate.getMonthValue() == lastBarBan.getMonthValue()
										&& intendedTradeDate.getYear() == lastBarBan.getYear())
												? lastBarTdom + 1 : 1;
								System.err.println("[single-bar] " + strategy.getName()
										+ " tdom(" + intendedTradeDate + ") = " + tdomOfRunDate
										+ " (lastBar " + lastBarBan + " tdom=" + lastBarTdom + ")");
							}
							// Mirrors BacktestServiceImplV2.isTdomBlocked (:237-239):
							// month in filter.bannedMonths AND filter.tdom == tdom(date)
							if (f.getBannedMonths() != null
									&& f.getBannedMonths().contains(intendedTradeDate.getMonthValue())
									&& f.getTdom().intValue() == tdomOfRunDate.intValue()) {
								entriesBanned = true;
								banReason = "tdom_filter tdom=" + f.getTdom()
										+ " month=" + intendedTradeDate.getMonthValue();
								break;
							}
							continue;
						}
						// Mirrors BacktestServiceImplV2 semantics: the filter applies
						// only when the month is in ITS bannedMonths list AND the
						// weekday matches. Python weekday 0=Mon..4=Fri; Java
						// DayOfWeek 1=Mon..7=Sun, hence the -1.
						boolean monthApplies = f.getBannedMonths() != null
								&& f.getBannedMonths().contains(intendedTradeDate.getMonthValue());
						boolean weekdayMatches = f.getWeekday() != null
								&& (intendedTradeDate.getDayOfWeek().getValue() - 1) == f.getWeekday();
						if (monthApplies && weekdayMatches) {
							entriesBanned = true;
							banReason = "tdom_filter weekday=" + f.getWeekday()
									+ " month=" + intendedTradeDate.getMonthValue();
							break;
						}
					}
				}
			}

			// Patch 160: vol-filter threshold seeding — the ONE rule class the
			// single-bar path lacked (thresholds stayed 0 → the StrategyBuilder
			// gate at ~:1002 never engaged → candidates were vol-UNfiltered).
			// The day-loop recalibrates at each trigger (BacktestServiceImplV2:553
			// → computeVolThresholds: first trading day OR triggerMonth/Tdom,
			// SPY-vs-SMA(prev) branch, percentile over the ACTIVE universe's
			// avg volume/turnover on prev). Replay that EXACT method over every
			// trading date up to lastBar — off-trigger dates no-op inside it —
			// so the thresholds signalsForTheDayV1 reads below are
			// identical-by-construction to what the same date sees in a
			// backtest. Per-date active regime is honoured for multi-regime
			// strategies via marketTrends.
			// Patch 185: safety-net gate at execution. Replays the SAME
			// open/close state machine the backtest day-loop runs
			// (dispatchSafetyNetsAtOpen/AtClose; suspended gates ENTRIES only,
			// mirroring BSIv2 :463) through lastBar, then evaluates the
			// intended trade date's open using data <= lastBar (no forward
			// bias). Policies self-load spy_close_0 / spy_rolling_vol_close_N
			// frames; if absent they log a loud no-op and never suspend.
			// Patch 185b: EXPLICIT opt-in only. resolveSafetyNets has a legacy
			// fallback (safety_net_type='simple' synthesizes a policy); running
			// that at execution would silently change books that never asked.
			// The gate activates ONLY for strategies whose regime carries an
			// explicit safety_nets list (e.g. Lsmr_static). Everything else --
			// CRDT shorts, QAS, PullBack, all combined members -- skips this
			// entire block, log line included: zero change to existing books.
			java.util.List<com.backtest.engine.dto.request.SafetyNetItemDto> explicitNets185 = strategy
					.getRegimes().get(0).getSafetyNets();
			java.util.List<com.backtest.engine.service.safetynet.SafetyNetPolicy> safetyPolicies185 = (explicitNets185 != null
					&& !explicitNets185.isEmpty())
							? context.buildSafetyPolicies(strategy, priceData, backtestDataPath)
							: java.util.Collections.emptyList();
			if (!safetyPolicies185.isEmpty()) {
				boolean suspended185 = false;
				LocalDate prev185 = null;
				for (LocalDate d185 : priceData.getAll_dates()) {
					suspended185 = backtestServiceImplV2.dispatchSafetyNetsAtOpen(safetyPolicies185, d185, prev185,
							suspended185, priceData, false); // Patch 185e: state-only
					suspended185 = backtestServiceImplV2.dispatchSafetyNetsAtClose(safetyPolicies185, d185, suspended185, false); // Patch 185e
					prev185 = d185;
				}
				boolean intendedSuspended185 = backtestServiceImplV2.dispatchSafetyNetsAtOpen(safetyPolicies185,
						intendedTradeDate, lastBar, suspended185, priceData, false); // Patch 185e
				System.err.println("[single-bar] " + strategy.getName() + " safety-net: policies="
						+ safetyPolicies185.size() + " suspendedThroughLastBar=" + suspended185
						+ " intended(" + intendedTradeDate + ")=" + intendedSuspended185);
				if (intendedSuspended185 && !entriesBanned) {
					entriesBanned = true;
					banReason = "safety_net suspended (state through " + lastBar + ")";
				}
			}
			Map<LocalDate, Integer> tdomMapForVol = backtestServiceImplV2
					.computeTdomMap(priceData.getAll_dates());
			for (int di = 0; di < tradingDates.size(); di++) {
				LocalDate volDate = tradingDates.get(di);
				if (volDate.isAfter(lastBar))
					break;
				String volLabel = marketTrends.get(volDate);
				if (volLabel == null)
					continue;
				BuySellDataV2 volBsd = regimeSignals.get(volLabel);
				if (volBsd == null)
					continue;
				LocalDate volPrev = (di > 0) ? tradingDates.get(di - 1) : null;
				backtestServiceImplV2.computeVolThresholds(volDate, volPrev,
						volBsd, priceData, tdomMapForVol);
			}
			System.err.println("[single-bar] " + strategy.getName()
					+ " vol thresholds seeded through " + lastBar + ": vol="
					+ sd.getVolThreshold() + " turnover="
					+ sd.getTurnoverThreshold() + " (regime '" + activeRegime
					+ "')");
			// Patch 164: name the silent guard. computeVolThresholds returns
			// without setting anything when its inputs are missing — the
			// evidence run showed vol=0.0/turnover=0.0 with no explanation.
			// If the filter is enabled and thresholds stayed unset, say
			// exactly which input is null and where its file lives.
			if (sd.getVolFilter() != null && sd.getVolFilter().isEnabled()
					&& (sd.getVolThreshold() <= 0f)
					&& ( sd.getTurnoverThreshold() <= 0f)) {
				System.err.println("[single-bar] " + strategy.getName()
						+ " VOL SEEDING INEFFECTIVE — filter is enabled but"
						+ " thresholds stayed 0 (vol filter INERT)."
						+ " Inputs present: avgVolume=" + (sd.getAvgVolume() != null)
						+ " avgTurnover=" + (sd.getAvgTurnover() != null)
						+ " spyCloses=" + (sd.getSpyCloses() != null)
						+ " — the loader expects avg_volume.parquet,"
						+ " avg_turnover.parquet, closes_spy.parquet in the"
						+ " exec universe folder (PriceLoader volFilterEnabled"
						+ " path). A false above = that file failed to load"
						+ " or was never generated.");
			}

			// ── 4. Initialise portfolioService + seed LIVE holdings ──────────
			float capitalForExecution = (sd.getProductionCapital() != null) ? sd.getProductionCapital()
					: sd.getStartingCapital();
			this.portfolioService.setPriceDate(priceData, capitalForExecution, sd.getSlots(), sd.getStopLossPct(),
					sd.getTakeProfitPct());

			List<LiveHoldingsSeedDto> liveHoldings = request.getLiveHoldings();
			if (liveHoldings == null) {
				liveHoldings = new ArrayList<>();
			}
			if (!liveHoldings.isEmpty()) {
				this.portfolioService.seedLiveHoldings(liveHoldings);
			}

			// ── 5. Evaluate rules on last bar (same call the day-loop makes) ─
			Map<String, List<String>> entryExitMap = this.strategyBuilderServiceV2.signalsForTheDayV1(lastBar,
					priceData, buySellData, this.portfolioService);
			List<String> rankedEntries = entryExitMap.getOrDefault("entry", new ArrayList<>());
			if (entriesBanned) {
				// Patch 148: intended trade date is banned — propose no entries.
				System.err.println("[single-bar] " + strategy.getName()
						+ " intended trade date " + intendedTradeDate + " is BANNED ("
						+ banReason + ") — proposedEntries emptied; exits/stop"
						+ " updates unaffected.");
				rankedEntries = new ArrayList<>();
			}
			List<String> exitTickers = entryExitMap.getOrDefault("exit", new ArrayList<>());
			// ── 6. Build ranked entry list (FULL list — middleware splits) ───

			// ── 6. Build ranked entry list (FULL list — middleware splits) ───
			// Matches existing BacktestReponseDto.proposedOrders contract:
			// engine emits all sector-capped + dup-filtered + ranked
			// candidates; middleware reads marketregime.substitute_pool_size
			// and splits into PROPOSED (top N=slots) + SUBSTITUTE_POOL
			// (next M=substitute_pool_size).
			int slots = sd.getSlots();
			String direction = resolveDirection(strategy.getSystemType());

			Map<String, Float> rankRow = (sd.getRanking() != null) ? sd.getRanking().getRow(lastBar) : null;
			Map<String, Float> lastCloses = priceData.getDaily_closes().getRow(lastBar);

			float perSlotCapital = capitalForExecution / (float) Math.max(slots, 1); // Patch 50

			// Resolve order type once — same for all entries in this regime.
			String orderType = sd.getOrderType() != null ? sd.getOrderType().toUpperCase() : "NORMAL";
			boolean isLimit = "LIMIT".equals(orderType);
			boolean isLimitAtr = "LIMIT_ATR".equals(orderType);
			boolean isLimitHv = "LIMIT_HV".equals(orderType);   // Patch 167 v2

			// ATR frame for last bar — used for LIMIT_ATR entry price and ATR_BASED stop.
			// Loaded from atr_stp parquet via RegimeOverlay in BacktestContext.
			// Null for NORMAL strategies (no ATR parquet loaded).
			Map<String, Float> lastAtr = (sd.getDailyAtr() != null) ? sd.getDailyAtr().getRow(lastBar) : null;
			// Patch 167 v2: HV frame for LIMIT_HV (fixed-name hv_limit parquet)
			Map<String, Float> lastHvLimit = (sd.getHvLimit() != null) ? sd.getHvLimit().getRow(lastBar) : null;

			List<ProposedEntryDto> entries = new ArrayList<>();
			for (int i = 0; i < rankedEntries.size(); i++) {
				String ticker = rankedEntries.get(i);
				Float close = (lastCloses != null) ? lastCloses.get(ticker) : null;
				Float score = (rankRow != null) ? rankRow.get(ticker) : null;
				String sector = (sd.getSectorMap() != null) ? sd.getSectorMap().getOrDefault(ticker, null) : null;

				// ── Limit price ──────────────────────────────────────────────
				// NORMAL: limitPrice = null (MKT order, no limit price)
				// LIMIT: limitPrice = lastClose × (1 - limitPct/100)
				// LIMIT_ATR: limitPrice = lastClose - (atr × atrLimitLookback)
				Float limitPrice = null;
				if (close != null && close > 0f) {
					if (isLimit && sd.getLimitPct() > 0f) {
						// Patch 180: direction-aware. SHORT sells a rally: limit ABOVE
						// close (legacy close x (1 + pct)); below-close sell-limits are
						// marketable, i.e. accidental market orders.
						limitPrice = "SHORT".equalsIgnoreCase(direction)
								? close * (1f + sd.getLimitPct() / 100f)
								: close * (1f - sd.getLimitPct() / 100f);
					} else if (isLimitAtr && sd.getAtrLimitLookback() > 0) {
						Float atr = (lastAtr != null) ? lastAtr.get(ticker) : null;
						if (atr != null && atr > 0f) {
							// Patch 180: direction-aware (and the accidental double-assign
							// from Patch 90 normalized).
							limitPrice = round2("SHORT".equalsIgnoreCase(direction)
									? close + (sd.getLimitPct() * atr)
									: close - (sd.getLimitPct() * atr)); // Patch 90:
																								// round2(close −
																								// limitPct×ATR)
						}
					} else if (isLimitHv) {
						// Patch 167 v2: pct = clamp(HV/divider, lower, upper)/100 x reduction.
						// Direction-aware from birth: SHORT limit ABOVE close.
						Float hvV = (lastHvLimit != null) ? lastHvLimit.get(ticker) : null;
						if (hvV != null && hvV > 0f && sd.getHvLimitDivider() > 0f) {
							float pctHv = hvV / sd.getHvLimitDivider();
							pctHv = Math.max(sd.getHvLimitLower(), Math.min(sd.getHvLimitUpper(), pctHv));
							pctHv = pctHv / 100f * sd.getHvLimitReduction();
							limitPrice = round2("SHORT".equalsIgnoreCase(direction)
									? close * (1f + pctHv)
									: close * (1f - pctHv));
						}
					}
				}
				

				// ── Stop price (initial bracket stop at proposal time) ────────
				// Only computed when limitPrice is known (LIMIT / LIMIT_ATR).
				// NORMAL: stopPrice = null — no entry price known yet at proposal time.
				// PCT: stopPrice = limitPrice × (1 - stoplossPct/100)
				// ATR_BASED: stopPrice = limitPrice - (stoplossPct × atr)
				Float stopPrice = null;
				if (limitPrice != null && limitPrice > 0f) {
					String stoplossType = sd.getStoplossType();
					if (sd.getStopLossPct() > 0f && !"ATR_BASED".equals(stoplossType)) {
						// PCT (NORMAL stoploss type or null)
						// Patch 180: a SHORT loses when price RISES -- stop ABOVE entry.
						stopPrice = "SHORT".equalsIgnoreCase(direction)
								? limitPrice * (1f + sd.getStopLossPct() / 100f)
								: limitPrice * (1f - sd.getStopLossPct() / 100f);
					} else if ("ATR_BASED".equals(stoplossType) && sd.getAtrLookbackStp() > 0) {
						Float atr = (lastAtr != null) ? lastAtr.get(ticker) : null;
						if (atr != null && atr > 0f) {
							float slOffset = round2(sd.getStopLossPct() * atr); // Patch 90: double-round like backtest
							// Patch 99: cap stop offset at stoplossMaxPct% of limit (legacy 'Temp Fix
							// Vas').
							// TP offset stays UNCAPPED — legacy derives it from the pre-cap stoploss
							// amount.
							if (sd.getStoplossMaxPct() > 0f) {
								float maxOffset = round2(limitPrice * sd.getStoplossMaxPct() / 100f);
								if (slOffset > maxOffset) {
									slOffset = maxOffset;
								}
							}
							// Patch 180: offset ADDS for SHORT (stop above entry).
							stopPrice = round2("SHORT".equalsIgnoreCase(direction)
									? limitPrice + slOffset
									: limitPrice - slOffset);
						}
					}
				}

				// ── Take-profit price (initial bracket TP at proposal time) ──
				// Patch 90. Mirrors PortfolioServiceImplV2.takeProfitHitLongAtr:
				// ATR_BASED: tp = round2(limit + round2(takeProfitPct × stopLossPct × atr))
				// PCT: tp = limit × (1 + takeProfitPct/100)
				// lastAtr = ATR at atrLookbackStp — one ATR parquet feeds stop+TP,
				// correct while atrLookbackTp == atrLookbackStp. LONG only.
				Float tpPrice = null;
				if (limitPrice != null && limitPrice > 0f) {
					String tpType = sd.getTakeprofitType();
					if (sd.getTakeProfitPct() > 0f && !"ATR_BASED".equals(tpType)) {
						// Patch 180: a SHORT takes profit when price FALLS -- TP below.
						tpPrice = round2("SHORT".equalsIgnoreCase(direction)
								? limitPrice * (1f - sd.getTakeProfitPct() / 100f)
								: limitPrice * (1f + sd.getTakeProfitPct() / 100f));
					} else if ("ATR_BASED".equals(tpType) && sd.getAtrLookbackStp() > 0) {
						Float atr = (lastAtr != null) ? lastAtr.get(ticker) : null;
						if (atr != null && atr > 0f) {
							tpPrice = round2("SHORT".equalsIgnoreCase(direction)
									? limitPrice - round2(sd.getTakeProfitPct() * sd.getStopLossPct() * atr)
									: limitPrice + round2(sd.getTakeProfitPct() * sd.getStopLossPct() * atr));
						}
					}
				}

				// ── Quantity: always sized off lastClose (confirmed from backtest) ─
				int qty = 0;
				if (close != null && close > 0f) {
					qty = (int) Math.floor(perSlotCapital / close);
				}
				float capitalEst = (close != null) ? (qty * close) : 0f;

				ProposedEntryDto entry = ProposedEntryDto.builder().symbol(ticker).direction(direction)
						.orderType(sd.getOrderType()).entryDate(request.getRunDate()).entryTiming(sd.getEntryTiming())
						.entryReason("entry rule fired on " + lastBar).quantity(qty).capital(capitalEst).sector(sector)
						.rank(i + 1).score(score).limitPrice(limitPrice) // null for NORMAL, computed for
																			// LIMIT/LIMIT_ATR
						.stopPrice(stopPrice) // null for NORMAL, computed for LIMIT when stoploss set
						.tpPrice(tpPrice) // Patch 90: null for NORMAL, computed for LIMIT_ATR/LIMIT
						.build();

				entries.add(entry);
			}

			// ── 7. Proposed exits — for each LIVE holding whose symbol is in exit set ─
			Set<String> exitSymbols = new HashSet<>(exitTickers);
			List<ProposedExitDto> proposedExits = new ArrayList<>();
			for (LiveHoldingsSeedDto h : liveHoldings) {
				if (exitSymbols.contains(h.getSymbol())) {
					proposedExits.add(ProposedExitDto.builder().tradeId(h.getTradeId()).symbol(h.getSymbol())
							.exitReason("exit rule fired on " + lastBar).exitDate(request.getRunDate())
							.exitTiming(sd.getExitTiming()).build());
				}
			}

			// ── 7.5 Patch 79: Max-time exits (execution path) ─────────────────
			// Backtest fires max-time via PortfolioServiceImplV2.checkMaxTime in the
			// day-loop; SingleBarEvaluatorImpl never calls it, so live positions never
			// max-time out. Replicate the rule on the single bar: dayCount = trading
			// sessions from entryDate to lastBar (== the backtest's per-bar
			// updateTradeDayCount). Exit when dayCount >= maxTime. No last-bar guard
			// here — in execution lastBar means "today", not "end of data". Backtest
			// exits at CLOSE → route MOC.
			int maxTime = sd.getMaxTime();
			if (maxTime > 0) {
				int lastIdx = tradingDates.size() - 1;
				for (LiveHoldingsSeedDto h : liveHoldings) {
					if (exitSymbols.contains(h.getSymbol())) {
						continue; // already exiting on a rule — don't double-add
					}
					int entryIdx = tradingDates.indexOf(h.getEntryDate());
					if (entryIdx < 0) {
						continue; // entry date outside the loaded window — can't age it
					}
					if ((lastIdx - entryIdx) + 1 >= maxTime) {
						proposedExits.add(ProposedExitDto.builder().tradeId(h.getTradeId()).symbol(h.getSymbol())
								.exitReason(String.format("MaxTime %d", maxTime)).exitDate(request.getRunDate())
								.exitTiming("close") // MOC — matches backtest close-exit
								.build());
						exitSymbols.add(h.getSymbol()); // so step 8 skips its stop update
					}
				}
			}

			// ── 8. Stop updates — for each LIVE holding NOT exiting ──────────
			float stoplossPct = sd.getStopLossPct();
			List<StopUpdateDto> stopUpdates = new ArrayList<>();
			for (LiveHoldingsSeedDto h : liveHoldings) {
				if (exitSymbols.contains(h.getSymbol())) {
					continue;
				}
				Float newStop = computeStopValue(h, stoplossPct, sd.getStoplossType(), lastAtr, sd.getStoplossMaxPct()); // Patch
																															// 99
				// Patch 108: daily TP maintenance alongside the stop (legacy
				// take_profit_orders block). Uncapped by design.
				Float newTp = computeTpValue(h, stoplossPct, sd.getTakeProfitPct(), sd.getStoplossType(), lastAtr);
				if (newStop != null || newTp != null) {
					String source = (h.getCurrentStopPrice() != null) ? "trader_override" : "pct_recompute";
					stopUpdates.add(StopUpdateDto.builder().tradeId(h.getTradeId()).symbol(h.getSymbol())
							.newStopPrice(newStop).newTpPrice(newTp).source(source).build());
				}
			}

			// ── 8.5 Patch 69 (disabled by Patch 72r) — PORTFOLIO check ──────
			// The original Patch 69 check anchored drawdown to production_capital,
			// the same bug fixed in the backtest path by Patch 72o.4. The execution
			// path has no equity history (single-bar evaluator gets only today's
			// bar + seeded live holdings), so neither PEAK (needs maxEquity) nor
			// DAILY (needs yesterday's equity) can be computed faithfully here.
			//
			// TODO (post-MSSQL equity table): re-enable by looking up the
			// strategy's persisted equity history from the new equity table,
			// computing maxEquity / previousEquityValue, and matching the
			// PortfolioServiceImplV2 anchor logic. Until then this check is
			// intentionally disabled — PORTFOLIO halt only fires from backtest
			// runs, never from nightly PM execution.
			boolean portfolioTripped = false;
			String portfolioReason = null;
			if (false && "PORTFOLIO".equals(sd.getStoplossType()) && sd.getStopLossPct() > 0f) {
				float startingCapital = sd.getStartingCapital();
				if (startingCapital > 0f) {
					float currentEquity = startingCapital;
					for (LiveHoldingsSeedDto h : liveHoldings) {
						Float closeP = priceData.getDaily_closes().getValue(lastBar, h.getSymbol());
						if (closeP != null) {
							float dir = "SHORT".equalsIgnoreCase(h.getDirection()) ? -1f : 1f;
							currentEquity += dir * h.getQuantity() * (closeP - h.getEntryprice());
						}
					}
					float pctDown = ((startingCapital - currentEquity) / startingCapital) * 100f;
					if (pctDown >= sd.getStopLossPct()) {
						portfolioTripped = true;
						portfolioReason = String.format("Portfolio Stoploss Hit: %.2f%% (threshold %.2f%%) on %s",
								pctDown, sd.getStopLossPct(), lastBar.toString());
						// Override: close-all every live holding, kill entries + stops.
						proposedExits = new ArrayList<>();
						for (LiveHoldingsSeedDto h : liveHoldings) {
							proposedExits.add(ProposedExitDto.builder().tradeId(h.getTradeId()).symbol(h.getSymbol())
									.exitReason(portfolioReason).exitDate(request.getRunDate()).exitTiming("open") // →
																													// OPG/MKT
																													// in
																													// broker_write
									.build());
						}
						entries = new ArrayList<>();
						stopUpdates = new ArrayList<>();
					}
				}
			}

			// ── 9. Response ──────────────────────────────────────────────────
			return SingleBarSignalsResponseDto.builder().dataDate(lastBar).runDate(request.getRunDate())
					.activeRegimeOnLastBar(activeRegime).proposedEntries(entries).proposedExits(proposedExits)
					.stopUpdates(stopUpdates)
					// Patch 69: PORTFOLIO trip → middleware flips execution_enabled off.
					.executionEnabledChange(portfolioTripped ? Boolean.FALSE : null)
					.executionDisableReason(portfolioReason).build();
		}
	}

	/**
	 * Compute stop price for a LIVE position nightly.
	 *
	 * Priority: 1. D3 trader override (currentStopPrice non-null) — echoed back
	 * unchanged 2. ATR_BASED: entryPrice - (stoplossPct × atr[lastBar][ticker]) 3.
	 * PCT (NORMAL): entryPrice × (1 - stoplossPct/100) for LONG entryPrice × (1 +
	 * stoplossPct/100) for SHORT 4. No stoploss (pct=0, no override) → null (no
	 * stop bracket needed)
	 *
	 * Mirrors PortfolioServiceImplV2.stoplossHitLong (line 767) for PCT and
	 * stoplossHitLongAtr (line 1004) for ATR_BASED.
	 */
	private Float computeStopValue(LiveHoldingsSeedDto h, float stoplossPct, String stoplossType,
			Map<String, Float> lastAtr, float stoplossMaxPct) { // Patch 99: cap param
		// D3 trader override always takes precedence
		if (h.getCurrentStopPrice() != null) {
			return h.getCurrentStopPrice();
		}
		if (stoplossPct <= 0f) {
			return null;
		}
		boolean isLong = "LONG".equalsIgnoreCase(h.getDirection());

		// ATR_BASED: stop = entryPrice - (stoplossPct × atr)
		// stoplossPct is the ATR multiplier in this case (e.g. 2.0 = 2 × ATR)
		if ("ATR_BASED".equals(stoplossType) && lastAtr != null) {
			Float atr = lastAtr.get(h.getSymbol());
			if (atr != null && atr > 0f) {
				float offset = stoplossPct * atr;
				// Patch 99: cap maintenance stop offset at stoplossMaxPct% of ENTRY price
				// (legacy daily block: if stop > 20% below entry, floor at entry x 0.8).
				if (stoplossMaxPct > 0f) {
					float maxOffset = h.getEntryprice() * stoplossMaxPct / 100f;
					if (offset > maxOffset) {
						offset = maxOffset;
					}
				}
				if (isLong) {
					return h.getEntryprice() - offset;
				} else {
					return h.getEntryprice() + offset;
				}
			}
		}

		// PCT: stop = entryPrice × (1 ± pct/100)
		if (isLong) {
			return h.getEntryprice() * (1f - (stoplossPct / 100f));
		} else {
			return h.getEntryprice() * (1f + (stoplossPct / 100f));
		}
	}

	/**
	 * Patch 108: daily take-profit maintenance value for a LIVE holding.
	 * Mirrors the legacy take_profit_orders block:
	 *   ATR_BASED: tp = entry ± (takeProfitPct × stoplossPct × atr[lastBar])
	 *              — UNCAPPED by design: legacy derives the TP amount from
	 *              the PRE-cap stoploss amount (stoploss_max_pct never
	 *              touches it).
	 *   PCT      : tp = entry × (1 ± takeProfitPct/100)
	 * Engine-computed ONLY — there is no D3 trader override for TP (legacy
	 * had none either). takeProfitPct <= 0 → null (no TP bracket).
	 */
	private Float computeTpValue(LiveHoldingsSeedDto h, float stoplossPct, float takeProfitPct,
			String stoplossType, Map<String, Float> lastAtr) {
		if (takeProfitPct <= 0f) {
			return null;
		}
		boolean isLong = "LONG".equalsIgnoreCase(h.getDirection());

		if ("ATR_BASED".equals(stoplossType) && lastAtr != null) {
			Float atr = lastAtr.get(h.getSymbol());
			if (atr != null && atr > 0f && stoplossPct > 0f) {
				float profitAmount = takeProfitPct * stoplossPct * atr;
				if (isLong) {
					return h.getEntryprice() + profitAmount;
				} else {
					return h.getEntryprice() - profitAmount;
				}
			}
			return null;   // ATR missing → no TP tonight (loud in freshness logs)
		}

		// PCT semantics for non-ATR stoploss types
		if (isLong) {
			return h.getEntryprice() * (1f + takeProfitPct / 100f);
		}
		return h.getEntryprice() * (1f - takeProfitPct / 100f);
	}

	private String resolveDirection(String systemType) {
		if (systemType == null)
			return "LONG";
		if ("SHORT".equalsIgnoreCase(systemType))
			return "SHORT";
		return "LONG";
	}

	// Patch 90: 2-decimal rounding, matching BacktestServiceImplV2 +
	// PortfolioServiceImplV2.
	private static float round2(float v) {
		return Math.round(v * 100f) / 100f;
	}
}