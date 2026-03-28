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

import com.backtest.engine.dto.request.MarketRegimeDto;
import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.dto.request.RuleGroupNodeDto;
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
	
	Map<String, List<LocalDate>> parquetDatesMapList;
	Map<String, ArrowDataFrame> parquetFileValueMap;
	Map<String, ArrowStringDataFrame> parquetMapSet;

	// Track all Arrow resources opened during Simple regime processing
	private final List<Map<String, ArrowDataFrame>> allArrowMaps = new ArrayList<>();
	
	public BacktestContext(PriceDataLoaderService priceDataService,
			StrategyBuilderServiceV2 strategyBuilderServiceV2,
			MarketTrendServiceV2 marketTrendServiceV2) {
		this.priceDataService = priceDataService;
		this.strategyBuilderServiceV2 = strategyBuilderServiceV2;
		this.marketTrendServiceV2 = marketTrendServiceV2;
	}
	
	public String inputPath(String strategy_name, String universe,String backtestDataPath) {
		return String.format("%s/%s/%s/%s", backtestDataPath, strategy_name, "input", universe);
	}
	public String path(String strategy_name, String fileName, String universe,String backtestDataPath) {

		if (fileName.startsWith("trading_") || fileName.startsWith("all_dates")) {
			return Paths.get(inputPath(strategy_name, universe,backtestDataPath), fileName).toString();
		}
		return Paths.get(inputPath(strategy_name, universe,backtestDataPath), fileName).toUri().toString();
	}
	
	
	public PriceDataV2 getPriceData(StrategyBucketRequestDto strategyRequest,String backtestDataPath) {
		this.parquetDatesMapList = new HashMap<>();
		this.parquetFileValueMap = new HashMap<>();
		this.parquetMapSet = new HashMap<>();	
		
		Map<Integer, Map<String, String>> priceLoaderPathMap = new HashMap<>();
		int i = 0;
		for (MarketRegimeDto marketRegime : strategyRequest.getRegimes()) {
			PriceLoader priceLoader = PriceLoader.builder().universe(marketRegime.getUniverse())
					.atrLimitLookback(marketRegime.getAtrLimitLookback())
					.atrLookbackStp(marketRegime.getAtrLookbackStp()).atrLookbackTp(marketRegime.getAtrLookbackTp())
					.rebalance(strategyRequest.getRebalance()).rankingIndicator(marketRegime.getRanking())
					.rankingLookback(marketRegime.getRankingLookback()).build();

			priceLoaderPathMap.put(i, priceLoader.getFilesForRebalance(strategyRequest.getRegimes()));
			i++;
		}
		
		
		for (Integer regimeIndex : priceLoaderPathMap.keySet()) {
			for (String keyFile : priceLoaderPathMap.get(regimeIndex).keySet()) {
				try {
					if (keyFile.equals("trading_dates") || keyFile.equals("all_dates")) {
						List<LocalDate> eachList = ParquetToMap
								.loadParquetToDateList(path(strategyRequest.getName(),priceLoaderPathMap.get(regimeIndex).get(keyFile),
										strategyRequest.getRegimes().get(0).getUniverse(),backtestDataPath));
						parquetDatesMapList.put(keyFile, eachList);
					} else if (keyFile.equals("universes")) {
						ArrowStringDataFrame universe = ArrowStringDataFrame
								.load(path(strategyRequest.getName(),priceLoaderPathMap.get(regimeIndex).get(keyFile),
										strategyRequest.getRegimes().get(0).getUniverse(),backtestDataPath));
						parquetMapSet.put(keyFile, universe);
					} else {
						if ((keyFile.equals("atr_limit")
								&& (priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
										|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty()))
								|| keyFile.equals("atr_stp")
										&& (priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
												|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty())
								|| keyFile.equals("atr_tp")
										&& (priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
												|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty())) {
							continue;
						}
						
						if(priceLoaderPathMap.get(regimeIndex).get(keyFile) == null || priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
								|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty() || priceLoaderPathMap.get(regimeIndex).get(keyFile).contains("null")) {
							continue;
						}
						
						ArrowDataFrame currentDf = ArrowDataFrame.load(path(strategyRequest.getName(),priceLoaderPathMap.get(regimeIndex).get(keyFile),
										strategyRequest.getRegimes().get(0).getUniverse(),backtestDataPath));
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
		
		
		PriceDataV2 priceData = this.priceDataService.loadPricesMarketDatav2(parquetFileValueMap.get("closes"),
				parquetFileValueMap.get("opens"), parquetFileValueMap.get("highs"), parquetFileValueMap.get("lows"),
				parquetMapSet.get("universes"), parquetDatesMapList.get("trading_dates"),
				parquetDatesMapList.get("all_dates"), daily_atr,null);
		priceData.setEndDate(strategyRequest.getEndDate());
		
		return priceData;
		
	}
	
	
	
	public BuySellDataV2 getBuySellData(StrategyBucketRequestDto strategyRequest,PriceDataV2 priceData,String backtestDataPath) {
		
//		List<RuleDto> entryRuleConditions = strategyRequest.getRegimes().get(0).getEntryRules();
//		List<RuleDto> exitRuleConditions = strategyRequest.getRegimes().get(0).getExitRules();
		String univ = strategyRequest.getRegimes().get(0).getUniverse();
		ObjectMapper mapper = new ObjectMapper()
		        .findAndRegisterModules()
		        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
		        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);

		Object entryObj = strategyRequest.getRegimes().get(0).getEntryRulesTree();
		Object exitObj  = strategyRequest.getRegimes().get(0).getExitRulesTree();

		RuleGroupNodeDto entryTree = mapper.convertValue(entryObj, RuleGroupNodeDto.class);
		RuleGroupNodeDto exitTree  = mapper.convertValue(exitObj,  RuleGroupNodeDto.class);
		
		List<RuleDto> entryLeafRules = RuleTreeFlattener.flatten(entryTree);
		List<RuleDto> exitLeafRules  = RuleTreeFlattener.flatten(exitTree);
		
		Map<String, ArrowDataFrame> entryMap = IndicatorRuleLoader.loadTables(entryLeafRules,inputPath(strategyRequest.getName(), univ,backtestDataPath));
		Map<String, ArrowDataFrame> exitMap = IndicatorRuleLoader.loadTables(exitLeafRules, inputPath(strategyRequest.getName(), univ,backtestDataPath));

		// Load sector mapping if sector filter is enabled
		Map<String, String> sectorMap = null;
		int sectorLevel = strategyRequest.getRegimes().get(0).getSectorLevel();
		int sectorLimit = strategyRequest.getRegimes().get(0).getSectorLimit();
		if (sectorLevel > 0 && sectorLimit > 0) {
			try {
				String sectorPath = inputPath(strategyRequest.getName(), univ, backtestDataPath) + "/sector_mapping.parquet";
				sectorMap = ParquetToMap.loadSectorMapping(sectorPath, sectorLevel);
			} catch (Exception e) {
				System.err.println("[WARNING] Could not load sector mapping: " + e.getMessage());
			}
		}

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
				.sectorMap(sectorMap)
				.startDate(strategyRequest.getStartDate()).endDate(strategyRequest.getEndDate())
				.minPrice(strategyRequest.getMinPrice()).minQuantity(strategyRequest.getMinQuantity())
				.stoplossType(strategyRequest.getRegimes().get(0).getStoplossType())
				.takeprofitType(strategyRequest.getRegimes().get(0).getTakeprofitType())
				.systemType(strategyRequest.getSystemType())
				.orderType(strategyRequest.getRegimes().get(0).getOrderType())
				.atrLimitLookback(strategyRequest.getRegimes().get(0).getAtrLimitLookback())
				.limitPct(strategyRequest.getRegimes().get(0).getLimitPct())
				.maxTime(strategyRequest.getRegimes().get(0).getMaxTime())
				.bannedMonths(strategyRequest.getRegimes().get(0).getBannedMonths())
				.entryRulesTree(entryTree)
				.exitRulesTree(exitTree)
				.build();
		
		BuySellDataV2 buySellData = this.strategyBuilderServiceV2.generateSignalsV1(strategyData,priceData);
		buySellData.setStrategyData(strategyData);
		return buySellData;
	}
	
	
	// ==================================================================
	//  SIMPLE REGIME — Multi-regime with market trend overlay
	// ==================================================================

	/**
	 * Load shared price data for Simple regime.
	 * Loads OHLC, universe, dates, and market ticker closes per regime.
	 */
	public PriceDataV2 getSimplePriceData(StrategyBucketRequestDto strategyRequest, String backtestDataPath) {
		this.parquetDatesMapList = new HashMap<>();
		this.parquetFileValueMap = new HashMap<>();
		this.parquetMapSet = new HashMap<>();

		String universe = strategyRequest.getRegimes().get(0).getUniverse();

		PriceLoader coreLoader = PriceLoader.builder()
				.universe(universe)
				.rebalance(strategyRequest.getRebalance())
				.build();

		Map<String, String> priceLoaderPathMap = coreLoader.getFilesForRebalance(strategyRequest.getRegimes());

		for (String keyFile : priceLoaderPathMap.keySet()) {
			try {
				if (keyFile.equals("trading_dates") || keyFile.equals("all_dates")) {
					List<LocalDate> eachList = ParquetToMap.loadParquetToDateList(
							path(strategyRequest.getName(), priceLoaderPathMap.get(keyFile), universe, backtestDataPath));
					parquetDatesMapList.put(keyFile, eachList);
				} else if (keyFile.equals("universes")) {
					ArrowStringDataFrame sharedUniverse = ArrowStringDataFrame
							.load(path(strategyRequest.getName(), priceLoaderPathMap.get(keyFile), universe, backtestDataPath));
					parquetMapSet.put(keyFile, sharedUniverse);
				} else if (priceLoaderPathMap.get(keyFile) != null
						&& !keyFile.contains("ranking") && !keyFile.contains("atr_")) {
					ArrowDataFrame currentDf = ArrowDataFrame
							.load(path(strategyRequest.getName(), priceLoaderPathMap.get(keyFile), universe, backtestDataPath));
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

		PriceDataV2 priceData = this.priceDataService.loadPricesMarketDatav2(
				parquetFileValueMap.get("closes"),
				parquetFileValueMap.get("opens"),
				parquetFileValueMap.get("highs"),
				parquetFileValueMap.get("lows"),
				parquetMapSet.get("universes"),
				parquetDatesMapList.get("trading_dates"),
				parquetDatesMapList.get("all_dates"),
				null,
				marketTickerPrices);
		priceData.setEndDate(strategyRequest.getEndDate());

		return priceData;
	}

	/**
	 * Build BuySellDataV2 per regime for Simple type.
	 * Uses tree evaluation (RuleTreeCache + RuleTreeEvaluator) — not flattening.
	 * Returns Map keyed by market trend label → BuySellDataV2.
	 */
	public Map<String, BuySellDataV2> getSimpleBuySellDataMap(
			StrategyBucketRequestDto strategyRequest,
			PriceDataV2 priceData,
			String backtestDataPath) {

		Map<String, BuySellDataV2> rulesOfDayRegimes = new HashMap<>();
		ObjectMapper mapper = new ObjectMapper()
				.findAndRegisterModules()
				.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
				.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);

		// Load sector mapping once (shared across all regimes)
		Map<String, String> simpleSectorMap = null;
		int simpleSectorLevel = strategyRequest.getRegimes().get(0).getSectorLevel();
		int simpleSectorLimit = strategyRequest.getRegimes().get(0).getSectorLimit();
		if (simpleSectorLevel > 0 && simpleSectorLimit > 0) {
			try {
				String universe0 = strategyRequest.getRegimes().get(0).getUniverse();
				String sectorPath = inputPath(strategyRequest.getName(), universe0, backtestDataPath) + "/sector_mapping.parquet";
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
			StrategyDataV2 strategyData = StrategyDataV2.builder()
					.entryRulesList(entryLeafRules)
					.exitRuleList(exitLeafRules)
					.entryIndicators(entryMap)
					.exitIndicators(exitMap)
					.startingCapital(regime.getCapital())
					.slots(regime.getSlots())
					.stopLossPct(regime.getStoplossPct())
					.takeProfitPct(regime.getTakeprofitPct())
					.stoplossTiming(regime.getStoplossTiming())
					.takeprofitTiming(regime.getTakeprofitTiming())
					.entryTiming(regime.getEntryTiming())
					.exitTiming(regime.getExitTiming())
					.ranking(overlay.getRanking())
					.rankingOrder(regime.getRankingOrder())
					.sectorMap(simpleSectorMap)
					.sectorLevel(simpleSectorLevel)
					.sectorLimit(simpleSectorLimit)
					.startDate(strategyRequest.getStartDate())
					.endDate(strategyRequest.getEndDate())
					.minPrice(strategyRequest.getMinPrice())
					.minQuantity(strategyRequest.getMinQuantity())
					.stoplossType(regime.getStoplossType())
					.takeprofitType(regime.getTakeprofitType())
					.systemType(strategyRequest.getSystemType())
					.orderType(regime.getOrderType())
					.atrLimitLookback(regime.getAtrLimitLookback())
					.limitPct(regime.getLimitPct())
					.maxTime(regime.getMaxTime())
					.bannedMonths(regime.getBannedMonths())
					.entryRulesTree(entryTree)   // ← tree for RuleTreeEvaluator
					.exitRulesTree(exitTree)      // ← tree for RuleTreeEvaluator
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
	 * Generate market trend map: date → active regime label.
	 * Flattens market_trend_rules_tree, uses per-rule ticker for indicator lookup.
	 */
	public Map<LocalDate, String> getMarketTrends(
			StrategyBucketRequestDto strategyRequest,
			PriceDataV2 priceData,
			String backtestDataPath) {

		ObjectMapper mapper = new ObjectMapper()
				.findAndRegisterModules()
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
					indicatorLookbackMap
							.computeIfAbsent(key, k -> new HashSet<>())
							.add(rule.getLookback());
				}
			}
		}

		// Load market trend indicator parquets
		Map<String, ArrowDataFrame> marketTrendMap = IndicatorRuleLoader.loadTablesMarketTrendV3(
				strategyRequest.getRegimes(), inputDir, indicatorLookbackMap);
		allArrowMaps.add(marketTrendMap);

		return this.marketTrendServiceV2.generateMarketTrend(
				strategyRequest.getRegimes(), marketTrendMap, priceData);
	}

	// ── Private helpers ─────────────────────────────────────────────

	private RegimeOverlay loadRegimeOverlay(
			StrategyBucketRequestDto strategyRequest,
			MarketRegimeDto regime,
			String backtestDataPath) {

		String universe = regime.getUniverse();
		RegimeOverlay overlay = RegimeOverlay.builder().build();

		PriceLoader regimeLoader = PriceLoader.builder()
				.universe(universe)
				.rebalance(strategyRequest.getRebalance())
				.atrLimitLookback(regime.getAtrLimitLookback())
				.atrLookbackStp(regime.getAtrLookbackStp())
				.atrLookbackTp(regime.getAtrLookbackTp())
				.rankingIndicator(regime.getRanking())
				.rankingLookback(regime.getRankingLookback())
				.build();

		Map<String, String> rebalanceFiles = regimeLoader.getFilesForRebalance(strategyRequest.getRegimes());

		for (String label : Arrays.asList("atr_limit", "atr_stp", "atr_tp", "ranking")) {
			try {
				String filePath = rebalanceFiles.get(label);
				if (filePath == null || filePath.isBlank() || filePath.contains("null")) {
					continue;
				}
				ArrowDataFrame df = ArrowDataFrame.load(
						path(strategyRequest.getName(), filePath, universe, backtestDataPath));
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

	public void writeBacktestResponse(StrategyBucketRequestDto strategyRequest,BacktestReponseDto backtestResponse,String backtestOPath) {
		// Setup Jackson ObjectMapper
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule()); // Handles LocalDate, etc.
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // Use ISO-8601 dates
		mapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty print
		
		try {
			// Serialize to JSON files
			mapper.writeValue(Paths.get(String.format("%s/%s/%s/%s.json", backtestOPath,strategyRequest.getName(),"output", "Equity")).toFile(),
					backtestResponse.getEquityLogger());

			mapper.writeValue(Paths.get(String.format("%s/%s/%s/%s.json", backtestOPath,strategyRequest.getName(),"output", "TradeList")).toFile(),
					backtestResponse.getTradeLogger());

		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}
	
	
	
	
	
	
	
	
	

	@Override
	public void close() {
	    if (parquetFileValueMap != null) {
	        parquetFileValueMap.values().stream()
	            .filter(Objects::nonNull)
	            .forEach(df -> { try { df.close(); } catch (Exception e) { e.printStackTrace(); } });
	        parquetFileValueMap.clear();
	    }
	    if (parquetMapSet != null) {
	        parquetMapSet.values().stream()
	            .filter(Objects::nonNull)
	            .forEach(df -> { try { df.close(); } catch (Exception e) { e.printStackTrace(); } });
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