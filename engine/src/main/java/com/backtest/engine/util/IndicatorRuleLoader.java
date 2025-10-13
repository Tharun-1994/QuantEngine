package com.backtest.engine.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.backtest.engine.dto.request.MarketRegimeDto;
import com.backtest.engine.dto.request.RuleDto;

public class IndicatorRuleLoader {
	public static Map<String, ArrowDataFrame> loadTables(List<RuleDto> conditions, String dataDir) {
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

	public static Map<String, ArrowDataFrame> loadTablesMarketTrend(
	        List<MarketRegimeDto> regimes,
	        String dataDir,
	        Map<String, Set<Integer>> indicatorLookbackMap) {

	    Map<String, ArrowDataFrame> tableMap = new HashMap<>();

	    for (MarketRegimeDto regime : regimes) {
	        for (RuleDto rule : regime.getMarketTrendRules()) {

	            // Key for lookup in indicatorLookbackMap
	            String lookupKey = String.format("%s_%s", 
	                    regime.getRegimeTicker().toLowerCase(),
	                    rule.getIndicator());

	            // Skip if not found in the indicatorLookbackMap
	            if (!indicatorLookbackMap.containsKey(lookupKey)) {
	                continue;
	            }

	            // Unique key for storing in tableMap
	            String tableKey = String.format("%s_%s_%d",
	                    regime.getRegimeTicker().toLowerCase(),
	                    rule.getIndicator(),
	                    rule.getLookback());

	            // Build file path
	            String fileName = RuleParser.buildParquetFileNameMarketTrend(
	                    regime.getRegimeTicker().toLowerCase(),
	                    rule.getIndicator(),
	                    rule.getLookback());
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