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

import com.backtest.engine.config.ArrowDataFrameCache;
import com.backtest.engine.config.StaticConfig;
import com.backtest.engine.dto.request.MarketRegimeDto;
import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.ruleBuilder.RuleParser;

public class IndicatorRuleLoader {

	// ─────────────────────────────────────────────────────────────────────────
	// Service Locator bootstrap: ArrowDataFrameCache calls setCache(...) at
	// startup via @PostConstruct. When set, static load methods route through
	// the cache; otherwise they fall back to direct disk reads. This lets us
	// add caching to legacy static-call sites without refactoring callers.
	// ─────────────────────────────────────────────────────────────────────────
	private static volatile ArrowDataFrameCache cache;

	public static void setCache(ArrowDataFrameCache c) {
		cache = c;
	}

	/** Single chokepoint for all indicator parquet loads inside this class. */
	private static ArrowDataFrame loadIndicator(String uri) throws Exception {
		ArrowDataFrameCache c = cache;
		if (c != null) {
			return c.load(uri);
		}
		return ArrowDataFrame.load(uri);
	}

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
			} else if (rc.getIndicator().equals(StaticConfig.SHARPE)) {
				String sharpeKey = StaticConfig.getSharpeKey(rc);
				fileName = sharpeKey + ".parquet";
			} else if (PRICE_MAP.containsKey(rc.getIndicator())) {
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
					ArrowDataFrame indicatorMap = loadIndicator(parquetPath.toUri().toString());
					tableMap.put(lookupKey, indicatorMap);
				}

