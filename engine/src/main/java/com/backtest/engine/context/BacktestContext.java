package com.backtest.engine.context;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import com.backtest.engine.config.ArrowDataFrameCache;
import com.backtest.engine.config.ArrowStringDataFrameCache;
import com.backtest.engine.dto.request.MarketRegimeDto;
import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.SafetyNetItemDto;
import com.backtest.engine.dto.request.StrategyBucketRequestDto;

import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.entity.RegimeOverlay;
import com.backtest.engine.entity.StrategyDataV2;
import com.backtest.engine.ruleBuilder.RuleTreeFlattener;
import com.backtest.engine.service.MarketTrendServiceV2;
import com.backtest.engine.service.PriceDataLoaderService;
import com.backtest.engine.service.StrategyBuilderServiceV2;
import com.backtest.engine.service.impl.VolatilityCutEvaluator;
import com.backtest.engine.service.safetynet.SafetyNetInitContext;
import com.backtest.engine.service.safetynet.SafetyNetPolicy;
import com.backtest.engine.service.safetynet.SafetyNetRegistry;
import com.backtest.engine.util.ArrowDataFrame;
import com.backtest.engine.util.ArrowStringDataFrame;
import com.backtest.engine.util.IndicatorRuleLoader;
import com.backtest.engine.util.ParquetToMap;
import com.backtest.engine.util.PriceLoader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class BacktestContext implements AutoCloseable {

	PriceDataV2 priceDataV2;
	private PriceDataLoaderService priceDataService;
	private StrategyBuilderServiceV2 strategyBuilderServiceV2;
	private MarketTrendServiceV2 marketTrendServiceV2;
	private ArrowDataFrameCache cache;
	private ArrowStringDataFrameCache stringCache;

	Map<String, List<LocalDate>> parquetDatesMapList;
	Map<String, ArrowDataFrame> parquetFileValueMap;
	Map<String, ArrowStringDataFrame> parquetMapSet;

	// Track all Arrow resources opened during Simple regime processing
	private final List<Map<String, ArrowDataFrame>> allArrowMaps = new ArrayList<>();

	// Patch 14: execution-mode data root override.
	// When set (non-null, non-blank), inputPath() resolves to
	// {executionDataRoot}/{universe}, skipping the {strategy_name}/input/
	// segments. exec_data is universe-shared so no per-strategy folder is
	// needed. Set by the /api/execution/step/single endpoint from the
	// request's data_root field; null in backtest contexts.
	private String executionDataRoot;

	public BacktestContext(PriceDataLoaderService priceDataService, StrategyBuilderServiceV2 strategyBuilderServiceV2,
			MarketTrendServiceV2 marketTrendServiceV2, ArrowDataFrameCache cache,
			ArrowStringDataFrameCache stringCache) {
		this.priceDataService = priceDataService;
		this.strategyBuilderServiceV2 = strategyBuilderServiceV2;
		this.marketTrendServiceV2 = marketTrendServiceV2;
		this.cache = cache;
		this.stringCache = stringCache;
	}

	// Patch 14: setter invoked from the execution endpoint to switch the
	// path-resolution scheme to exec_data layout. No-op for backtests.
	public void setExecutionDataRoot(String dataRoot) {
		this.executionDataRoot = dataRoot;
	}

	public String inputPath(String strategy_name, String universe, String backtestDataPath) {
		// Patch 14: execution mode reads from a date-stamped exec_data
		// folder written by middleware C1. Skips strategy_name + "input"
		// segments because exec_data is universe-shared. backtestDataPath
		// is intentionally ignored in this branch — the caller's existing
		// argument is preserved unchanged for API compatibility.
		if (this.executionDataRoot != null && !this.executionDataRoot.isBlank()) {
			return String.format("%s/%s", this.executionDataRoot, universe);
		}
		return String.format("%s/%s/%s/%s", backtestDataPath, strategy_name, "input", universe);
	}

	public String path(String strategy_name, String fileName, String universe, String backtestDataPath) {

		if (fileName.startsWith("trading_") || fileName.startsWith("all_dates")) {
			return Paths.get(inputPath(strategy_name, universe, backtestDataPath), fileName).toString();
		}
		return Paths.get(inputPath(strategy_name, universe, backtestDataPath), fileName).toUri().toString();
	}

	public PriceDataV2 getPriceData(StrategyBucketRequestDto strategyRequest, String backtestDataPath) {
		this.parquetDatesMapList = new HashMap<>();
		this.parquetFileValueMap = new HashMap<>();
		this.parquetMapSet = new HashMap<>();

		Map<Integer, Map<String, String>> priceLoaderPathMap = new HashMap<>();
		int i = 0;
		for (MarketRegimeDto marketRegime : strategyRequest.getRegimes()) {
			boolean volEnabled = marketRegime.getVolFilter() != null && marketRegime.getVolFilter().isEnabled();
			PriceLoader priceLoader = PriceLoader.builder().universe(marketRegime.getUniverse())
					.atrLimitLookback(marketRegime.getAtrLimitLookback())
					.atrLookbackStp(marketRegime.getAtrLookbackStp()).atrLookbackTp(marketRegime.getAtrLookbackTp())
					.rebalance(strategyRequest.getRebalance()).rankingIndicator(marketRegime.getRanking())
					.rankingLookback(marketRegime.getRankingLookback()).volFilterEnabled(volEnabled).build();

			priceLoaderPathMap.put(i, priceLoader.getFilesForRebalance(strategyRequest.getRegimes()));
			i++;
		}

		for (Integer regimeIndex : priceLoaderPathMap.keySet()) {
			for (String keyFile : priceLoaderPathMap.get(regimeIndex).keySet()) {
				try {
					if (keyFile.equals("trading_dates") || keyFile.equals("all_dates")) {
						List<LocalDate> eachList = ParquetToMap.loadParquetToDateList(
								path(strategyRequest.getName(), priceLoaderPathMap.get(regimeIndex).get(keyFile),
										strategyRequest.getRegimes().get(0).getUniverse(), backtestDataPath));
						parquetDatesMapList.put(keyFile, eachList);
					} else if (keyFile.equals("universes")) {
						ArrowStringDataFrame universe = stringCache
								.load(path(strategyRequest.getName(), priceLoaderPathMap.get(regimeIndex).get(keyFile),
										strategyRequest.getRegimes().get(0).getUniverse(), backtestDataPath));
						parquetMapSet.put(keyFile, universe);
					} else {
						if ((keyFile.equals("atr_limit") && (priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
								|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty()))
								|| keyFile.equals("atr_stp")
										&& (priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
												|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty())
								|| keyFile.equals("atr_tp")
										&& (priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
												|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty())) {
							continue;
						}

						if (priceLoaderPathMap.get(regimeIndex).get(keyFile) == null
								|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
								|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty()
								|| priceLoaderPathMap.get(regimeIndex).get(keyFile).contains("null")) {
							continue;
						}

						ArrowDataFrame currentDf = cache
								.load(path(strategyRequest.getName(), priceLoaderPathMap.get(regimeIndex).get(keyFile),
										strategyRequest.getRegimes().get(0).getUniverse(), backtestDataPath));
						parquetFileValueMap.put(keyFile, currentDf);
					}
				} catch (Exception e) { // Broadened from SQLException, as no SQL is involved
					// Replace with proper logging or error handling
					e.printStackTrace();
				}
			}
		}

		ArrowDataFrame daily_atr = Stream.of("atr_limit", "atr_stp", "atr_tp").map(parquetFileValueMap::get)
				.filter(Objects::nonNull).findFirst().orElse(null);

		// Ensure closes_spy is available for vol filter SPY SMA computation
		MarketRegimeDto firstRegime = strategyRequest.getRegimes().get(0);
		if (firstRegime.getVolFilter() != null && firstRegime.getVolFilter().isEnabled()) {
			String spyTicker = firstRegime.getVolFilter().getSpyTicker() != null
					? firstRegime.getVolFilter().getSpyTicker().toLowerCase()
					: "spy";
			String spyKey = "closes_" + spyTicker;
			if (!parquetFileValueMap.containsKey(spyKey)) {
				try {
					String prefix = strategyRequest.getRebalance().equalsIgnoreCase("daily") ? "DAILY_"
							: strategyRequest.getRebalance().toLowerCase() + "_";
					ArrowDataFrame spyDf = cache.load(path(strategyRequest.getName(), prefix + spyKey + ".parquet",
							firstRegime.getUniverse(), backtestDataPath));
					parquetFileValueMap.put(spyKey, spyDf);
				} catch (Exception e) {
					System.err.println("[WARNING] Could not load spy closes for vol filter: " + e.getMessage());
				}
			}
		}

		PriceDataV2 priceData = this.priceDataService.loadPricesMarketDatav2(parquetFileValueMap.get("closes"),
				parquetFileValueMap.get("opens"), parquetFileValueMap.get("highs"), parquetFileValueMap.get("lows"),
				parquetMapSet.get("universes"), parquetDatesMapList.get("trading_dates"),
				parquetDatesMapList.get("all_dates"), daily_atr, null);
		priceData.setEndDate(strategyRequest.getEndDate());

		return priceData;

	}

	public BuySellDataV2 getBuySellData(StrategyBucketRequestDto strategyRequest, PriceDataV2 priceData,
			String backtestDataPath) {

//		List<RuleDto> entryRuleConditions = strategyRequest.getRegimes().get(0).getEntryRules();
//		List<RuleDto> exitRuleConditions = strategyRequest.getRegimes().get(0).getExitRules();
		String univ = strategyRequest.getRegimes().get(0).getUniverse();
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
				.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
				.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);

		Object entryObj = strategyRequest.getRegimes().get(0).getEntryRulesTree();
		Object exitObj = strategyRequest.getRegimes().get(0).getExitRulesTree();
		Object freezeObj = strategyRequest.getRegimes().get(0).getFreezeRulesTree();
		Object resumeObj = strategyRequest.getRegimes().get(0).getResumeRulesTree();

		RuleGroupNodeDto entryTree = mapper.convertValue(entryObj, RuleGroupNodeDto.class);
		RuleGroupNodeDto exitTree = mapper.convertValue(exitObj, RuleGroupNodeDto.class);
		RuleGroupNodeDto freezeTree = freezeObj == null ? null : mapper.convertValue(freezeObj, RuleGroupNodeDto.class);
		RuleGroupNodeDto resumeTree = resumeObj == null ? null : mapper.convertValue(resumeObj, RuleGroupNodeDto.class);

		List<RuleDto> entryLeafRules = RuleTreeFlattener.flatten(entryTree);
		List<RuleDto> exitLeafRules = RuleTreeFlattener.flatten(exitTree);

		Map<String, ArrowDataFrame> entryMap = IndicatorRuleLoader.loadTables(entryLeafRules,
				inputPath(strategyRequest.getName(), univ, backtestDataPath));
		Map<String, ArrowDataFrame> exitMap = IndicatorRuleLoader.loadTables(exitLeafRules,
				inputPath(strategyRequest.getName(), univ, backtestDataPath));

		// Load sector mapping if sector filter is enabled
		Map<String, String> sectorMap = null;
		int sectorLevel = strategyRequest.getRegimes().get(0).getSectorLevel();
		int sectorLimit = strategyRequest.getRegimes().get(0).getSectorLimit();
		if (sectorLevel > 0 && sectorLimit > 0) {
			try {
				String sectorPath = inputPath(strategyRequest.getName(), univ, backtestDataPath)
						+ "/sector_mapping.parquet";
				sectorMap = ParquetToMap.loadSectorMapping(sectorPath, sectorLevel);
			} catch (Exception e) {
				System.err.println("[WARNING] Could not load sector mapping: " + e.getMessage());
			}
		}

		// Volatility-cut (freeze/resume) — dedicated load + per-date evaluation.
		// Separate from market-trend and the regime price loader.
		Map<String, ArrowDataFrame> freezeFrames = loadVolatilityCutFrames(freezeTree, strategyRequest, univ,
				backtestDataPath);
		Map<String, ArrowDataFrame> resumeFrames = loadVolatilityCutFrames(resumeTree, strategyRequest, univ,
				backtestDataPath);
		List<LocalDate> volCutDates = priceData.getAll_dates();
		Set<LocalDate> freezeDays = VolatilityCutEvaluator.evaluateTreeToDates(freezeTree, freezeFrames, volCutDates);
		Set<LocalDate> resumeDays = VolatilityCutEvaluator.evaluateTreeToDates(resumeTree, resumeFrames, volCutDates);

		// ── Stage 3b: build SafetyNetPolicy instances ─────────────────────
		// Resolves request.safety_nets, falling back to a synthesised single
		// "simple" item if only legacy fields are present. Each item is run
		// through SafetyNetRegistry to get a fresh policy instance, then
		// initialised with the shared market data + frame loader.
		// Day-loop dispatch wiring lands in Stage 3b Sub-chunk B2; until
		// then the inline freezeDays/resumeDays path above stays primary.
		java.util.List<SafetyNetItemDto> resolvedSafetyNets = resolveSafetyNets(strategyRequest);
		SafetyNetInitContext safetyInitCtx = SafetyNetInitContext.builder().strategyRequest(strategyRequest)
				.universe(univ).priceData(priceData).allDates(volCutDates).objectMapper(mapper)
				.frameLoader((RuleGroupNodeDto tree) -> loadVolatilityCutFrames(tree, strategyRequest, univ,
						backtestDataPath))
				.build();
		java.util.List<com.backtest.engine.service.safetynet.SafetyNetPolicy> safetyPolicies = new java.util.ArrayList<>();
		for (SafetyNetItemDto item : resolvedSafetyNets) {
			SafetyNetPolicy policy = SafetyNetRegistry.create(item.getType());
			if (policy == null)
				continue;
			policy.initialize(item, safetyInitCtx);
			safetyPolicies.add(policy);
		}
		System.out.println("[safety-nets] resolved=" + resolvedSafetyNets.size() + " active=" + safetyPolicies.size());

		StrategyDataV2 strategyData = StrategyDataV2.builder().entryRulesList(entryLeafRules)
				.exitRuleList(exitLeafRules).entryIndicators(entryMap).exitIndicators(exitMap)
				.startingCapital(strategyRequest.getRegimes().get(0).getCapital())
				.slots(strategyRequest.getRegimes().get(0).getSlots())
				.stopLossPct(strategyRequest.getRegimes().get(0).getStoplossPct())
				.takeProfitPct(strategyRequest.getRegimes().get(0).getTakeprofitPct())
				.stoplossTiming(strategyRequest.getRegimes().get(0).getStoplossTiming())
				.takeprofitTiming(strategyRequest.getRegimes().get(0).getTakeprofitTiming())
				.entryTiming(strategyRequest.getRegimes().get(0).getEntryTiming())
				.exitTiming(strategyRequest.getRegimes().get(0).getExitTiming())
				.ranking(this.parquetFileValueMap.get("ranking"))
				.rankingOrder(strategyRequest.getRegimes().get(0).getRankingOrder())
				.sectorLevel(strategyRequest.getRegimes().get(0).getSectorLevel())
				.sectorLimit(strategyRequest.getRegimes().get(0).getSectorLimit())
				.closePositionsOnRegimeExit(strategyRequest.getRegimes().get(0).isClosePositionsOnRegimeExit())
				.sectorMap(sectorMap).gapFilterPct(strategyRequest.getRegimes().get(0).getGapFilterPct())
				.maxDuplicates(strategyRequest.getRegimes().get(0).getMaxDuplicates())
				.maxDuplicateSets(strategyRequest.getRegimes().get(0).getMaxDuplicateSets())
				.startDate(strategyRequest.getStartDate()).endDate(strategyRequest.getEndDate())
				.minPrice(strategyRequest.getMinPrice()).minQuantity(strategyRequest.getMinQuantity())
				.stoplossType(strategyRequest.getRegimes().get(0).getStoplossType())
				.takeprofitType(strategyRequest.getRegimes().get(0).getTakeprofitType())
				// Patch 72m.4: anchor passes through builder.
				.portfolioStoplossAnchor(strategyRequest.getRegimes().get(0).getPortfolioStoplossAnchor())
				.systemType(strategyRequest.getSystemType())
				.orderType(strategyRequest.getRegimes().get(0).getOrderType())
				.atrLimitLookback(strategyRequest.getRegimes().get(0).getAtrLimitLookback())
				.limitPct(strategyRequest.getRegimes().get(0).getLimitPct())
				.maxTime(strategyRequest.getRegimes().get(0).getMaxTime())
				.bannedMonths(strategyRequest.getRegimes().get(0).getBannedMonths())
				.tdomFilters(strategyRequest.getRegimes().get(0).getTdomFilters())
				.volFilter(strategyRequest.getRegimes().get(0).getVolFilter())
				.avgVolume(this.parquetFileValueMap.get("avg_volume"))
				.avgTurnover(this.parquetFileValueMap.get("avg_turnover"))
				.spyCloses(this.parquetFileValueMap.get("closes_spy")).entryRulesTree(entryTree).exitRulesTree(exitTree)
				.freezeRulesTree(freezeTree).resumeRulesTree(resumeTree).freezeDays(freezeDays).resumeDays(resumeDays)
				.freezeTiming(strategyRequest.getRegimes().get(0).getFreezeTiming() == null ? "open"
						: strategyRequest.getRegimes().get(0).getFreezeTiming().toLowerCase())
				.resumeTiming(strategyRequest.getRegimes().get(0).getResumeTiming() == null ? "open"
						: strategyRequest.getRegimes().get(0).getResumeTiming().toLowerCase())
				.safetyNetType(strategyRequest.getRegimes().get(0).getSafetyNetType() == null ? "none"
						: strategyRequest.getRegimes().get(0).getSafetyNetType().toLowerCase())
				.safetyNets(strategyRequest.getRegimes().get(0).getSafetyNets()).safetyPolicies(safetyPolicies).build();

		BuySellDataV2 buySellData = this.strategyBuilderServiceV2.generateSignalsV1(strategyData, priceData);
		buySellData.setStrategyData(strategyData);
		return buySellData;
	}

	/**
	 * Volatility-cut (freeze/resume) — DEDICATED, separate from market-trend and
	 * the regime price loader. Loads the per-date frames referenced by the
	 * freeze/resume trees into their own map.
	 *
	 * FIX (Bug 1): key format is {ticker}_{indicator}_{lookback}, matching exactly
	 * what VolatilityCutEvaluator.evalLeaf() expects. Previously the ticker prefix
	 * was omitted, causing every lookup to silently return null.
	 *
	 * FIX (Bug 2): when the ticker is "spy" and the indicator is "close", the frame
	 * is sourced from the already-loaded closes_spy entry in parquetFileValueMap
	 * (loaded by the VolFilter path) rather than attempting to read a non-existent
	 * spy_close_0.parquet from the strategy input folder. If closes_spy is not yet
	 * loaded (VolFilter disabled) we attempt a direct load using the standard
	 * DAILY_closes_spy.parquet path.
	 *
	 * NOTE: spy_sma_{N} frames are intentionally NOT loaded here.
	 * VolatilityCutEvaluator computes the SMA on-the-fly from the close frame (Bug
	 * 3 fix), so no pre-built SMA parquet is required.
	 *
	 * NOTE: rules with operator "month_in" and indicator "month" are skipped — they
	 * require no parquet frame (the evaluator inspects the date directly).
	 */
	private java.util.Map<String, ArrowDataFrame> loadVolatilityCutFrames(RuleGroupNodeDto tree,
			StrategyBucketRequestDto strategyRequest, String univ, String backtestDataPath) {
		java.util.Map<String, ArrowDataFrame> frames = new java.util.HashMap<>();
		if (tree == null)
			return frames;

		String rebalancePrefix = strategyRequest.getRebalance().equalsIgnoreCase("daily") ? "DAILY_"
				: strategyRequest.getRebalance().toLowerCase() + "_";

		for (RuleDto rule : RuleTreeFlattener.flatten(tree)) {
			// month_in rules need no frame — evaluator inspects the date directly
			if ("month_in".equalsIgnoreCase(rule.getOperator()) || "month".equalsIgnoreCase(rule.getIndicator())) {
				continue;
			}

			String ticker = (rule.getRegimeTicker() == null || rule.getRegimeTicker().isBlank()) ? ""
					: rule.getRegimeTicker().toLowerCase();

			// LHS frame: {ticker}_{indicator}_{lookback}
			String lhsKey = buildVolCutKey(ticker, rule.getIndicator(), rule.getLookback());
			loadVolCutFrame(lhsKey, ticker, rule.getIndicator(), rule.getLookback(), frames, strategyRequest, univ,
					backtestDataPath, rebalancePrefix);

			// RHS frame — only when comparing to another indicator.
			// Skip "sma" RHS: VolatilityCutEvaluator computes it on-the-fly from the
			// close frame (no pre-built sma parquet needed).
			if ("indicator_price".equalsIgnoreCase(rule.getValueType()) && rule.getValueIndicator() != null
					&& !rule.getValueIndicator().isBlank() && !"sma".equalsIgnoreCase(rule.getValueIndicator())) {
				String rhsKey = buildVolCutKey(ticker, rule.getValueIndicator(), rule.getValueLookback());
				loadVolCutFrame(rhsKey, ticker, rule.getValueIndicator(), rule.getValueLookback(), frames,
						strategyRequest, univ, backtestDataPath, rebalancePrefix);
			}
		}
		return frames;
	}

	/**
	 * Back-compat shim: if the request has no {@code safety_nets} list but carries
	 * the legacy {@code safety_net_type='simple'} + regime-level freeze/resume
	 * trees, synthesise a one-item list so the policy framework treats them
	 * identically to a freshly-saved strategy.
	 *
	 * <p>
	 * Returns the safetyNets list to use downstream (never null — empty list when
	 * there's nothing to wire up).
	 * </p>
	 */
	@SuppressWarnings("unchecked")
	private List<SafetyNetItemDto> resolveSafetyNets(StrategyBucketRequestDto request) {
		MarketRegimeDto regime = request.getRegimes().get(0);
		java.util.List<SafetyNetItemDto> list = regime.getSafetyNets();
		if (list != null && !list.isEmpty()) {
			return list;
		}
		String legacyType = regime.getSafetyNetType();
		if (legacyType == null || !"simple".equalsIgnoreCase(legacyType)) {
			return java.util.Collections.emptyList();
		}
		// Synthesise a single 'simple' item from the regime-level fields
		java.util.Map<String, Object> params = new java.util.HashMap<>();
		params.put("freeze_rules_tree", regime.getFreezeRulesTree());
		params.put("resume_rules_tree", regime.getResumeRulesTree());
		params.put("freeze_timing", regime.getFreezeTiming() == null ? "open" : regime.getFreezeTiming());
		params.put("resume_timing", regime.getResumeTiming() == null ? "open" : regime.getResumeTiming());
		SafetyNetItemDto item = new SafetyNetItemDto("simple", params);
		return java.util.List.of(item);
	}

	/**
	 * Canonical key: {ticker}_{indicator}_{lookback} (ticker may be empty).
	 *
	 * Exception: when the indicator name already encodes the ticker (e.g.
	 * indicator="vix_close" with ticker="vix"), the ticker prefix is skipped so the
	 * key matches the middleware-written filename (vix_close_0.parquet, not
	 * vix_vix_close_0.parquet). See
	 * GeneratePricesIndicators._compute_volatility_cut_indicators.
	 */
	private static String buildVolCutKey(String ticker, String indicator, Integer lookback) {
		String ind = indicator == null ? "" : indicator.toLowerCase();
		int lb = (lookback == null) ? 0 : lookback;
		if (ticker == null || ticker.isBlank())
			return ind + "_" + lb;
		String tk = ticker.toLowerCase();
		if (ind.startsWith(tk + "_"))
			return ind + "_" + lb; // indicator already ticker-prefixed
		return tk + "_" + ind + "_" + lb;
	}

	/**
	 * Load a single frame into the frames map.
	 *
	 * Priority for close frames on a known market ticker (e.g. spy): 1. Already
	 * present in parquetFileValueMap (loaded by VolFilter path) 2. Direct load via
	 * DAILY_closes_{ticker}.parquet from strategy input folder 3. Fall back to
	 * generic {key}.parquet in strategy input folder
	 */
	private void loadVolCutFrame(String key, String ticker, String indicator, Integer lookback,
			java.util.Map<String, ArrowDataFrame> frames, StrategyBucketRequestDto strategyRequest, String univ,
			String backtestDataPath, String rebalancePrefix) {
		if (frames.containsKey(key))
			return;

		// FIX (Bug 2): for close indicator on a named ticker, prefer the already-loaded
		// closes_{ticker} frame from parquetFileValueMap (populated by VolFilter path).
		if ("close".equalsIgnoreCase(indicator) && ticker != null && !ticker.isBlank()) {
			String spyMapKey = "closes_" + ticker;
			ArrowDataFrame existing = parquetFileValueMap.get(spyMapKey);
			if (existing != null) {
				frames.put(key, existing);
				return;
			}
			// VolFilter not enabled — attempt direct load of DAILY_closes_{ticker}.parquet
			try {
				String fileName = rebalancePrefix + "closes_" + ticker + ".parquet";
				ArrowDataFrame df = cache.load(path(strategyRequest.getName(), fileName, univ, backtestDataPath));
				if (df != null) {
					frames.put(key, df);
					parquetFileValueMap.put(spyMapKey, df); // cache it for reuse
					return;
				}
			} catch (Exception e) {
				System.err.println(
						"[WARNING] Could not load vol-cut close frame for ticker '" + ticker + "': " + e.getMessage());
			}
		}

		// Generic path: load {key}.parquet from strategy input folder
		try {
			ArrowDataFrame df = cache.load(path(strategyRequest.getName(), key + ".parquet", univ, backtestDataPath));
			if (df != null)
				frames.put(key, df);
		} catch (Exception e) {
			System.err.println("[WARNING] Could not load volatility-cut frame '" + key + "': " + e.getMessage());
		}
	}
	// ==================================================================
	// SIMPLE REGIME — Multi-regime with market trend overlay
	// ==================================================================

	/**
	 * Load shared price data for Simple regime. Loads OHLC, universe, dates, and
	 * market ticker closes per regime.
	 */
	public PriceDataV2 getSimplePriceData(StrategyBucketRequestDto strategyRequest, String backtestDataPath) {
		this.parquetDatesMapList = new HashMap<>();
		this.parquetFileValueMap = new HashMap<>();
		this.parquetMapSet = new HashMap<>();

		String universe = strategyRequest.getRegimes().get(0).getUniverse();

		PriceLoader coreLoader = PriceLoader.builder().universe(universe).rebalance(strategyRequest.getRebalance())
				.build();

		Map<String, String> priceLoaderPathMap = coreLoader.getFilesForRebalance(strategyRequest.getRegimes());

		for (String keyFile : priceLoaderPathMap.keySet()) {
			try {
				if (keyFile.equals("trading_dates") || keyFile.equals("all_dates")) {
					List<LocalDate> eachList = ParquetToMap.loadParquetToDateList(path(strategyRequest.getName(),
							priceLoaderPathMap.get(keyFile), universe, backtestDataPath));
					parquetDatesMapList.put(keyFile, eachList);
				} else if (keyFile.equals("universes")) {
					ArrowStringDataFrame sharedUniverse = ArrowStringDataFrame.load(path(strategyRequest.getName(),
							priceLoaderPathMap.get(keyFile), universe, backtestDataPath));
					parquetMapSet.put(keyFile, sharedUniverse);
				} else if (priceLoaderPathMap.get(keyFile) != null && !keyFile.contains("ranking")
						&& !keyFile.contains("atr_")) {
					ArrowDataFrame currentDf = ArrowDataFrame.load(path(strategyRequest.getName(),
							priceLoaderPathMap.get(keyFile), universe, backtestDataPath));
					parquetFileValueMap.put(keyFile, currentDf);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		Map<String, ArrowDataFrame> marketTickerPrices = new HashMap<>();
		for (String ticker : coreLoader.getMarketTickers()) {
			String tickerKey = "closes_" + ticker;
			ArrowDataFrame tickerDf = parquetFileValueMap.get(tickerKey);
			if (tickerDf != null) {
				marketTickerPrices.put(tickerKey, tickerDf);
			}
		}

		PriceDataV2 priceData = this.priceDataService.loadPricesMarketDatav2(parquetFileValueMap.get("closes"),
				parquetFileValueMap.get("opens"), parquetFileValueMap.get("highs"), parquetFileValueMap.get("lows"),
				parquetMapSet.get("universes"), parquetDatesMapList.get("trading_dates"),
				parquetDatesMapList.get("all_dates"), null, marketTickerPrices);
		priceData.setEndDate(strategyRequest.getEndDate());

		return priceData;
	}

	/**
	 * Build BuySellDataV2 per regime for Simple type. Uses tree evaluation
	 * (RuleTreeCache + RuleTreeEvaluator) — not flattening. Returns Map keyed by
	 * market trend label → BuySellDataV2.
	 */
	public Map<String, BuySellDataV2> getSimpleBuySellDataMap(StrategyBucketRequestDto strategyRequest,
			PriceDataV2 priceData, String backtestDataPath) {

		Map<String, BuySellDataV2> rulesOfDayRegimes = new HashMap<>();
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
				.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
				.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);

		// Load sector mapping once (shared across all regimes)
		Map<String, String> simpleSectorMap = null;
		int simpleSectorLevel = strategyRequest.getRegimes().get(0).getSectorLevel();
		int simpleSectorLimit = strategyRequest.getRegimes().get(0).getSectorLimit();
		if (simpleSectorLevel > 0 && simpleSectorLimit > 0) {
			try {
				String universe0 = strategyRequest.getRegimes().get(0).getUniverse();
				String sectorPath = inputPath(strategyRequest.getName(), universe0, backtestDataPath)
						+ "/sector_mapping.parquet";
				simpleSectorMap = ParquetToMap.loadSectorMapping(sectorPath, simpleSectorLevel);
			} catch (Exception e) {
				System.err.println("[WARNING] Could not load sector mapping: " + e.getMessage());
			}
		}

		for (MarketRegimeDto regime : strategyRequest.getRegimes()) {
			String universe = regime.getUniverse();
			String inputDir = inputPath(strategyRequest.getName(), universe, backtestDataPath);

			// Convert tree JSON → typed DTO
			RuleGroupNodeDto entryTree = mapper.convertValue(regime.getEntryRulesTree(), RuleGroupNodeDto.class);
			RuleGroupNodeDto exitTree = mapper.convertValue(regime.getExitRulesTree(), RuleGroupNodeDto.class);

			// Flatten ONLY for loading indicator parquets (need filenames)
			List<RuleDto> entryLeafRules = RuleTreeFlattener.flatten(entryTree);
			List<RuleDto> exitLeafRules = RuleTreeFlattener.flatten(exitTree);

			// Load indicator parquets
			Map<String, ArrowDataFrame> entryMap = IndicatorRuleLoader.loadTables(entryLeafRules, inputDir);
			Map<String, ArrowDataFrame> exitMap = IndicatorRuleLoader.loadTables(exitLeafRules, inputDir);
			allArrowMaps.add(entryMap);
			allArrowMaps.add(exitMap);

			// Load per-regime overlays (ATR, ranking)
			RegimeOverlay overlay = loadRegimeOverlay(strategyRequest, regime, backtestDataPath);

			// Build StrategyDataV2 — set TREES (not just flat rules) for tree evaluation
			StrategyDataV2 strategyData = StrategyDataV2.builder().entryRulesList(entryLeafRules)
					.exitRuleList(exitLeafRules).entryIndicators(entryMap).exitIndicators(exitMap)
					.startingCapital(regime.getCapital()).slots(regime.getSlots()).stopLossPct(regime.getStoplossPct())
					.productionCapital(regime.getProductionCapital())   // Patch 50
					.takeProfitPct(regime.getTakeprofitPct()).stoplossTiming(regime.getStoplossTiming())
					.takeprofitTiming(regime.getTakeprofitTiming()).entryTiming(regime.getEntryTiming())
					.exitTiming(regime.getExitTiming()).ranking(overlay.getRanking())
					.rankingOrder(regime.getRankingOrder()).sectorMap(simpleSectorMap).sectorLevel(simpleSectorLevel)
					.closePositionsOnRegimeExit(regime.isClosePositionsOnRegimeExit()).sectorLimit(simpleSectorLimit)
					.gapFilterPct(regime.getGapFilterPct()).maxDuplicates(regime.getMaxDuplicates())
					.maxDuplicateSets(regime.getMaxDuplicateSets()).startDate(strategyRequest.getStartDate())
					.endDate(strategyRequest.getEndDate()).minPrice(strategyRequest.getMinPrice())
					.minQuantity(strategyRequest.getMinQuantity()).stoplossType(regime.getStoplossType())
					.takeprofitType(regime.getTakeprofitType()).systemType(strategyRequest.getSystemType())
					// Patch 72m.5: anchor passes through builder.
					.portfolioStoplossAnchor(regime.getPortfolioStoplossAnchor())
					.orderType(regime.getOrderType()).atrLimitLookback(regime.getAtrLimitLookback())
					.limitPct(regime.getLimitPct()).maxTime(regime.getMaxTime()).bannedMonths(regime.getBannedMonths())
					.tdomFilters(regime.getTdomFilters()).volFilter(regime.getVolFilter())
					.avgVolume(this.parquetFileValueMap.get("avg_volume"))
					.avgTurnover(this.parquetFileValueMap.get("avg_turnover"))
					.spyCloses(this.parquetFileValueMap.get("closes_spy")).entryRulesTree(entryTree) // ← tree for
																										// RuleTreeEvaluator
					.exitRulesTree(exitTree) // ← tree for RuleTreeEvaluator
					.build();

			// generateSignalsV1 detects trees → builds LeafCache → uses RuleTreeEvaluator
			BuySellDataV2 buySellData = this.strategyBuilderServiceV2.generateSignalsV1(strategyData, priceData);
			buySellData.getStrategyData().setMaxSameTicker(1);

			String regimeLabel = buildRegimeLabel(regime, mapper);
			rulesOfDayRegimes.put(regimeLabel, buySellData);
		}

		return rulesOfDayRegimes;
	}

	/**
	 * Generate market trend map: date → active regime label. Flattens
	 * market_trend_rules_tree, uses per-rule ticker for indicator lookup.
	 */
	public Map<LocalDate, String> getMarketTrends(StrategyBucketRequestDto strategyRequest, PriceDataV2 priceData,
			String backtestDataPath) {

		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
				.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
				.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);

		String universe = strategyRequest.getRegimes().get(0).getUniverse();
		String inputDir = inputPath(strategyRequest.getName(), universe, backtestDataPath);

		Map<String, Set<Integer>> indicatorLookbackMap = new HashMap<>();

		for (MarketRegimeDto regime : strategyRequest.getRegimes()) {
			// Flatten market_trend_rules_tree → flat list
			Object treeObj = regime.getMarketTrendRulesTree();
			if (treeObj != null) {
				RuleGroupNodeDto trendTree = mapper.convertValue(treeObj, RuleGroupNodeDto.class);
				List<RuleDto> flatRules = RuleTreeFlattener.flatten(trendTree);
				regime.setMarketTrendRules(flatRules);
			}

			// Build indicator lookback map — use per-rule ticker
			if (regime.getMarketTrendRules() != null) {
				for (RuleDto rule : regime.getMarketTrendRules()) {
					String ticker = (rule.getRegimeTicker() != null ? rule.getRegimeTicker() : "").toLowerCase();
					String key = String.format("%s_%s", ticker, rule.getIndicator());
					indicatorLookbackMap.computeIfAbsent(key, k -> new HashSet<>()).add(rule.getLookback());
				}
			}
		}

		// Load market trend indicator parquets
		Map<String, ArrowDataFrame> marketTrendMap = IndicatorRuleLoader
				.loadTablesMarketTrendV3(strategyRequest.getRegimes(), inputDir, indicatorLookbackMap);
		allArrowMaps.add(marketTrendMap);

		return this.marketTrendServiceV2.generateMarketTrend(strategyRequest.getRegimes(), marketTrendMap, priceData);
	}

	// ── Private helpers ─────────────────────────────────────────────

	private RegimeOverlay loadRegimeOverlay(StrategyBucketRequestDto strategyRequest, MarketRegimeDto regime,
			String backtestDataPath) {

		String universe = regime.getUniverse();
		RegimeOverlay overlay = RegimeOverlay.builder().build();

		PriceLoader regimeLoader = PriceLoader.builder().universe(universe).rebalance(strategyRequest.getRebalance())
				.atrLimitLookback(regime.getAtrLimitLookback()).atrLookbackStp(regime.getAtrLookbackStp())
				.atrLookbackTp(regime.getAtrLookbackTp()).rankingIndicator(regime.getRanking())
				.rankingLookback(regime.getRankingLookback()).build();

		Map<String, String> rebalanceFiles = regimeLoader.getFilesForRebalance(strategyRequest.getRegimes());

		for (String label : Arrays.asList("atr_limit", "atr_stp", "atr_tp", "ranking")) {
			try {
				String filePath = rebalanceFiles.get(label);
				if (filePath == null || filePath.isBlank() || filePath.contains("null")) {
					continue;
				}
				ArrowDataFrame df = cache.load(path(strategyRequest.getName(), filePath, universe, backtestDataPath));
				if (label.contains("atr")) {
					overlay.setDailyAtr(df);
				} else {
					overlay.setRanking(df);
				}
				parquetFileValueMap.put(label + "_" + regime.getId(), df);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return overlay;
	}

	private String buildRegimeLabel(MarketRegimeDto regime, ObjectMapper mapper) {
		Object treeObj = regime.getMarketTrendRulesTree();
		List<RuleDto> trendRules;
		if (treeObj != null) {
			RuleGroupNodeDto trendTree = mapper.convertValue(treeObj, RuleGroupNodeDto.class);
			trendRules = RuleTreeFlattener.flatten(trendTree);
		} else {
			trendRules = regime.getMarketTrendRules() != null ? regime.getMarketTrendRules() : List.of();
		}
		StringBuilder sb = new StringBuilder();
		for (RuleDto rule : trendRules) {
			sb.append(rule.getLabel()).append("_");
		}
		if (sb.length() > 0) {
			sb.setLength(sb.length() - 1);
		}
		return sb.toString();
	}

	public void writeBacktestResponse(StrategyBucketRequestDto strategyRequest, BacktestReponseDto backtestResponse,
			String backtestOPath) {
		// Setup Jackson ObjectMapper
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule()); // Handles LocalDate, etc.
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // Use ISO-8601 dates
		mapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty print

		try {
			// Serialize to JSON files
			mapper.writeValue(Paths.get(
					String.format("%s/%s/%s/%s.json", backtestOPath, strategyRequest.getName(), "output", "Equity"))
					.toFile(), backtestResponse.getEquityLogger());

			mapper.writeValue(Paths.get(
					String.format("%s/%s/%s/%s.json", backtestOPath, strategyRequest.getName(), "output", "TradeList"))
					.toFile(), backtestResponse.getTradeLogger());

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	@Override
	public void close() {
		if (parquetFileValueMap != null) {
			parquetFileValueMap.values().stream().filter(Objects::nonNull).forEach(df -> {
				try {
					df.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
			parquetFileValueMap.clear();
		}
		if (parquetMapSet != null) {
			parquetMapSet.values().stream().filter(Objects::nonNull).forEach(df -> {
				try {
					df.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
			parquetMapSet.clear();
		}
		// Close all Arrow resources from Simple regime processing
		for (Map<String, ArrowDataFrame> arrowMap : allArrowMaps) {
			IndicatorRuleLoader.closeTables(arrowMap);
		}
		allArrowMaps.clear();
		if (parquetDatesMapList != null) {
			parquetDatesMapList.clear();
		}
	}

}