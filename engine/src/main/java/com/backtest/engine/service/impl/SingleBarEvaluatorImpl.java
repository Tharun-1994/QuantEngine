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

			List<ProposedEntryDto> entries = new ArrayList<>();
			for (int i = 0; i < rankedEntries.size(); i++) {
				String ticker = rankedEntries.get(i);
				Float close = (lastCloses != null) ? lastCloses.get(ticker) : null;
				Float score = (rankRow != null) ? rankRow.get(ticker) : null;
				String sector = (sd.getSectorMap() != null) ? sd.getSectorMap().getOrDefault(ticker, null) : null;

				int qty = 0;
				if (close != null && close > 0f) {
					qty = (int) Math.floor(perSlotCapital / close);
				}
				float capitalEst = (close != null) ? (qty * close) : 0f;

				ProposedEntryDto entry = ProposedEntryDto.builder().symbol(ticker).direction(direction)
						.orderType(sd.getOrderType()).entryDate(request.getRunDate()).entryTiming(sd.getEntryTiming())
						.entryReason("entry rule fired on " + lastBar).quantity(qty).capital(capitalEst).sector(sector)
						.rank(i + 1).score(score)
						// limitPrice / stopPrice for LIMIT / LIMIT_ATR: deferred,
						// see class-level Javadoc.
						.limitPrice(null).stopPrice(null).build();

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

			// ── 8. Stop updates — for each LIVE holding NOT exiting ──────────
			float stoplossPct = sd.getStopLossPct();
			List<StopUpdateDto> stopUpdates = new ArrayList<>();
			for (LiveHoldingsSeedDto h : liveHoldings) {
				if (exitSymbols.contains(h.getSymbol())) {
					continue;
				}
				Float newStop = computeStopValue(h, stoplossPct);
				if (newStop != null) {
					String source = (h.getCurrentStopPrice() != null) ? "trader_override" : "pct_recompute";
					stopUpdates.add(StopUpdateDto.builder().tradeId(h.getTradeId()).symbol(h.getSymbol())
							.newStopPrice(newStop).source(source).build());
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
	 * Compute today's stop value for a LIVE position. Mirrors
	 * PortfolioServiceImplV2.stoplossHitLong/Short formula (lines 524-542): - D3
	 * trader override (currentStopPrice non-null) takes precedence - Otherwise, pct
	 * stop relative to entry price - Returns null when stoploss_pct=0 and no trader
	 * override (no broker stop bracket needed for this position)
	 *
	 * ATR-based stops not handled here — see class-level Javadoc.
	 */
	private Float computeStopValue(LiveHoldingsSeedDto h, float stoplossPct) {
		if (h.getCurrentStopPrice() != null) {
			return h.getCurrentStopPrice();
		}
		if (stoplossPct <= 0f) {
			return null;
		}
		if ("LONG".equalsIgnoreCase(h.getDirection())) {
			return h.getEntryprice() * (1f - (stoplossPct / 100f));
		} else {
			return h.getEntryprice() * (1f + (stoplossPct / 100f));
		}
	}

	private String resolveDirection(String systemType) {
		if (systemType == null)
			return "LONG";
		if ("SHORT".equalsIgnoreCase(systemType))
			return "SHORT";
		return "LONG";
	}
}