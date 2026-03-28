package com.backtest.engine.service.impl;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import org.springframework.stereotype.Service;

import com.backtest.engine.dto.request.MarketRegimeDto;
import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.service.MarketTrendServiceV2;
import com.backtest.engine.util.ArrowDataFrame;

@Service("marketTrendService")
public class MarketTrendServiceV2Impl implements MarketTrendServiceV2 {

	@Override
	public Map<LocalDate, String> generateMarketTrend(List<MarketRegimeDto> marketRegimes,
			Map<String, ArrowDataFrame> marketTrendMap, PriceDataV2 priceData) {
		Map<LocalDate, String> entryRuleMap = new HashMap<>();
		for (MarketRegimeDto regime : marketRegimes) {
			Map<LocalDate, String> passedMap = generateMarketSignals(regime.getMarketTrendRules(), marketTrendMap, priceData);
			entryRuleMap.putAll(passedMap);
		}

		return entryRuleMap;
	}

	private Map<LocalDate, String> generateMarketSignals(List<RuleDto> marketRules,
			Map<String, ArrowDataFrame> marketTrendMap, PriceDataV2 priceData) {
		
		marketRules.sort(Comparator.comparingInt(rule -> {
		    String conn = rule.getConnector();
		    if ("&&".equals(conn)) return 0;
		    if ("||".equals(conn)) return 1;
		    return 2;
		}));

		Map<LocalDate, String> entryRuleMap = new HashMap<>();
		Set<LocalDate> passedDates = new HashSet<>();
		int i = 0;
		StringBuilder marketRuleBuilder = new StringBuilder();
		for (RuleDto rule : marketRules) {

			if (rule.getIndicator() != null && rule.getLookback() > -1) {

				// Each rule carries its own ticker (e.g. "SPY", "VIX")
				String ruleTicker = (rule.getRegimeTicker() != null ? rule.getRegimeTicker() : "").toLowerCase();

				// For price indicators (close, open, etc.), use price data directly
				// For computed indicators (sma, atr, etc.), use marketTrendMap
				ArrowDataFrame indicatorDf;
				String indicator = rule.getIndicator().toLowerCase();
				if ("close".equals(indicator) || "open".equals(indicator) || "high".equals(indicator) || "low".equals(indicator)) {
					indicatorDf = priceData.getMarketTickerPrices().get("closes_" + ruleTicker);
				} else {
					indicatorDf = marketTrendMap.get(ruleTicker + "_" + rule.getIndicator() + "_" + rule.getLookback());
				}

				// For indicator_price comparison (e.g. close >= sma_200), RHS is the value indicator
				// For numeric comparison (e.g. sma_200 > 0), RHS is the threshold (handled in evaluateRule)
				ArrowDataFrame rhsDf;
				if ("indicator_price".equalsIgnoreCase(rule.getValueType())
						&& rule.getValueIndicator() != null && !rule.getValueIndicator().isBlank()) {
					String valIndicator = rule.getValueIndicator().toLowerCase();
					if ("close".equals(valIndicator) || "open".equals(valIndicator) || "high".equals(valIndicator) || "low".equals(valIndicator)) {
						rhsDf = priceData.getMarketTickerPrices().get("closes_" + ruleTicker);
					} else {
						rhsDf = marketTrendMap.get(ruleTicker + "_" + rule.getValueIndicator() + "_" + rule.getValueLookback());
					}
				} else {
					rhsDf = priceData.getMarketTickerPrices().get("closes_" + ruleTicker);
				}

				if (i == 0) {
					Set<LocalDate> dates = evaluateRule(indicatorDf, rule, rhsDf);
					passedDates.addAll(dates);
				}
				else {
					RuleDto prev = marketRules.get(i - 1);
					String conn = prev.getConnector();
					
					Set<LocalDate> todays = evaluateRule(indicatorDf, rule, rhsDf);

					if ("&&".equals(conn)) {
						passedDates.retainAll(todays);
					} else if ("||".equals(conn)) {
						passedDates.addAll(todays);
					} else {
						passedDates.addAll(todays);
					}
				}
				i++;
				marketRuleBuilder.append(rule.getLabel()).append("_");

			}

		}
		

		String marketRuleOfDay = marketRuleBuilder.toString();
		
		if (marketRuleOfDay.length() > 0) {
		    marketRuleOfDay = marketRuleOfDay.substring(0, marketRuleOfDay.length() - 1);
		}
		
	    for (LocalDate d : passedDates) {
	        entryRuleMap.put(d, marketRuleOfDay);
	    }
		

		return entryRuleMap;

	}