//				Value Type indicator price
				if (rc.getValueType().equals("indicator_price")) {

					if (PRICE_MAP.containsKey(rc.getValueIndicator())) {
						valueIndicatorFileName = RuleParser.buildParquetFileName(PRICE_MAP.get(rc.getIndicator()));
					} else {
						valueIndicatorFileName = RuleParser.buildParquetFileName(rc.getValueIndicator(),
								rc.getValueLookback());

					}

					Path valueIndicatorParquetPath = Paths.get(dataDir, valueIndicatorFileName);

					if (!tableMap.containsKey(rc.getValueIndicator() + "_" + rc.getValueLookback())) {
						ArrowDataFrame indicatorMap = loadIndicator(valueIndicatorParquetPath.toUri().toString());
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
					ArrowDataFrame indicatorMap = loadIndicator(parquetPath.toUri().toString());
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
						return loadIndicator(parquetPath.toUri().toString());
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
	 * V3: Load market trend indicator parquets using per-rule ticker
	 * (rule.getRegimeTicker()) instead of per-regime ticker
	 * (regime.getRegimeTicker()).
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
					String fileName = RuleParser.buildParquetFileNameMarketTrend(ticker, rule.getIndicator(),
							rule.getLookback());
					Path parquetPath = Paths.get(dataDir, fileName);

					tableMap.computeIfAbsent(tableKey, k -> {
						try {
							return loadIndicator(parquetPath.toUri().toString());
						} catch (Exception e) {
							e.printStackTrace();
							return null;
						}
					});
				}

				// Load value indicator (RHS) — for indicator_price comparisons
				if ("indicator_price".equalsIgnoreCase(rule.getValueType()) && rule.getValueIndicator() != null
						&& !rule.getValueIndicator().isBlank()
						&& !PRICE_INDICATORS.contains(rule.getValueIndicator().toLowerCase())) {

					String valKey = String.format("%s_%s_%d", ticker, rule.getValueIndicator(),
							rule.getValueLookback());
					String valFileName = RuleParser.buildParquetFileNameMarketTrend(ticker, rule.getValueIndicator(),
							rule.getValueLookback());
					Path valPath = Paths.get(dataDir, valFileName);

					tableMap.computeIfAbsent(valKey, k -> {
						try {
							return loadIndicator(valPath.toUri().toString());
						} catch (Exception e) {
							e.printStackTrace();
							return null;
						}
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
					ArrowDataFrame indicatorMap = loadIndicator(parquetPath.toUri().toString());
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

	// ─────────────────────────────────────────────────────────────────────────
	// LRA Patch 35 — auto-collect + auto-load indicators referenced by the
	// LRA-specific JSON config (entry_rules_tree_long / _short + sizing_policy
	// conditional indicator). The existing scan loops in BacktestEngineController
	// only inspect the legacy flat entry_rules / exit_rules / market_trend_rules
	// lists; this fills the gap for LONGSHORT system_type.
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Walks an LRA regime's JSON config and returns every (indicator, lookback)
	 * pair it references. Sources scanned: - entry_rules_tree_long (recursive
	 * group/leaf tree) - entry_rules_tree_short (recursive group/leaf tree) -
	 * sizing_policy.params.conditional_on (+ optional conditional_on_lookback)
	 *
	 * Returns an empty map when the regime has no LRA fields populated — so
	 * existing ROC strategies pay zero cost when this is called against them.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Set<Integer>> collectLraIndicators(MarketRegimeDto regime) {
		Map<String, Set<Integer>> result = new HashMap<>();
		if (regime == null) {
			return result;
		}

		walkTreeForIndicators(regime.getEntryRulesTreeLong(), result);
		walkTreeForIndicators(regime.getEntryRulesTreeShort(), result);

		Map<String, Object> policy = regime.getSizingPolicy();
		if (policy != null) {
			Map<String, Object> params = (Map<String, Object>) policy.get("params");
			if (params != null) {
				Object conditionalOn = params.get("conditional_on");
				if (conditionalOn instanceof String && !((String) conditionalOn).isEmpty()) {
					Object lbObj = params.get("conditional_on_lookback");
					int lb = (lbObj instanceof Number) ? ((Number) lbObj).intValue() : 0;
					result.computeIfAbsent((String) conditionalOn, k -> new HashSet<>()).add(lb);
				}
			}
		}
		return result;
	}

	/**
	 * Recursive tree-walker. Group nodes ({"type":"group", "children":[...]})
	 * recurse on children. Leaf nodes contribute (indicator, lookback). Lookback
	 * defaults to 0 when absent. top_n_universe leaves are not special-cased — they
	 * reference an indicator like any other comparison node.
	 */
	@SuppressWarnings("unchecked")
	private static void walkTreeForIndicators(Map<String, Object> node, Map<String, Set<Integer>> sink) {
		if (node == null) {
			return;
		}
		String type = (String) node.get("type");
		if ("group".equals(type)) {
			List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
			if (children != null) {
				for (Map<String, Object> child : children) {
					walkTreeForIndicators(child, sink);
				}
			}
			return;
		}
		// Leaf
		String indicator = (String) node.get("indicator");
		if (indicator == null || indicator.isEmpty()) {
			return;
		}
		Object lbObj = node.get("lookback");
		int lookback = (lbObj instanceof Number) ? ((Number) lbObj).intValue() : 0;
		sink.computeIfAbsent(indicator, k -> new HashSet<>()).add(lookback);
	}

	/**
	 * Loads every parquet referenced in indicatorLookbacks into an ArrowDataFrame
	 * map keyed "indicator_lookback". Existing entries in tableMap aren't checked
	 * here — caller is expected to merge with putAll into the regime's entryMap.
	 * Failures are logged and the offending entry is skipped (consistent with
	 * loadTables' resilience pattern).
	 */
	public static Map<String, ArrowDataFrame> loadFromMap(Map<String, Set<Integer>> indicatorLookbacks,
			String dataDir) {
		Map<String, ArrowDataFrame> tableMap = new HashMap<>();
		if (indicatorLookbacks == null || indicatorLookbacks.isEmpty()) {
			return tableMap;
		}
		for (Map.Entry<String, Set<Integer>> e : indicatorLookbacks.entrySet()) {
			String indicator = e.getKey();
			for (Integer lookback : e.getValue()) {
				int lb = (lookback != null) ? lookback : 0;
				String key = indicator + "_" + lb;
				if (tableMap.containsKey(key)) {
					continue;
				}
				String fileName = RuleParser.buildParquetFileName(indicator, lb);
				Path parquetPath = Paths.get(dataDir, fileName);
				try {
					ArrowDataFrame df = loadIndicator(parquetPath.toUri().toString());
					tableMap.put(key, df);
				} catch (Exception ex) {
					System.err.println(
							"LRA Patch 35: failed to load indicator parquet: " + parquetPath + " - " + ex.getMessage());
					ex.printStackTrace();
				}
			}
		}
		return tableMap;
	}

}
