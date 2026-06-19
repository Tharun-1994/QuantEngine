package com.backtest.engine.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import org.apache.arrow.vector.Float4Vector;

import com.backtest.engine.config.StaticConfig;
import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.entity.StrategyDataV2;
import com.backtest.engine.ruleBuilder.LeafCacheResult;
import com.backtest.engine.ruleBuilder.RuleTreeCache;
import com.backtest.engine.ruleBuilder.RuleTreeEvaluator;
import com.backtest.engine.service.PortfolioServiceV2;
import com.backtest.engine.service.StrategyBuilderServiceV2;
import com.backtest.engine.util.ArrowDataFrame;

@Service
public class StrategyBuilderServiceImplV2 implements StrategyBuilderServiceV2 {

	@Override
	public BuySellDataV2 generateSignals(StrategyDataV2 strategyData, PriceDataV2 priceData) {

//		 This Will Create the buys and sells signal by filtering the entries and exits as a list of Strings.
//		Example : RSI(2) < 10 will filter each date with List of tickers which pass the condition as entries.
		Map<RuleDto, Map<LocalDate, List<String>>> buys = generateBuySignals(strategyData, priceData);
//		Example : RSI(2) > 70 will filter each date with List of tickers which pass the condition as exits.
		Map<RuleDto, Map<LocalDate, List<String>>> sells = generateSellSignals(strategyData, priceData);

		return BuySellDataV2.builder().sells(sells).buys(buys).strategyData(strategyData).build();
	}
	
	
	public static Map<RuleDto, Map<LocalDate, List<String>>> toOldRuleMap(
	        Map<String, RuleDto> rulesById,
	        Map<String, Map<LocalDate, Set<String>>> leafCache
	) {
	    Map<RuleDto, Map<LocalDate, List<String>>> out = new HashMap<>();

	    // Null-safe: when a regime has no entry/exit rules (e.g. bull leg of
	    // Ronnan's ROC_SP500 where bull-mode has no entry filter and exits
	    // only via max_time), the corresponding tree is null → leaf cache
	    // is never built → both params arrive as null. Return empty map
	    // rather than NPE on entrySet().
	    if (leafCache == null || rulesById == null) {
	        return out;
	    }

	    for (Map.Entry<String, Map<LocalDate, Set<String>>> e : leafCache.entrySet()) {
	        String leafId = e.getKey();
	        RuleDto rule = rulesById.get(leafId);
	        if (rule == null) continue;

	        Map<LocalDate, List<String>> byDate = new HashMap<>();
	        for (Map.Entry<LocalDate, Set<String>> row : e.getValue().entrySet()) {
	            byDate.put(row.getKey(), new ArrayList<>(row.getValue()));
	        }
	        out.put(rule, byDate);
	    }
	    return out;
	}

	
	@Override
	public BuySellDataV2 generateSignalsV1(StrategyDataV2 strategyData, PriceDataV2 priceData) {

	    if (strategyData.getEntryRulesTree() != null && strategyData.getEntryLeafCache() == null) {
	        LeafCacheResult entryRes = RuleTreeCache.buildLeafCache(
	                strategyData.getEntryRulesTree(),
	                strategyData.getEntryIndicators(),
	                priceData,
	                this
	        );
	        strategyData.setEntryLeafRulesById(entryRes.getRulesByLeafId());
	        strategyData.setEntryLeafCache(entryRes.getEligibleByLeafId());
	    }

	    if (strategyData.getExitRulesTree() != null && strategyData.getExitLeafCache() == null) {
	        LeafCacheResult exitRes = RuleTreeCache.buildLeafCache(
	                strategyData.getExitRulesTree(),
	                strategyData.getExitIndicators(),
	                priceData,
	                this
	        );
	        strategyData.setExitLeafRulesById(exitRes.getRulesByLeafId());
	        strategyData.setExitLeafCache(exitRes.getEligibleByLeafId());
	    }

	    // OPTIONAL: keep old buys/sells if you still need them for debugging
	    Map<RuleDto, Map<LocalDate, List<String>>> buys =null;
	    Map<RuleDto, Map<LocalDate, List<String>>> sells = null;
	    buys  = toOldRuleMap(strategyData.getEntryLeafRulesById(), strategyData.getEntryLeafCache());
	    sells = toOldRuleMap(strategyData.getExitLeafRulesById(),  strategyData.getExitLeafCache());

	    return BuySellDataV2.builder()
	            .sells(sells)
	            .buys(buys)
	            .strategyData(strategyData)
	            .build();
	}


	private Map<RuleDto, Map<LocalDate, List<String>>> generateSellSignals(StrategyDataV2 strategyData,
			PriceDataV2 priceData) {
		strategyData.getExitRuleList().sort(Comparator.comparingInt(rule -> {
			String conn = rule.getConnector();
			if ("&&".equals(conn))
				return 0; // highest priority
			if ("||".equals(conn))
				return 1; // lower priority
			return 2; // no connector or others
		}));
		Map<RuleDto, Map<LocalDate, List<String>>> exitRuleMap = new HashMap<>();
		for (RuleDto rule : strategyData.getExitRuleList()) {
			Map<LocalDate, List<String>> list = evaluateRule(strategyData.getExitIndicators().get(rule.getIndicator() + "_" + rule.getLookback()),
					rule, priceData,strategyData.getExitIndicators().containsKey(rule.getValueIndicator() + "_" + rule.getValueLookback()) ?
						strategyData.getExitIndicators().get(rule.getValueIndicator() + "_" + rule.getValueLookback()) : null);
			
//			System.err.println(list.get(LocalDate.of(2000, 1, 5)).size());
			exitRuleMap.put(rule,list);
		}

		return exitRuleMap;
	}

