package com.backtest.engine.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.backtest.engine.config.StaticConfig;
import com.backtest.engine.dto.request.MarketRegimeDto;
import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.ruleBuilder.RuleParser;

public class IndicatorRuleLoader {

	// 1. Declare it as a static constant
	private static final Map<String, String> PRICE_MAP = Map.of("unadjusted_close", "DAILY_unadjusted_closes", "close",
			"DAILY_closes");
	
	private static final Set<String> PATTERN_INDICATORS = buildPatternIndicators();

	private static Set<String> buildPatternIndicators() {
	    Set<String> m = new HashSet<>();

	    // --- Pattern / price-action style ---
	    m.add("n_week_high_recent");

	    return Collections.unmodifiableSet(m);
	}
	
	

	public static Map<String, ArrowDataFrame> loadTables(List<RuleDto> conditions, String dataDir) {
		Map<String, ArrowDataFrame> tableMap = new HashMap<>();

		for (RuleDto rc : conditions) {
			String fileName;
			String valueIndicatorFileName;
			
			if (rc.getIndicator().equals(StaticConfig.N_WEEK_HIGH_RECENT)) {
				
			    int nWeeks = (Integer) rc.getParams().get("n_week_days");
			    int within = (Integer) rc.getParams().get("within_days");
			    fileName = String.format("%s_%s_%s.parquet", rc.getIndicator(), nWeeks, within);
			}
			else if (rc.getIndicator().equals(StaticConfig.SHARPE)) {
			    String sharpeKey = StaticConfig.getSharpeKey(rc);
			    fileName = sharpeKey + ".parquet";
			}
			else if (PRICE_MAP.containsKey(rc.getIndicator())) {
				fileName = RuleParser.buildParquetFileName(PRICE_MAP.get(rc.getIndicator()));
			} else {
				fileName = RuleParser.buildParquetFileName(rc.getIndicator(), rc.getLookback());
			}

			Path parquetPath = Paths.get(dataDir, fileName);
			try {
				String lookupKey;
				if (rc.getIndicator().equals(StaticConfig.N_WEEK_HIGH_RECENT)) {
				    lookupKey = StaticConfig.getN_WEEK_HIGH_RECENT(rc);
				} else if (rc.getIndicator().equals(StaticConfig.SHARPE)) {
				    lookupKey = StaticConfig.getSharpeKey(rc);
				} else {
				    lookupKey = rc.getIndicator() + "_" + rc.getLookback();
				}

				if (!tableMap.containsKey(lookupKey)) {
					ArrowDataFrame indicatorMap = ArrowDataFrame.load(parquetPath.toUri().toString());
					tableMap.put(lookupKey, indicatorMap);
				}

//				Value Type indicator price
				if (rc.getValueType().equals("indicator_price")) {

					if (PRICE_MAP.containsKey(rc.getValueIndicator())) {
						valueIndicatorFileName = RuleParser.buildParquetFileName(PRICE_MAP.get(rc.getIndicator()));
					} else {
						valueIndicatorFileName = RuleParser.buildParquetFileName(rc.getValueIndicator(),rc.getValueLookback());

					}

					Path valueIndicatorParquetPath = Paths.get(dataDir, valueIndicatorFileName);

					if (!tableMap.containsKey(rc.getValueIndicator() + "_" + rc.getValueLookback())) {
						ArrowDataFrame indicatorMap = ArrowDataFrame.load(valueIndicatorParquetPath.toUri().toString());
						tableMap.put(rc.getValueIndicator() + "_" + rc.getValueLookback(), indicatorMap);
					}

				}

			} catch (Exception e) {
				// Replace with proper logging (e.g., SLF4J or Log4j) or rethrow for better
				// error handling
				System.err.println("Failed to load parquet file: " + parquetPath + " - " + e.getMessage());
				e.printStackTrace();
			}

		}

		return tableMap;
	}

	// Optional utility method to close all dataframes in the map
	public static void closeTables(Map<String, ArrowDataFrame> tableMap) {
		if (tableMap != null) {
			tableMap.values().forEach(df -> {
				try {
					if (df != null) {
						df.close();
					}
				} catch (Exception e) {
					System.err.println("Error closing ArrowDataFrame: " + e.getMessage());
					e.printStackTrace();
				}
			});
		}
	}

	public static Map<String, ArrowDataFrame> loadTablesMarketTrend(List<RuleDto> marketTrendRuleConditions,
			String dataDir, String regimeTicker) {
		Map<String, ArrowDataFrame> tableMap = new HashMap<>();

		for (RuleDto rc : marketTrendRuleConditions) {
			String fileName;
			if (rc.getIndicator().equals("unadjusted_close")) {
				fileName = RuleParser.buildParquetFileName("DAILY_unadjusted_closes");
			} else {
				fileName = RuleParser.buildParquetFileNameMarketTrend(regimeTicker, rc.getIndicator(),
						rc.getLookback());
			}

			Path parquetPath = Paths.get(dataDir, fileName);
			try {
				if (!tableMap.containsKey(rc.getIndicator() + "_" + rc.getLookback())) {
					ArrowDataFrame indicatorMap = ArrowDataFrame.load(parquetPath.toUri().toString());
					tableMap.put(rc.getIndicator() + "_" + rc.getLookback(), indicatorMap);
				}

			} catch (Exception e) {
				// Replace with proper logging (e.g., SLF4J or Log4j) or rethrow for better
				// error handling
				System.err.println("Failed to load parquet file: " + parquetPath + " - " + e.getMessage());
				e.printStackTrace();
			}
		}

		return tableMap;
	}

