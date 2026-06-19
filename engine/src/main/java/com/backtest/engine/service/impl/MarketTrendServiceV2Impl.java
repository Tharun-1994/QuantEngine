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
import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.RuleLeafNodeDto;
import com.backtest.engine.dto.request.RuleNodeDto;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.ruleBuilder.MarketTrendTreeEvaluator;
import com.backtest.engine.service.MarketTrendServiceV2;
import com.backtest.engine.util.ArrowDataFrame;
import com.fasterxml.jackson.databind.ObjectMapper;


@Service("marketTrendService")
public class MarketTrendServiceV2Impl implements MarketTrendServiceV2 {

	@Override
	public Map<LocalDate, String> generateMarketTrend(List<MarketRegimeDto> marketRegimes,
			Map<String, ArrowDataFrame> marketTrendMap, PriceDataV2 priceData) {
		Map<LocalDate, String> entryRuleMap = new HashMap<>();
		for (MarketRegimeDto regime : marketRegimes) {
			// Prefer tree-based evaluation. Mirrors how signalsForTheDayV1 evaluates
			// entry/exit trees: leaves are pre-computed into a per-leaf cache, then a
			// shared combinator walks the tree (AND = intersect, OR = union).
			// Falls back to the legacy flat-list path for strategies saved before the
			// tree migration (kept for backward compatibility — can be removed once
			// all stored strategies are tree-only).
			Map<String, Object> treeMap = regime.getMarketTrendRulesTree();
			if (treeMap != null && !treeMap.isEmpty()) {
				Map<LocalDate, String> passedMap = generateMarketSignalsFromTree(
						treeMap, marketTrendMap, priceData);
				entryRuleMap.putAll(passedMap);
			} else {
				Map<LocalDate, String> passedMap = generateMarketSignals(
						regime.getMarketTrendRules(), marketTrendMap, priceData);
				entryRuleMap.putAll(passedMap);
			}
		}

		return entryRuleMap;
	}

	// ── Tree-based market trend evaluation ──────────────────────────────────
	//
	// generateMarketSignalsFromTree   → top-level entry point: tree (Map) →
	//                                   {date: label} for passing dates
	// collectLeaves                   → walk tree, build leafId -> RuleDto map
	// evaluateLeafForMarketTrend      → run a single leaf rule against its
	//                                   target market ticker, return Set<dates>
	// buildLabelFromTree              → concatenate leaf labels with "_"
	//                                   (mirrors the flat-list label scheme)

	private Map<LocalDate, String> generateMarketSignalsFromTree(
			Map<String, Object> treeMap,
			Map<String, ArrowDataFrame> marketTrendMap,
			PriceDataV2 priceData) {

		// Convert the loosely-typed Map<String,Object> (Jackson's default
		// deserialisation of MarketRegimeDto.marketTrendRulesTree) into a
		// typed RuleGroupNodeDto. Same conversion pattern used in
		// BacktestContext for entry/exit/freeze/resume trees.
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		RuleGroupNodeDto tree = mapper.convertValue(treeMap, RuleGroupNodeDto.class);

		// Collect every leaf so each rule is evaluated once.
		Map<String, RuleDto> leaves = new HashMap<>();
		collectLeaves(tree, leaves);

		// Per-leaf pre-computation: leafId → dates when the leaf rule passes
		// on its target market ticker (e.g. SPY < SMA(200) → set of dates).
		Map<String, Set<LocalDate>> leafPassingDates = new HashMap<>();
		for (Map.Entry<String, RuleDto> e : leaves.entrySet()) {
			Set<LocalDate> dates = evaluateLeafForMarketTrend(e.getValue(), marketTrendMap, priceData);
			leafPassingDates.put(e.getKey(), dates);
		}

		// Walk the tree with AND/OR combinators on the date sets.
		Set<LocalDate> passedDates = MarketTrendTreeEvaluator.evalTree(tree, leafPassingDates);

		// Build the regime label (e.g. "bear" or "bear_high_vix") from leaf labels.
		String label = buildLabelFromTree(tree);

		Map<LocalDate, String> out = new HashMap<>();
		for (LocalDate d : passedDates) {
			out.put(d, label);
		}
		return out;
	}

	private void collectLeaves(RuleNodeDto node, Map<String, RuleDto> out) {
		if (node == null) return;
		if (node instanceof RuleLeafNodeDto leaf) {
			out.put(leaf.getId(), leaf.getRule());
			return;
		}
		if (node instanceof RuleGroupNodeDto group && group.getChildren() != null) {
			for (RuleNodeDto c : group.getChildren()) collectLeaves(c, out);
		}
	}

	private String buildLabelFromTree(RuleNodeDto node) {
		// In-order traversal joining non-blank leaf labels with "_".
		// Matches the flat-list label scheme: each rule contributes its
		// label, separated by underscores.
		if (node == null) return "";
		if (node instanceof RuleLeafNodeDto leaf) {
			String lbl = (leaf.getRule() == null) ? "" : leaf.getRule().getLabel();
			return (lbl == null) ? "" : lbl;
		}
		if (node instanceof RuleGroupNodeDto group && group.getChildren() != null) {
			StringBuilder sb = new StringBuilder();
			for (RuleNodeDto c : group.getChildren()) {
				String s = buildLabelFromTree(c);
				if (s != null && !s.isBlank()) {
					if (sb.length() > 0) sb.append("_");
					sb.append(s);
				}
			}
			return sb.toString();
		}
		return "";
	}