	// 1) Define a reusable operator → lambda map
	private static final Map<String, BiPredicate<Float, Float>> OPERATOR_MAP = Map.of("<", (v, t) -> v < t, "<=",
			(v, t) -> v <= t, ">", (v, t) -> v > t, ">=", (v, t) -> v >= t, "==", (v, t) -> Float.compare(v, t) == 0,
			"!=", (v, t) -> Float.compare(v, t) != 0);

	private Map<RuleDto, Map<LocalDate, List<String>>> generateBuySignals(StrategyDataV2 strategyData,
			PriceDataV2 priceData) {

		strategyData.getEntryRulesList().sort(Comparator.comparingInt(rule -> {
			String conn = rule.getConnector();
			if ("&&".equals(conn))
				return 0; // highest priority
			if ("||".equals(conn))
				return 1; // lower priority
			return 2; // no connector or others
		}));

		Map<RuleDto, Map<LocalDate, List<String>>> entryRuleMap = new HashMap<>();
		for (RuleDto rule : strategyData.getEntryRulesList()) {

			if (rule.getIndicator() != null && rule.getLookback() > -1) {
				
				
				entryRuleMap.put(rule,
						evaluateRule(
								strategyData.getEntryIndicators().get(rule.getIndicator() + "_" + rule.getLookback()),
								rule, priceData,strategyData.getEntryIndicators().containsKey(rule.getValueIndicator() + "_" + rule.getValueLookback()) ?
										strategyData.getEntryIndicators().get(rule.getValueIndicator() + "_" + rule.getValueLookback()) : null));

			}

		}

		return entryRuleMap;

	}

//	private Map<LocalDate, List<String>> evaluateRule(ArrowDataFrame arrowDataFrame, RuleDto rule,PriceDataV2 priceData) {
//
//		// 1) Parse the threshold once
//		float threshold = rule.getValue();
//
//		// 2) Prepare operator test function
//		BiPredicate<Float, Float> test = OPERATOR_MAP.get(rule.getOperator());
//		if (test == null) {
//			throw new IllegalArgumentException("Unknown operator: " + rule.getOperator());
//		}
//
//		// 3) Iterate over each date and evaluate the rule
//
//		Map<LocalDate, List<String>> eligibleByDate = new HashMap<>();
//
//		List<LocalDate> sortedDates = arrowDataFrame.getDates().stream().sorted().toList();
//
//		for (LocalDate d : sortedDates) {
//			
//			if (d.equals(LocalDate.of(2000, 1, 3))) {
//				System.err.println();
//			}
//
//			Map<String, Float> tickerValues = arrowDataFrame.getRow(d);
//			;
//
//			List<String> eligibleTickers = tickerValues.entrySet().stream().filter(e -> {
//				Float val = e.getValue();
//				return val != null && !val.isNaN() && !val.isInfinite() && test.test(val, threshold);
//			}).map(Map.Entry::getKey).collect(Collectors.toList());
//
//			eligibleByDate.put(d, eligibleTickers);
//		}
//
//		return eligibleByDate;
//	}