	// 1) Define a reusable operator → lambda map
	private static final Map<String, BiPredicate<Float, Float>> OPERATOR_MAP = Map.of("<", (v, t) -> v < t, "<=",
			(v, t) -> v <= t, ">", (v, t) -> v > t, ">=", (v, t) -> v >= t, "==", (v, t) -> Float.compare(v, t) == 0,
			"!=", (v, t) -> Float.compare(v, t) != 0);

	private Set<LocalDate> evaluateRule(ArrowDataFrame marketIndicator, RuleDto rule, ArrowDataFrame marketTrendPrice) {

		BiPredicate<Float, Float> test = OPERATOR_MAP.get(rule.getOperator());

		if (rule.getValueType().equalsIgnoreCase("indicator_price")) {

			Set<LocalDate> eligibleByDate = new HashSet<>();
			List<LocalDate> sortedDates = marketIndicator.getDates().stream().sorted().toList();
			int i =0;
//			LocalDate previousDate = null;
			for (LocalDate d : sortedDates) {
				if(i > 0) {
					Map<String, Float> tickerValues = marketIndicator.getRow(d);
					Map<String, Float> tickerPrices = marketTrendPrice.getRow(d);

					if (tickerValues.get("Close") != null && tickerPrices.get("Close") != null) {
						if (test.test(tickerValues.get("Close"), tickerPrices.get("Close"))) {
							eligibleByDate.add(d);

						}

					}
				}
				i++;
//				previousDate= d;
				
			}

			return eligibleByDate;

		} else {
			// 1) Parse the threshold once
			float threshold = rule.getValue();
			// 2) Prepare operator test function
			if (test == null) {
				throw new IllegalArgumentException("Unknown operator: " + rule.getOperator());
			}

			// 3) Iterate over each date and evaluate the rule

			Set<LocalDate> eligibleByDate = new HashSet<>();

			List<LocalDate> sortedDates = marketIndicator.getDates().stream().sorted().toList();
			int i =0;
//			LocalDate previousDate = null;
			for (LocalDate d : sortedDates) {

				if (d.equals(LocalDate.of(2000, 1, 3))) {
					System.err.println();
				}
				if(i > 0 ) {
					Map<String, Float> tickerValues = marketIndicator.getRow(d);

					if (tickerValues.get("Close") != null && threshold > 0) {
						if (test.test(tickerValues.get("Close"), threshold)) {
							eligibleByDate.add(d);
						}

					}
				}
				
				
				i++;
//				previousDate = d;
			}
			return eligibleByDate;

		}
	}
	
	
	private Set<LocalDate> evaluateRulePreviousDate(ArrowDataFrame marketIndicator, RuleDto rule, ArrowDataFrame marketTrendPrice) {

		BiPredicate<Float, Float> test = OPERATOR_MAP.get(rule.getOperator());

		if (rule.getValueType().equalsIgnoreCase("indicator_price")) {

			Set<LocalDate> eligibleByDate = new HashSet<>();
			List<LocalDate> sortedDates = marketIndicator.getDates().stream().sorted().toList();
			int i =0;
			LocalDate previousDate = null;
			for (LocalDate d : sortedDates) {
				if(i > 0) {
					Map<String, Float> tickerValues = marketIndicator.getRow(previousDate);
					Map<String, Float> tickerPrices = marketTrendPrice.getRow(previousDate);

					if (tickerValues.get("Close") != null && tickerPrices.get("Close") != null) {
						if (test.test(tickerValues.get("Close"), tickerPrices.get("Close"))) {
							eligibleByDate.add(d);

						}

					}
				}
				i++;
				previousDate= d;
				
			}

			return eligibleByDate;

		} else {
			// 1) Parse the threshold once
			float threshold = rule.getValue();
			// 2) Prepare operator test function
			if (test == null) {
				throw new IllegalArgumentException("Unknown operator: " + rule.getOperator());
			}

			// 3) Iterate over each date and evaluate the rule

			Set<LocalDate> eligibleByDate = new HashSet<>();

			List<LocalDate> sortedDates = marketIndicator.getDates().stream().sorted().toList();
			int i =0;
			LocalDate previousDate = null;
			for (LocalDate d : sortedDates) {

				if (d.equals(LocalDate.of(2000, 1, 3))) {
					System.err.println();
				}
				if(i > 0 ) {
					Map<String, Float> tickerValues = marketIndicator.getRow(previousDate);

					if (tickerValues.get("Close") != null && threshold > 0) {
						if (test.test(tickerValues.get("Close"), threshold)) {
							eligibleByDate.add(d);
						}

					}
				}
				
				
				i++;
				previousDate = d;
			}
			return eligibleByDate;

		}
	}

}