	private Set<LocalDate> evaluateLeafForMarketTrend(
			RuleDto rule,
			Map<String, ArrowDataFrame> marketTrendMap,
			PriceDataV2 priceData) {

		// Same ticker/indicator/value resolution logic as the flat-list path
		// (see generateMarketSignals). Each leaf carries its own regime_ticker.
		String ruleTicker = (rule.getRegimeTicker() != null ? rule.getRegimeTicker() : "").toLowerCase();
		String indicator = rule.getIndicator().toLowerCase();

		ArrowDataFrame indicatorDf;
		if ("close".equals(indicator) || "open".equals(indicator)
				|| "high".equals(indicator) || "low".equals(indicator)) {
			indicatorDf = priceData.getMarketTickerPrices().get("closes_" + ruleTicker);
		} else {
			indicatorDf = marketTrendMap.get(ruleTicker + "_" + indicator + "_" + rule.getLookback());
		}

		ArrowDataFrame rhsDf = null;
		boolean isIndicatorPriceCompare = "indicator_price".equalsIgnoreCase(rule.getValueType())
				&& rule.getValueIndicator() != null && !rule.getValueIndicator().isBlank();
		if (isIndicatorPriceCompare) {
			String valIndicator = rule.getValueIndicator().toLowerCase();
			if ("close".equals(valIndicator) || "open".equals(valIndicator)
					|| "high".equals(valIndicator) || "low".equals(valIndicator)) {
				rhsDf = priceData.getMarketTickerPrices().get("closes_" + ruleTicker);
			} else {
				rhsDf = marketTrendMap.get(ruleTicker + "_" + rule.getValueIndicator() + "_" + rule.getValueLookback());
			}
		}

		// Bail safely if a required frame is missing.
		if (indicatorDf == null) {
			System.err.println("[MarketTrend] indicatorDf MISSING for ticker=" + ruleTicker
					+ " indicator=" + indicator + " lookback=" + rule.getLookback());
			return Set.of();
		}
		if (isIndicatorPriceCompare && rhsDf == null) {
			System.err.println("[MarketTrend] rhsDf MISSING for ticker=" + ruleTicker
					+ " valueIndicator=" + rule.getValueIndicator() + " valueLookback=" + rule.getValueLookback());
			return Set.of();
		}

		// Each of these parquets is single-ticker (one float column). Look up the
		// actual column name from the frame's schema instead of hardcoding "Close" —
		// the loader (ArrowDataFrame.load line 117) uses the parquet's own column
		// names, which the Python middleware writes as the ticker symbol ("spy"),
		// not the literal string "Close".
		String[] lhsCols = indicatorDf.getTickerArray();
		if (lhsCols.length == 0) {
			System.err.println("[MarketTrend] indicatorDf has no columns for ticker=" + ruleTicker);
			return Set.of();
		}
		String lhsCol = lhsCols[0];

		String rhsCol = null;
		if (isIndicatorPriceCompare) {
			String[] rhsCols = rhsDf.getTickerArray();
			if (rhsCols.length == 0) {
				System.err.println("[MarketTrend] rhsDf has no columns for ticker=" + ruleTicker);
				return Set.of();
			}
			rhsCol = rhsCols[0];
		}

		// One-time diagnostic per leaf — prints what column names were resolved
		// from each parquet and how many candidate dates exist. Remove once
		// market trend evaluation is confirmed working in production.
		System.err.println("[MarketTrend] Evaluating leaf: ticker=" + ruleTicker
				+ " lhs=" + lhsCol + " (rows=" + indicatorDf.getDates().size() + ")"
				+ " op=" + rule.getOperator()
				+ " rhs=" + (isIndicatorPriceCompare ? (rhsCol + "@" + rule.getValueIndicator() + "_" + rule.getValueLookback()) : ("threshold=" + rule.getValue())));

		BiPredicate<Float, Float> test = OPERATOR_MAP.get(rule.getOperator());
		if (test == null) {
			throw new IllegalArgumentException("Unknown operator: " + rule.getOperator());
		}

		Set<LocalDate> passing = new HashSet<>();
		List<LocalDate> sortedDates = indicatorDf.getDates().stream().sorted().toList();
		int i = 0;
		for (LocalDate d : sortedDates) {
			if (i++ == 0) continue; // skip first bar (warmup — matches legacy behaviour)

			Float lhsVal = indicatorDf.getValue(d, lhsCol);
			if (lhsVal == null || lhsVal.isNaN() || lhsVal.isInfinite()) continue;

			Float rhsVal;
			if (isIndicatorPriceCompare) {
				// Date may not exist in rhs frame (e.g. SMA still warming up) — skip.
				if (rhsDf.getDateIndex(d) == null) continue;
				rhsVal = rhsDf.getValue(d, rhsCol);
				if (rhsVal == null || rhsVal.isNaN() || rhsVal.isInfinite()) continue;
			} else {
				// Threshold comparison (e.g. VIX > 30)
				rhsVal = rule.getValue();
			}

			if (test.test(lhsVal, rhsVal)) {
				passing.add(d);
			}
		}

		System.err.println("[MarketTrend] Leaf passed on " + passing.size() + " of " + sortedDates.size() + " dates");
		return passing;
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