	public Map<LocalDate, List<String>> evaluateRule(ArrowDataFrame arrowDataFrame, RuleDto rule,
			PriceDataV2 priceData,ArrowDataFrame indicatorPriceDataFrame) {

		// ─────────────────────────────────────────────────────────────────────
		// PRIMITIVE FAST PATH
		// All branches below use ArrowDataFrame's parallel arrays (built once,
		// reused per call) and primitive Float4Vector.get(int) reads. This avoids:
		//   - ~6,500 HashMap allocations per date (one per getRow call)
		//   - ~19.5M Float object allocations per leaf (boxing of primitive floats)
		//   - BiPredicate<Float,Float> re-boxing inside the inner loop
		// Logic is identical to the original; only allocation patterns differ.
		// ─────────────────────────────────────────────────────────────────────
		final String[] tickers = arrowDataFrame.getTickerArray();
		final Float4Vector[] vectors = arrowDataFrame.getVectorArray();
		final int numTickers = tickers.length;

		final List<LocalDate> sortedDates = new ArrayList<>(arrowDataFrame.getDates());
		Collections.sort(sortedDates);

		// ── Top N filter: rank tickers by indicator value, keep top N ──
		if ("top_n".equalsIgnoreCase(rule.getValueType())) {
			return evaluateTopNPrimitive(rule, sortedDates, tickers, vectors, numTickers, arrowDataFrame);
		}

		// ── Top N (within active universe) filter ──
		// Identical to top_n but only ranks tickers that are in today's active
		// universe (daily_universes parquet). Matches Python's
		//     series[todays_universe.index].nsmallest(N)
		// semantics, where ranking happens AFTER the universe filter.
		if ("top_n_universe".equalsIgnoreCase(rule.getValueType())) {
			return evaluateTopNInUniversePrimitive(rule, sortedDates, tickers, vectors, numTickers, arrowDataFrame, priceData);
		}

		// ── Standard threshold / indicator_price comparison ──
		// Encode operator into a primitive int (0..5) so the inner loop uses a switch
		// rather than a BiPredicate<Float,Float> lambda (which re-boxes on every call).
		final int op = encodeOperator(rule);

		if (rule.getValueType().equalsIgnoreCase("indicator_price")) {
			return evaluateIndicatorPricePrimitive(rule, priceData, indicatorPriceDataFrame,
					sortedDates, tickers, vectors, numTickers, op, arrowDataFrame);
		}
		return evaluateThresholdPrimitive(rule, sortedDates, tickers, vectors, numTickers, op, arrowDataFrame);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Primitive helpers (boxing-free, allocation-light)
	// ─────────────────────────────────────────────────────────────────────────

	private static final int OP_LT  = 0;
	private static final int OP_LE  = 1;
	private static final int OP_GT  = 2;
	private static final int OP_GE  = 3;
	private static final int OP_EQ  = 4;
	private static final int OP_NEQ = 5;

	private static int encodeOperator(RuleDto rule) {
		String op;
		if (rule.getIndicator().equals(StaticConfig.N_WEEK_HIGH_RECENT)
				&& rule.getOperator().equalsIgnoreCase("IS_TRUE")) {
			op = "==";
		} else {
			op = rule.getOperator();
		}
		switch (op) {
			case "<":  return OP_LT;
			case "<=": return OP_LE;
			case ">":  return OP_GT;
			case ">=": return OP_GE;
			case "==": return OP_EQ;
			case "!=": return OP_NEQ;
			default: throw new IllegalArgumentException("Unknown operator: " + op);
		}
	}

	private static boolean compare(float v, float t, int op) {
		switch (op) {
			case OP_LT:  return v < t;
			case OP_LE:  return v <= t;
			case OP_GT:  return v > t;
			case OP_GE:  return v >= t;
			case OP_EQ:  return Float.compare(v, t) == 0;
			case OP_NEQ: return Float.compare(v, t) != 0;
			default: return false;
		}
	}

	/**
	 * Threshold branch: each ticker's indicator value is compared against a fixed scalar.
	 * Examples: hv_100 > 15, rsi_2 > 85, adx_10 >= 22.
	 */
	private Map<LocalDate, List<String>> evaluateThresholdPrimitive(RuleDto rule,
			List<LocalDate> sortedDates, String[] tickers, Float4Vector[] vectors,
			int numTickers, int op, ArrowDataFrame arrowDataFrame) {

		Map<LocalDate, List<String>> result = new HashMap<>(sortedDates.size() * 2);
		final float thresh = rule.getValue();

		for (LocalDate d : sortedDates) {
			Integer rowBox = arrowDataFrame.getDateIndex(d);
			if (rowBox == null) { result.put(d, Collections.emptyList()); continue; }
			int row = rowBox;

			// Capacity 64: rough average of passing tickers per date. Grows if exceeded.
			List<String> passing = new ArrayList<>(64);
			for (int col = 0; col < numTickers; col++) {
				Float4Vector vec = vectors[col];
				if (row >= vec.getValueCount() || vec.isNull(row)) continue;
				float v = vec.get(row);
				if (Float.isNaN(v) || Float.isInfinite(v)) continue;
				if (compare(v, thresh, op)) {
					passing.add(tickers[col]);
				}
			}
			result.put(d, passing);
		}
		return result;
	}

	/**
	 * Indicator_price branch: each ticker's LHS indicator is compared against another
	 * indicator's value for the same ticker (e.g. close_0 > sma_150).
	 *
	 * Preserves original fallback: when the RHS value is null/missing, synthesize
	 * MAX_VALUE for '>' or MIN_VALUE for '<' so the comparison resolves predictably.
	 * For RHS = "close", uses PriceDataV2.getValue (unchanged behavior).
	 */
	private Map<LocalDate, List<String>> evaluateIndicatorPricePrimitive(RuleDto rule,
			PriceDataV2 priceData, ArrowDataFrame rhsDf,
			List<LocalDate> sortedDates, String[] tickers, Float4Vector[] vectors,
			int numTickers, int op, ArrowDataFrame arrowDataFrame) {

		Map<LocalDate, List<String>> result = new HashMap<>(sortedDates.size() * 2);
		final boolean rhsIsClose = rule.getValueIndicator().equalsIgnoreCase("close");

		// Pre-build RHS ticker→column index ONCE (not per date) when RHS is an indicator frame
		String[] rhsTickers = null;
		Float4Vector[] rhsVectors = null;
		Map<String, Integer> rhsTickerIdx = null;
		if (!rhsIsClose && rhsDf != null) {
			rhsTickers = rhsDf.getTickerArray();
			rhsVectors = rhsDf.getVectorArray();
			rhsTickerIdx = new HashMap<>(rhsTickers.length * 2);
			for (int i = 0; i < rhsTickers.length; i++) {
				rhsTickerIdx.put(rhsTickers[i], i);
			}
		}

		for (LocalDate d : sortedDates) {
			Integer lhsRowBox = arrowDataFrame.getDateIndex(d);
			if (lhsRowBox == null) { result.put(d, Collections.emptyList()); continue; }
			int lhsRow = lhsRowBox;

			int rhsRow = -1;
			if (!rhsIsClose && rhsDf != null) {
				Integer rhsRowBox = rhsDf.getDateIndex(d);
				rhsRow = (rhsRowBox == null) ? -1 : rhsRowBox;
			}

			List<String> passing = new ArrayList<>(64);

			for (int col = 0; col < numTickers; col++) {
				Float4Vector lhsVec = vectors[col];
				if (lhsRow >= lhsVec.getValueCount() || lhsVec.isNull(lhsRow)) continue;
				float v = lhsVec.get(lhsRow);
				if (Float.isNaN(v)) continue;

				// Resolve RHS threshold
				float thresh;
				if (rhsIsClose) {
					// Original behavior: PriceData.getValue(ticker, d, "close") — may return Float box
					Float close = priceData.getValue(tickers[col], d, rule.getValueIndicator());
					if (close == null) continue;
					thresh = close;
				} else if (rhsDf == null || rhsRow < 0) {
					// RHS frame missing or RHS date missing → use original synthetic-extreme fallback
					if (op == OP_GT) { thresh = Integer.MAX_VALUE; }
					else if (op == OP_LT) { thresh = Integer.MIN_VALUE; }
					else { continue; }
				} else {
					Integer rhsColBox = rhsTickerIdx.get(tickers[col]);
					if (rhsColBox == null) {
						// Ticker not present in RHS frame → same fallback as original
						if (op == OP_GT) { thresh = Integer.MAX_VALUE; }
						else if (op == OP_LT) { thresh = Integer.MIN_VALUE; }
						else { continue; }
					} else {
						int rhsCol = rhsColBox;
						Float4Vector rhsVec = rhsVectors[rhsCol];
						if (rhsRow >= rhsVec.getValueCount() || rhsVec.isNull(rhsRow)) {
							if (op == OP_GT) { thresh = Integer.MAX_VALUE; }
							else if (op == OP_LT) { thresh = Integer.MIN_VALUE; }
							else { continue; }
						} else {
							thresh = rhsVec.get(rhsRow);
						}
					}
				}

				if (compare(v, thresh, op)) {
					passing.add(tickers[col]);
				}
			}
			result.put(d, passing);
		}
		return result;
	}

	/**
	 * Top-N branch: rank tickers by indicator value, keep the top N.
	 * Uses parallel primitive arrays (int[] indices + float[] values) and a custom
	 * sort that does not box. Preserves the original semantics (filter NaN/Inf,
	 * descending by default, ascending if rankingOrder = "Ascending").
	 */
	private Map<LocalDate, List<String>> evaluateTopNPrimitive(RuleDto rule,
			List<LocalDate> sortedDates, String[] tickers, Float4Vector[] vectors,
			int numTickers, ArrowDataFrame arrowDataFrame) {

		Map<LocalDate, List<String>> result = new HashMap<>(sortedDates.size() * 2);
		final int n = (int) rule.getValue();
		final boolean descending = !"Ascending".equalsIgnoreCase(rule.getRankingOrder());

		// Reusable scratch buffers — allocated once, reused for every date
		int[]   colIdx = new int[numTickers];
		float[] vals   = new float[numTickers];

		for (LocalDate d : sortedDates) {
			Integer rowBox = arrowDataFrame.getDateIndex(d);
			if (rowBox == null) { result.put(d, Collections.emptyList()); continue; }
			int row = rowBox;

			int count = 0;
			for (int col = 0; col < numTickers; col++) {
				Float4Vector vec = vectors[col];
				if (row >= vec.getValueCount() || vec.isNull(row)) continue;
				float v = vec.get(row);
				if (Float.isNaN(v) || Float.isInfinite(v)) continue;
				colIdx[count] = col;
				vals[count] = v;
				count++;
			}

			// Sort the (colIdx[0..count), vals[0..count)) prefix by vals.
			// Use boxed Integer[] of indices (small — only `count` elements, not numTickers)
			// with a primitive-comparison comparator. Avoids stream + Map.Entry pipeline.
			Integer[] orderIdx = new Integer[count];
			for (int i = 0; i < count; i++) orderIdx[i] = i;
			final float[] valsRef = vals;
			java.util.Arrays.sort(orderIdx, (a, b) ->
					descending ? Float.compare(valsRef[b], valsRef[a])
					           : Float.compare(valsRef[a], valsRef[b]));

			int take = Math.min(n, count);
			List<String> top = new ArrayList<>(take);
			for (int i = 0; i < take; i++) {
				top.add(tickers[colIdx[orderIdx[i]]]);
			}
			result.put(d, top);
		}
		return result;
	}

	/**
	 * Top-N within active universe.
	 *
	 * Mirrors Python's behavior:
	 *     todays_universe = data.daily_universes.loc[d].dropna()
	 *     todays_universe = todays_universe[todays_universe == 1]
	 *     series[todays_universe.index].nsmallest(N)
	 *
	 * Identical to {@link #evaluateTopNPrimitive} except for one extra check
	 * inside the ticker loop: tickers not in today's universe are skipped before
	 * being considered for ranking. The universe is fetched once per date from
	 * priceData.getDaily_universes().getRow(d) — a Set<String> lookup.
	 *
	 * Why this exists: the base top_n evaluator ranks across all ~2000 tickers
	 * ever in the dataset, including delisted/non-universe ones. For dynamic
	 * universes like Liquid_500 this picks the wrong worst-N because ~75% of
	 * candidates aren't tradable today. Python filters universe FIRST, then
	 * ranks. This method matches that.
	 */
	private Map<LocalDate, List<String>> evaluateTopNInUniversePrimitive(RuleDto rule,
			List<LocalDate> sortedDates, String[] tickers, Float4Vector[] vectors,
			int numTickers, ArrowDataFrame arrowDataFrame, PriceDataV2 priceData) {

		Map<LocalDate, List<String>> result = new HashMap<>(sortedDates.size() * 2);
		final int n = (int) rule.getValue();
		final boolean descending = !"Ascending".equalsIgnoreCase(rule.getRankingOrder());

		// Reusable scratch buffers — allocated once, reused for every date
		int[]   colIdx = new int[numTickers];
		float[] vals   = new float[numTickers];

		for (LocalDate d : sortedDates) {
			Integer rowBox = arrowDataFrame.getDateIndex(d);
			if (rowBox == null) { result.put(d, Collections.emptyList()); continue; }
			int row = rowBox;

			// Fetch today's active universe (Set<String>). One lookup per date.
			Set<String> todayUniverse = priceData.getDaily_universes().getRow(d);
			if (todayUniverse == null || todayUniverse.isEmpty()) {
				result.put(d, Collections.emptyList());
				continue;
			}

			int count = 0;
			for (int col = 0; col < numTickers; col++) {
				Float4Vector vec = vectors[col];
				if (row >= vec.getValueCount() || vec.isNull(row)) continue;
				// Universe membership check — the only added line vs evaluateTopNPrimitive.
				if (!todayUniverse.contains(tickers[col])) continue;
				float v = vec.get(row);
				if (Float.isNaN(v) || Float.isInfinite(v)) continue;
				colIdx[count] = col;
				vals[count] = v;
				count++;
			}

			// Sort the (colIdx[0..count), vals[0..count)) prefix by vals.
			Integer[] orderIdx = new Integer[count];
			for (int i = 0; i < count; i++) orderIdx[i] = i;
			final float[] valsRef = vals;
			java.util.Arrays.sort(orderIdx, (a, b) ->
					descending ? Float.compare(valsRef[b], valsRef[a])
					           : Float.compare(valsRef[a], valsRef[b]));

			int take = Math.min(n, count);
			List<String> top = new ArrayList<>(take);
			for (int i = 0; i < take; i++) {
				top.add(tickers[colIdx[orderIdx[i]]]);
			}
			result.put(d, top);
		}
		return result;
	}

//	private Map<LocalDate, List<String>> evaluateRule(
//	        ArrowDataFrame arrowDataFrame,
//	        RuleDto rule,
//	        PriceDataV2 priceData
//	) {
//	    // Get the operator function (>, <, >=, <=, ==, etc.)
//	    BiPredicate<Float, Float> test = OPERATOR_MAP.get(rule.getOperator());
//	    if (test == null) {
//	        throw new IllegalArgumentException("Unknown operator: " + rule.getOperator());
//	    }
//
//	    Map<LocalDate, List<String>> eligibleByDate = new HashMap<>();
//	    List<LocalDate> sortedDates = arrowDataFrame.getDates().stream().sorted().toList();
//
//	    boolean isIndicatorPrice = "indicator_price".equalsIgnoreCase(rule.getValueType());
//	    String valueIndicator = rule.getValueIndicator(); // e.g., "close", "open"
//
//	    for (LocalDate date : sortedDates) {
//	        Map<String, Float> tickerValues = arrowDataFrame.getRow(date);
//	        List<String> eligibleTickers = new ArrayList<>();
//
//	        for (Map.Entry<String, Float> entry : tickerValues.entrySet()) {
//	            String ticker = entry.getKey();
//	            Float indicatorValue = entry.getValue();
//
//	            if (indicatorValue == null || indicatorValue.isNaN() || indicatorValue.isInfinite()) {
//	                continue;
//	            }
//
//	            Float comparisonValue;
//
//	            if (isIndicatorPrice) {
//	                // Compare indicator value to price (e.g. close or open)
//	                comparisonValue = priceData.getValue(ticker, date, valueIndicator);
//	            } else {
//	                // Compare to numeric threshold
//	                comparisonValue = rule.getValue();
//	            }
//
//	            if (comparisonValue == null || comparisonValue.isNaN() || comparisonValue.isInfinite()) {
//	                continue;
//	            }
//
//	            // Apply operator test
//	            if (test.test(indicatorValue, comparisonValue)) {
//	                eligibleTickers.add(ticker);
//	            }
//	        }
//
//	        eligibleByDate.put(date, eligibleTickers);
//	    }
//
//	    return eligibleByDate;
//	}

//	public Map<LocalDate, List<String>> evaluateRule(Map<LocalDate, Map<String, Float>> map, RuleDto rule) {
//
//		// 1) Parse the threshold once
//		float threshold = rule.getValue();
//
//		// 2) Prepare operator test function
//		BiPredicate<Float, Float> test = OPERATOR_MAP.get(rule.getOperator());
//		if (test == null) {
//			throw new IllegalArgumentException("Unknown operator: " + rule.getOperator());
//		}
//
//		// 3) Iterate over each date and evaluate the rule
//		Map<LocalDate, List<String>> eligibleByDate = new HashMap<>();
//
//		for (Map.Entry<LocalDate, Map<String, Float>> entry : map.entrySet()) {
//			LocalDate date = entry.getKey();
//			Map<String, Float> tickerValues = entry.getValue();
//
//			List<String> eligibleTickers = tickerValues.entrySet().stream().filter(e -> {
//				Float val = e.getValue();
//				return val != null && !val.isNaN() && !val.isInfinite() && test.test(val, threshold);
//			}).map(Map.Entry::getKey).collect(Collectors.toList());
//
//			eligibleByDate.put(date, eligibleTickers);
//		}
//
//		return eligibleByDate;
//	}

	@Override
	public Map<String, List<String>> signalsForTheDay(LocalDate date, PriceDataV2 priceData, BuySellDataV2 buySellData,
			PortfolioServiceV2 portfolioService) {

		long startTotal = System.nanoTime(); // ⏱️ start timer for the whole function

		Map<String, List<String>> entryExitMap = new HashMap<>();
		entryExitMap.put("entry", Collections.EMPTY_LIST);
		entryExitMap.put("exit", Collections.EMPTY_LIST);

		List<RuleDto> entryrules = buySellData.getStrategyData().getEntryRulesList();
		Map<RuleDto, Map<LocalDate, List<String>>> buysRules = buySellData.getBuys();
		Map<RuleDto, Map<LocalDate, List<String>>> sellRules = buySellData.getSells();

		Set<String> todayUniverse = priceData.getDaily_universes().getRow(date);
		Set<String> entrySet = new HashSet<>();
		long startBuys = System.nanoTime();
		int i = 0;
		for (Map.Entry<RuleDto, Map<LocalDate, List<String>>> ruleEntry : buysRules.entrySet()) {
			List<String> todays = ruleEntry.getValue().get(date);
			if (i == 0) {
				
				entrySet.addAll(todays);
			} else {
				RuleDto prev = entryrules.get(i - 1);
				String conn = prev.getConnector(); // "&&" or "||"
				if ("&&".equals(conn)) {
					entrySet.retainAll(todays);
				} else { // "||" or fallback
					entrySet.addAll(todays);
				}
			}
			i++;
		}
		long endBuys = System.nanoTime();

		long startSells = System.nanoTime();
		Set<String> exitSet = new HashSet<>();
		List<RuleDto> exitrules = buySellData.getStrategyData().getExitRuleList();
		i = 0;
		for (Map.Entry<RuleDto, Map<LocalDate, List<String>>> ruleEntry : sellRules.entrySet()) {
			List<String> todays = ruleEntry.getValue().get(date);
			if (todays == null || todays.isEmpty()) {
				continue;
			}
			if (i == 0) {
				
				
				for(String live : portfolioService.getLiveHoldingsLogger()) {
					if(todays.contains(live)) {
						exitSet.add(live);
					}
				}
				
				
				
			} else {
				RuleDto prev = exitrules.get(i - 1);
				String conn = prev.getConnector();
				if ("&&".equals(conn)) {
					exitSet.retainAll(todays);
				} else {
					
					for(String live : portfolioService.getLiveHoldingsLogger()) {
						if(todays.contains(live)) {
							exitSet.add(live);
						}
					}

				}
			}
			i++;
		}
		long endSells = System.nanoTime();

		long startRank = System.nanoTime();

		entrySet.retainAll(todayUniverse);
		validEntriesTommorow(date, entrySet, priceData);

		// ── Duplicate-aware holding removal ──
		int maxDups = buySellData.getStrategyData().getMaxDuplicates();
		int maxDupSets = buySellData.getStrategyData().getMaxDuplicateSets();
		if (maxDups > 1) {
			Map<String, Long> holdingCounts = portfolioService.getLiveHoldingsTickerCounts();
			long currentDupSets = holdingCounts.values().stream().filter(c -> c >= 2).count();
			// Remove tickers already at max duplicates
			entrySet.removeIf(t -> holdingCounts.getOrDefault(t, 0L) >= maxDups);
			// If at max duplicate sets, remove all held tickers (no more duplicates allowed)
			if (maxDupSets > 0 && currentDupSets >= maxDupSets) {
				entrySet.removeIf(t -> holdingCounts.containsKey(t));
			}
		} else {
			entrySet.removeAll(portfolioService.getLiveHoldingsLogger());
		}

		List<String> entries_list = new ArrayList<>(entrySet);
		Map<String, Float> rank = buySellData.getStrategyData().getRanking().getRow(date);
		if (rank != null && !rank.isEmpty()) {
			if ("Ascending".equals(buySellData.getStrategyData().getRankingOrder())) {
				
				
				entries_list.sort(Comparator.comparingDouble(e -> {
					Float v = rank.get(e);
					return v != null ? v : Float.MAX_VALUE;
				}));
			} else if ("Descending".equals(buySellData.getStrategyData().getRankingOrder())) {
				entries_list.sort(Comparator.comparingDouble((String e) -> {
					Float v = rank.get(e);
//					Float v = rank.get("STI-201912");
					return v != null ? v : Float.MIN_VALUE;
				}).reversed());
			}
		}
		long endRank = System.nanoTime();

		// ── Sector filter: cap entries per sector (counting current holdings) ──
		StrategyDataV2 sdForSector = buySellData.getStrategyData();
		if (sdForSector.getSectorLimit() > 0 && sdForSector.getSectorMap() != null) {
			Map<String, Integer> sectorCount = new HashMap<>();
			for (String holding : portfolioService.getLiveHoldingsLogger()) {
				String sector = sdForSector.getSectorMap().getOrDefault(holding, "undefined");
				sectorCount.merge(sector, 1, Integer::sum);
			}
			List<String> sectorFiltered = new ArrayList<>();
			for (String ticker : entries_list) {
				String sector = sdForSector.getSectorMap().getOrDefault(ticker, "undefined");
				int count = sectorCount.getOrDefault(sector, 0);
				if (count < sdForSector.getSectorLimit()) {
					sectorFiltered.add(ticker);
					sectorCount.merge(sector, 1, Integer::sum);
				}
			}
			entries_list = sectorFiltered;
		}

		entryExitMap.put("entry", entries_list);
		entryExitMap.put("exit", new ArrayList<>(exitSet));

		long endTotal = System.nanoTime();

		// ⏱️ Timing report (you can later disable or log every 100 days)
		double totalSec = (endTotal - startTotal) / 1_000_000_000.0;
		double buysSec = (endBuys - startBuys) / 1_000_000_000.0;
		double sellsSec = (endSells - startSells) / 1_000_000_000.0;
		double rankSec = (endRank - startRank) / 1_000_000_000.0;

		System.err.printf("   ⚙️ [%s] signalsForTheDay → total: %.4fs (buys: %.4fs | sells: %.4fs | rank: %.4fs)%n",
				date, totalSec, buysSec, sellsSec, rankSec);

		return entryExitMap;
	}

//	@Override
//	public Map<String, List<String>> signalsForTheDay(LocalDate date, PriceDataV2 priceData, BuySellDataV2 buySellData,
//			PortfolioServiceV2 portfolioService) {
//
//		Map<String, List<String>> entryExitMap = new HashMap<>();
//		entryExitMap.put("entry", Collections.EMPTY_LIST);
//		entryExitMap.put("exit", Collections.EMPTY_LIST);
//
//		List<RuleDto> rules = buySellData.getStrategyData().getEntryRulesList();
//		Map<RuleDto, Map<LocalDate, List<String>>> buysRules = buySellData.getBuys();
//		Map<RuleDto, Map<LocalDate, List<String>>> sellRules = buySellData.getSells();
//
//		Set<String> entrySet = new LinkedHashSet<>();
//
//		Set<String> todayUniverse = priceData.getDaily_universes().getRow(date);
//		int i = 0;
//		for (Map.Entry<RuleDto, Map<LocalDate, List<String>>> ruleEntry : buysRules.entrySet()) {
//
////			List<String> todays = ruleEntry.getValue().getOrDefault(date, Collections.emptyList());
//			List<String> todays = ruleEntry.getValue().get(date);
//
//			if (i == 0) {
//				// seed with the first rule’s hits
//				entrySet.addAll(todays);
//			} else {
//				// look at the *previous* rule’s connector
//				RuleDto prev = rules.get(i - 1);
//				String conn = prev.getConnector(); // e.g. "&&" or "||"
//
//				if ("&&".equals(conn)) {
//					// AND = intersection
//					entrySet.retainAll(todays);
//				} else if ("||".equals(conn)) {
//					// OR = union
//					entrySet.addAll(todays);
//				} else {
//					// fallback: treat as OR
//					entrySet.addAll(todays);
//				}
//			}
//			i++;
//		}
//
//		Set<String> exitSet = new LinkedHashSet<>();
//
//		i = 0;
//		for (Map.Entry<RuleDto, Map<LocalDate, List<String>>> ruleEntry : sellRules.entrySet()) {
//
////			List<String> todays = ruleEntry.getValue().getOrDefault(date, Collections.emptyList());
//			List<String> todays = ruleEntry.getValue().get(date);
//
//			if (i == 0) {
//				// seed with the first rule’s hits
//				exitSet.addAll(todays);
//			} else {
//				// look at the *previous* rule’s connector
//				RuleDto prev = rules.get(i - 1);
//				String conn = prev.getConnector(); // e.g. "&&" or "||"
//
//				if ("&&".equals(conn)) {
//					// AND = intersection
//					exitSet.retainAll(todays);
//				} else if ("||".equals(conn)) {
//					// OR = union
//					exitSet.addAll(todays);
//				} else {
//					// fallback: treat as OR
//					exitSet.addAll(todays);
//				}
//			}
//			i++;
//		}
//		entrySet.retainAll(todayUniverse);
//
//		// Validation fro next Day
//		validEntriesTommorow(date, entrySet, priceData);
//
////		
////		portfolioService.getLiveHoldingsLogger().
//		entrySet.removeAll(portfolioService.getLiveHoldingsLogger());
//		// Ranking for ENtry Set
//		List<String> entries_list = new ArrayList<>(entrySet);
//
//		Map<String, Float> rank = buySellData.getStrategyData().getRanking().getRow(date);
//		if (rank != null && !rank.isEmpty()) {
//			if ("Ascending".equals(buySellData.getStrategyData().getRankingOrder())) {
//				entries_list.sort(Comparator.comparingDouble(e -> {
//					Float v = rank.get(e);
//					return v != null ? v : Float.MAX_VALUE;
//				}));
//			} else if ("Descending".equals(buySellData.getStrategyData().getRankingOrder())) {
//				entries_list.sort(Comparator.comparingDouble((String e) -> {
//					Float v = rank.get(e);
//					return v != null ? v : Double.MAX_VALUE;
//				}).reversed());
//			}
//		}
//
//		entryExitMap.put("entry", entries_list);
//		entryExitMap.put("exit", new ArrayList<>(exitSet));
//
//		return entryExitMap;
//	}
	
	
	
	
	
	@Override
	public Map<String, List<String>> signalsForTheDayV1(LocalDate date, PriceDataV2 priceData,
			BuySellDataV2 buySellData, PortfolioServiceV2 portfolioService) {
		long startTotal = System.nanoTime();
		
	    if (date.equals(LocalDate.of(2020, 3, 4))) {
	        System.err.println();  // ← put breakpoint here
	    }

		Map<String, List<String>> entryExitMap = new HashMap<>();
		entryExitMap.put("entry", Collections.emptyList());
		entryExitMap.put("exit", Collections.emptyList());

		StrategyDataV2 sd = buySellData.getStrategyData();

		// ---------- Tree evaluation timing ----------
		long startTree = System.nanoTime();

		RuleGroupNodeDto entryTree = sd.getEntryRulesTree();
		RuleGroupNodeDto exitTree = sd.getExitRulesTree();

		// LeafCacheResult contains BOTH:
		// 1) leafId -> RuleDto (rulesByLeafId)
		// 2) leafId -> date -> eligibleTickers (eligibleByLeafId)
		LeafCacheResult entryRes = sd.getEntryLeafCacheResult();
		LeafCacheResult exitRes = sd.getExitLeafCacheResult();

		// Fallback only if not prebuilt (avoid in hot-path, but safe)
		if (entryRes == null && entryTree != null) {
			entryRes = RuleTreeCache.buildLeafCache(entryTree, sd.getEntryIndicators(), priceData, this);
			sd.setEntryLeafCacheResult(entryRes);
		}

		if (exitRes == null && exitTree != null) {
			exitRes = RuleTreeCache.buildLeafCache(exitTree, sd.getExitIndicators(), priceData, this);
			sd.setExitLeafCacheResult(exitRes);
		}

		// Evaluate tree for the date using eligibleByLeafId.
		//
		// Semantic for NULL entry tree: treat as "no filter" → entire universe is
		// candidate (matches Python's bull-leg pattern in Ronnan's ROC_SP500 script,
		// where `if not bear: rank entire universe by ROC`). Without this, a null
		// entry tree would produce zero candidates and the bull leg would never
		// trade — opposite of Python.
		//
		// NULL exit tree stays as empty set: no RSI/IBS-driven exits. max_time
		// still fires from BacktestServiceImplV2.checkMaxTime, matching Python's
		// bull-leg behaviour (only max_time exits, no signal exits).
		Set<String> todayUniverseForEval = priceData.getDaily_universes().getRow(date);
		Set<String> entrySet = (entryTree == null || entryRes == null)
				? new HashSet<>(todayUniverseForEval)
				: new HashSet<>(RuleTreeEvaluator.evalForDate(entryTree, date, entryRes.getEligibleByLeafId()));

		Set<String> exitSet = (exitTree == null || exitRes == null) ? new HashSet<>()
				: new HashSet<>(RuleTreeEvaluator.evalForDate(exitTree, date, exitRes.getEligibleByLeafId()));

		long endTree = System.nanoTime();

		// ---------- Exit filtering timing ----------
		long startSells = System.nanoTime();

		Set<String> liveHoldings = new HashSet<>(portfolioService.getLiveHoldingsLogger());
		exitSet.retainAll(liveHoldings);

		long endSells = System.nanoTime();

		// ---------- Universe + next-day validity + ranking ----------
		long startRank = System.nanoTime();

		Set<String> todayUniverse = priceData.getDaily_universes().getRow(date);

		entrySet.retainAll(todayUniverse);
		validEntriesTommorow(date, entrySet, priceData);

		// ── Duplicate-aware holding removal ──
		int maxDups = sd.getMaxDuplicates();
		int maxDupSets = sd.getMaxDuplicateSets();
		if (maxDups > 1) {
			Map<String, Long> holdingCounts = portfolioService.getLiveHoldingsTickerCounts();
			long currentDupSets = holdingCounts.values().stream().filter(c -> c >= 2).count();
			// Remove tickers already at max duplicates
			entrySet.removeIf(t -> holdingCounts.getOrDefault(t, 0L) >= maxDups);
			// If at max duplicate sets, remove all held tickers (no more duplicates allowed)
			if (maxDupSets > 0 && currentDupSets >= maxDupSets) {
				entrySet.removeIf(t -> holdingCounts.containsKey(t));
			}
		} else {
			entrySet.removeAll(liveHoldings);
		}

		List<String> entries_list = new ArrayList<>(entrySet);

		// ── Vol/Turnover filter — applied BEFORE ranking ──────────────────────
		// Matches Python: todays_entries filtered by avg_volume/avg_turnover >= threshold
		// Threshold is set yearly by BacktestServiceImplV2.computeVolThresholds().
		// When filter is disabled or thresholds are 0, all entries pass.
		if (sd.getVolFilter() != null && sd.getVolFilter().isEnabled()
				&& sd.getAvgVolume() != null && sd.getAvgTurnover() != null) {
			// Use previousDate (yesterday) — matches Python iloc[index_today-1]
			LocalDate prevDate = priceData.getAll_dates().stream()
					.filter(d -> d.isBefore(date))
					.reduce((a, b) -> b).orElse(null);
			if (prevDate != null) {
				float volThresh = sd.getVolThreshold();
				float toThresh = sd.getTurnoverThreshold();
				if (volThresh > 0 || toThresh > 0) {
					entries_list.removeIf(ticker -> {
						if (volThresh > 0) {
							try {
								Float av = sd.getAvgVolume().hasValue(prevDate, ticker)
										? sd.getAvgVolume().getValue(prevDate, ticker) : null;
								if (av == null || av < volThresh) return true;
							} catch (Exception ignored) { return true; }
						}
						if (toThresh > 0) {
							try {
								Float at = sd.getAvgTurnover().hasValue(prevDate, ticker)
										? sd.getAvgTurnover().getValue(prevDate, ticker) : null;
								if (at == null || at < toThresh) return true;
							} catch (Exception ignored) { return true; }
						}
						return false;
					});
				}
			}
		}

		Map<String, Float> rank = sd.getRanking().getRow(date);
		if (rank != null && !rank.isEmpty()) {
			if ("Ascending".equals(sd.getRankingOrder())) {
				entries_list.sort(Comparator.comparingDouble(e -> {
					Float v = rank.get(e);
					return v != null ? v : Float.MAX_VALUE;
				}));
			} else if ("Descending".equals(sd.getRankingOrder())) {
				entries_list.sort(Comparator.comparingDouble((String e) -> {
					Float v = rank.get(e);
					return v != null ? v : Float.MIN_VALUE;
				}).reversed());
			}
		}

		long endRank = System.nanoTime();

		// ── Sector filter: cap entries per sector (counting current holdings) ──
		if (sd.getSectorLimit() > 0 && sd.getSectorMap() != null) {
			Map<String, Integer> sectorCount = new HashMap<>();
			for (String holding : liveHoldings) {
				String sector = sd.getSectorMap().getOrDefault(holding, "undefined");
				sectorCount.merge(sector, 1, Integer::sum);
			}
			List<String> sectorFiltered = new ArrayList<>();
			for (String ticker : entries_list) {
				String sector = sd.getSectorMap().getOrDefault(ticker, "undefined");
				int count = sectorCount.getOrDefault(sector, 0);
				if (count < sd.getSectorLimit()) {
					sectorFiltered.add(ticker);
					sectorCount.merge(sector, 1, Integer::sum);
				}
			}
			entries_list = sectorFiltered;
		}

		entryExitMap.put("entry", entries_list);
		entryExitMap.put("exit", new ArrayList<>(exitSet));

		long endTotal = System.nanoTime();

		// ---------- Timing report ----------
		double totalSec = (endTotal - startTotal) / 1_000_000_000.0;
		double treeSec = (endTree - startTree) / 1_000_000_000.0;
		double sellsSec = (endSells - startSells) / 1_000_000_000.0;
		double rankSec = (endRank - startRank) / 1_000_000_000.0;

//		System.err.printf(
//				"   ⚙️ [%s] signalsForTheDayV1(Tree) → total: %.4fs (tree: %.4fs | exits: %.4fs | rank: %.4fs)%n", date,
//				totalSec, treeSec, sellsSec, rankSec);

		return entryExitMap;
	}


	
	private void validEntriesTommorow(LocalDate date, Set<String> entrySet, PriceDataV2 priceData) {

		int idx = Collections.binarySearch(priceData.getTrading_dates(), date);
		if (idx >= 0 && idx + 1 < priceData.getTrading_dates().size()) {
			LocalDate nextDate = priceData.getTrading_dates().get(idx + 1);

			// Retain only those in the next day's universe
			Set<String> nextDayUniverse = priceData.getDaily_universes().getRow(nextDate);

			entrySet.retainAll(nextDayUniverse);

			// Get the row in daily_closes for nextDate
			ArrowDataFrame dailyCloses = priceData.getDaily_closes();

			Map<String, Float> nextDayCloses = dailyCloses.getRow(nextDate);
			entrySet.removeIf(ticker -> {
				return nextDayCloses.get(ticker) == null || nextDayCloses.get(ticker).isNaN()
						|| nextDayCloses.get(ticker).isInfinite();
			});

		}
	}

}