	public static Map<String, ArrowDataFrame> loadTablesMarketTrend(List<MarketRegimeDto> regimes, String dataDir,
			Map<String, Set<Integer>> indicatorLookbackMap) {

		Map<String, ArrowDataFrame> tableMap = new HashMap<>();

		for (MarketRegimeDto regime : regimes) {
			for (RuleDto rule : regime.getMarketTrendRules()) {

				// Key for lookup in indicatorLookbackMap
				String lookupKey = String.format("%s_%s", regime.getRegimeTicker().toLowerCase(), rule.getIndicator());

				// Skip if not found in the indicatorLookbackMap
				if (!indicatorLookbackMap.containsKey(lookupKey)) {
					continue;
				}

				// Unique key for storing in tableMap
				String tableKey = String.format("%s_%s_%d", regime.getRegimeTicker().toLowerCase(), rule.getIndicator(),
						rule.getLookback());

				// Build file path
				String fileName = RuleParser.buildParquetFileNameMarketTrend(regime.getRegimeTicker().toLowerCase(),
						rule.getIndicator(), rule.getLookback());
				Path parquetPath = Paths.get(dataDir, fileName);

				// Load ArrowDataFrame only if not already loaded
				tableMap.computeIfAbsent(tableKey, k -> {
					try {
						return ArrowDataFrame.load(parquetPath.toUri().toString());
					} catch (Exception e) {
						e.printStackTrace();
						return null;
					}
				});

				// Remove entry from indicatorLookbackMap (if intentional)
				indicatorLookbackMap.remove(lookupKey);
			}
		}

		// Clean up null entries if load failed
		tableMap.values().removeIf(Objects::isNull);

		return tableMap;
	}

	/**
	 * V3: Load market trend indicator parquets using per-rule ticker (rule.getRegimeTicker())
	 * instead of per-regime ticker (regime.getRegimeTicker()).
	 */
	public static Map<String, ArrowDataFrame> loadTablesMarketTrendV3(List<MarketRegimeDto> regimes, String dataDir,
			Map<String, Set<Integer>> indicatorLookbackMap) {

		// Price indicators that come from OHLC data, not separate parquet files
		Set<String> PRICE_INDICATORS = Set.of("close", "open", "high", "low");

		Map<String, ArrowDataFrame> tableMap = new HashMap<>();

		for (MarketRegimeDto regime : regimes) {
			for (RuleDto rule : regime.getMarketTrendRules()) {

				String ticker = (rule.getRegimeTicker() != null ? rule.getRegimeTicker() : "").toLowerCase();

				// Load primary indicator (LHS) — skip price indicators
				if (!PRICE_INDICATORS.contains(rule.getIndicator().toLowerCase())) {
					String tableKey = String.format("%s_%s_%d", ticker, rule.getIndicator(), rule.getLookback());
					String fileName = RuleParser.buildParquetFileNameMarketTrend(ticker, rule.getIndicator(), rule.getLookback());
					Path parquetPath = Paths.get(dataDir, fileName);

					tableMap.computeIfAbsent(tableKey, k -> {
						try { return ArrowDataFrame.load(parquetPath.toUri().toString()); }
						catch (Exception e) { e.printStackTrace(); return null; }
					});
				}

				// Load value indicator (RHS) — for indicator_price comparisons
				if ("indicator_price".equalsIgnoreCase(rule.getValueType())
						&& rule.getValueIndicator() != null
						&& !rule.getValueIndicator().isBlank()
						&& !PRICE_INDICATORS.contains(rule.getValueIndicator().toLowerCase())) {

					String valKey = String.format("%s_%s_%d", ticker, rule.getValueIndicator(), rule.getValueLookback());
					String valFileName = RuleParser.buildParquetFileNameMarketTrend(ticker, rule.getValueIndicator(), rule.getValueLookback());
					Path valPath = Paths.get(dataDir, valFileName);

					tableMap.computeIfAbsent(valKey, k -> {
						try { return ArrowDataFrame.load(valPath.toUri().toString()); }
						catch (Exception e) { e.printStackTrace(); return null; }
					});
				}
			}
		}

		tableMap.values().removeIf(Objects::isNull);

		return tableMap;
	}

	public static Map<String, ArrowDataFrame> loadTables(List<RuleDto> conditions, String dataDir,
			Map<String, Set<Integer>> indicatorLookbackMap) {

		Map<String, ArrowDataFrame> tableMap = new HashMap<>();

		for (RuleDto rc : conditions) {
			String fileName;
			if (rc.getIndicator().equals("unadjusted_close")) {
				fileName = RuleParser.buildParquetFileName("DAILY_unadjusted_closes");
			} else {
				fileName = RuleParser.buildParquetFileName(rc.getIndicator(), rc.getLookback());
			}

			Path parquetPath = Paths.get(dataDir, fileName);
			try {
				if (!tableMap.containsKey(rc.getIndicator() + "_" + rc.getLookback())) {
					ArrowDataFrame indicatorMap = ArrowDataFrame.load(parquetPath.toUri().toString());
					tableMap.put(rc.getIndicator() + "_" + rc.getLookback(), indicatorMap);
				}

			} catch (Exception e) {
				// Replace with proper logging (e.g., SLF4J or Log4j) or rethrow for better
				// error handling
				System.err.println("Failed to load parquet file: " + parquetPath + " - " + e.getMessage());
				e.printStackTrace();
			}
		}

		return tableMap;

	}

}