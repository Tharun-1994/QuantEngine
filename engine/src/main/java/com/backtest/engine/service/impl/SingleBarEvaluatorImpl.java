package com.backtest.engine.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
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

			// ATR frame for last bar — used for LIMIT_ATR entry price and ATR_BASED stop.
			// Loaded from atr_stp parquet via RegimeOverlay in BacktestContext.
			// Null for NORMAL strategies (no ATR parquet loaded).
			Map<String, Float> lastAtr = (sd.getDailyAtr() != null) ? sd.getDailyAtr().getRow(lastBar) : null;

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
						limitPrice = close * (1f - sd.getLimitPct() / 100f);
					} else if (isLimitAtr && sd.getAtrLimitLookback() > 0) {
						Float atr = (lastAtr != null) ? lastAtr.get(ticker) : null;
						if (atr != null && atr > 0f) {
							limitPrice = limitPrice = round2(close - (sd.getLimitPct() * atr)); // Patch 90:
																								// round2(close −
																								// limitPct×ATR)
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
						stopPrice = limitPrice * (1f - sd.getStopLossPct() / 100f);
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
							stopPrice = round2(limitPrice - slOffset);
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
						tpPrice = round2(limitPrice * (1f + sd.getTakeProfitPct() / 100f));
					} else if ("ATR_BASED".equals(tpType) && sd.getAtrLookbackStp() > 0) {
						Float atr = (lastAtr != null) ? lastAtr.get(ticker) : null;
						if (atr != null && atr > 0f) {
							tpPrice = round2(limitPrice + round2(sd.getTakeProfitPct() * sd.getStopLossPct() * atr));
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