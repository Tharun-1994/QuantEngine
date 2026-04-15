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

		// ── Top N filter: rank tickers by indicator value, keep top N ──
		if ("top_n".equalsIgnoreCase(rule.getValueType())) {
			int n = (int) rule.getValue();
			boolean descending = !"Ascending".equalsIgnoreCase(rule.getRankingOrder());

			Map<LocalDate, List<String>> eligibleByDate = new HashMap<>();
			List<LocalDate> sortedDates = arrowDataFrame.getDates().stream().sorted().toList();

			for (LocalDate d : sortedDates) {
				Map<String, Float> tickerValues = arrowDataFrame.getRow(d);

				List<String> topTickers = tickerValues.entrySet().stream()
						.filter(e -> e.getValue() != null && !e.getValue().isNaN() && !e.getValue().isInfinite())
						.sorted(descending
								? Map.Entry.<String, Float>comparingByValue().reversed()
								: Map.Entry.comparingByValue())
						.limit(n)
						.map(Map.Entry::getKey)
						.collect(Collectors.toList());

				eligibleByDate.put(d, topTickers);
			}
			return eligibleByDate;
		}

		// ── Standard threshold / indicator_price comparison ──
		// 1) Parse the threshold once
		BiPredicate<Float, Float> test;
		// 2) Prepare operator test function
		if(rule.getIndicator().equals(StaticConfig.N_WEEK_HIGH_RECENT)) {
			
			test =rule.getOperator().equalsIgnoreCase("IS_TRUE") ? OPERATOR_MAP.get("==") : OPERATOR_MAP.get(rule.getOperator());
		}else {
			test = OPERATOR_MAP.get(rule.getOperator());
		}
		
		
		if (test == null) {
			throw new IllegalArgumentException("Unknown operator: " + rule.getOperator());
		}

		// 3) Iterate over each date and evaluate the rule

		Map<LocalDate, List<String>> eligibleByDate = new HashMap<>();

		List<LocalDate> sortedDates = arrowDataFrame.getDates().stream().sorted().toList();

		if (rule.getValueType().equalsIgnoreCase("indicator_price")) {
			
			for (LocalDate d : sortedDates) {
				
				List<String> eligibleTickers = new ArrayList<>();
				
				if (d.equals(LocalDate.of(2000, 1, 5))) {
					System.err.println("");
//					continue;
				}

				Map<String, Float> tickerValues = arrowDataFrame.getRow(d);

				for (Map.Entry<String, Float> entry : tickerValues.entrySet()) {

					String ticker = entry.getKey();
					if(ticker.equals("GE")) {
						System.err.println();
					}
					Float indicatorValue = entry.getValue();
					if (indicatorValue == null) {
						continue;
					}
					float threshold = 0;
					if (rule.getValueIndicator().equalsIgnoreCase("close")) {
						threshold = priceData.getValue(ticker, d, rule.getValueIndicator());
					}else {
						
						
						if (indicatorPriceDataFrame.getValue(d, ticker) == null) {
							
							
							if(rule.getOperator().equals(">")) {
								threshold = Integer.MAX_VALUE;
							}else if(rule.getOperator().equals("<")) {
								threshold = Integer.MIN_VALUE;
							}
							
						}else {
							threshold =  indicatorPriceDataFrame.getValue(d, ticker);
						}
						
//						
					}

					if (test.test(indicatorValue, threshold)) {
						eligibleTickers.add(ticker);
					}

				}
				



				eligibleByDate.put(d, eligibleTickers);
			}

		} else {
			for (LocalDate d : sortedDates) {
				if (d.equals(LocalDate.of(2000, 1, 5))) {
					System.err.println();
//					continue;
				}
				final float thresh = rule.getValue();

				Map<String, Float> tickerValues = arrowDataFrame.getRow(d);
//				tickerValues.get("ACV-201105")

				List<String> eligibleTickers = tickerValues.entrySet().stream().filter(e -> {
					Float val = e.getValue();
					return val != null && !val.isNaN() && !val.isInfinite() && test.test(val, thresh);
				}).map(Map.Entry::getKey).collect(Collectors.toList());
//				eligibleTickers.contains("ACV-201105");
				eligibleByDate.put(d, eligibleTickers);
			}
		}

		return eligibleByDate;
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

		// Evaluate tree for the date using eligibleByLeafId
		Set<String> entrySet = (entryTree == null || entryRes == null) ? new HashSet<>()
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

		System.err.printf(
				"   ⚙️ [%s] signalsForTheDayV1(Tree) → total: %.4fs (tree: %.4fs | exits: %.4fs | rank: %.4fs)%n", date,
				totalSec, treeSec, sellsSec, rankSec);

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