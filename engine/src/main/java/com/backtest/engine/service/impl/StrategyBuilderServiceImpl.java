package com.backtest.engine.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import com.backtest.engine.entity.BuySellData;
import com.backtest.engine.entity.PriceData;
import com.backtest.engine.entity.RuleCondition;
import com.backtest.engine.entity.StrategyData;
import com.backtest.engine.service.PortfolioService;
import com.backtest.engine.service.StrategyBuilderService;

@Service
public class StrategyBuilderServiceImpl implements StrategyBuilderService {
	List<Integer> list = new LinkedList<>();

	@Override
	public BuySellData generateSignals(StrategyData strategyData) {

//		 This Will Create the buys and sells signal by filtering the entries and exits as a list of Strings.
//		Example : RSI(2) < 10 will filter each date with List of tickers which pass the condition as entries.
		Map<RuleCondition, Map<LocalDate, List<String>>> buys = generateBuySignals(strategyData);
//		Example : RSI(2) > 70 will filter each date with List of tickers which pass the condition as exits.
		Map<RuleCondition, Map<LocalDate, List<String>>> sells = generateSellSignals(strategyData);

		return BuySellData.builder().sells(sells).buys(buys).build();
	}

	private Map<RuleCondition, Map<LocalDate, List<String>>> generateSellSignals(StrategyData strategyData) {

		Map<RuleCondition, Map<LocalDate, List<String>>> exitRuleMap = new HashMap<>();
		for (RuleCondition rule : strategyData.getExitRuleList()) {

			if (rule.getIndicator() != null && rule.getIndicatorLookBack() > -1) {
				exitRuleMap.put(rule, evaluateRule(
						strategyData.getEntryIndicators().get(rule.getIndicator() + "_" + rule.getIndicatorLookBack()),
						rule));

			}

		}

		return exitRuleMap;
	}

	private Map<RuleCondition, Map<LocalDate, List<String>>> generateBuySignals(StrategyData strategyData) {

		Map<RuleCondition, Map<LocalDate, List<String>>> entryRuleMap = new HashMap<>();
		for (RuleCondition rule : strategyData.getEntryRulesList()) {

			if (rule.getIndicator() != null && rule.getIndicatorLookBack() > -1) {
				entryRuleMap.put(rule, evaluateRule(
						strategyData.getEntryIndicators().get(rule.getIndicator() + "_" + rule.getIndicatorLookBack()),
						rule));

			}

		}

		return entryRuleMap;
	}

	public Map<LocalDate, List<String>> evaluateRule(Map<LocalDate, Map<String, Double>> map, RuleCondition rule) {

		// 1) Parse the threshold once
		double threshold = rule.getValue();

		// 2) Prepare operator test function
		BiPredicate<Double, Double> test = OPERATOR_MAP.get(rule.getOperator());
		if (test == null) {
			throw new IllegalArgumentException("Unknown operator: " + rule.getOperator());
		}

		// 3) Iterate over each date and evaluate the rule
		Map<LocalDate, List<String>> eligibleByDate = new LinkedHashMap<>();

		for (Map.Entry<LocalDate, Map<String, Double>> entry : map.entrySet()) {
			LocalDate date = entry.getKey();
			Map<String, Double> tickerValues = entry.getValue();

			List<String> eligibleTickers = tickerValues.entrySet().stream().filter(e -> {
				Double val = e.getValue();
				return val != null && !val.isNaN() && !val.isInfinite() && test.test(val, threshold);
			}).map(Map.Entry::getKey).collect(Collectors.toList());

			eligibleByDate.put(date, eligibleTickers);
		}

		return eligibleByDate;
	}

	// 1) Define a reusable operator → lambda map
	private static final Map<String, BiPredicate<Double, Double>> OPERATOR_MAP = Map.of(
		    "<",  (v, t) -> v < t,
		    "<=", (v, t) -> v <= t,
		    ">",  (v, t) -> v > t,
		    ">=", (v, t) -> v >= t,
		    "==", (v, t) -> Double.compare(v, t) == 0,
		    "!=", (v, t) -> Double.compare(v, t) != 0
		);


	@Override
	public Map<String, List<String>> signalsForTheDay(LocalDate date, PriceData priceData, BuySellData buySellData, PortfolioService portfolioService) {

		Map<String, List<String>> entryExitMap = new HashMap<>();
		entryExitMap.put("entry", Collections.EMPTY_LIST);
		entryExitMap.put("exit", Collections.EMPTY_LIST);

		List<RuleCondition> rules = buySellData.getStrategyData().getEntryRulesList();
		Map<RuleCondition, Map<LocalDate, List<String>>> buysRules = buySellData.getBuys();
		Map<RuleCondition, Map<LocalDate, List<String>>> sellRules = buySellData.getSells();

		Set<String> entrySet = new LinkedHashSet<>();

		Set<String> todayUniverse = priceData.getDaily_universes().get(date);
		int i = 0;
		for (Map.Entry<RuleCondition, Map<LocalDate, List<String>>> ruleEntry : buysRules.entrySet()) {

			List<String> todays = ruleEntry.getValue().getOrDefault(date, Collections.emptyList());

			if (i == 0) {
				// seed with the first rule’s hits
				entrySet.addAll(todays);
			} else {
				// look at the *previous* rule’s connector
				RuleCondition prev = rules.get(i - 1);
				String conn = prev.getConnectOperator(); // e.g. "&&" or "||"

				if ("&&".equals(conn)) {
					// AND = intersection
					entrySet.retainAll(todays);
				} else if ("||".equals(conn)) {
					// OR = union
					entrySet.addAll(todays);
				} else {
					// fallback: treat as OR
					entrySet.addAll(todays);
				}
			}
			i++;
		}

		Set<String> exitSet = new LinkedHashSet<>();

		i = 0;
		for (Map.Entry<RuleCondition, Map<LocalDate, List<String>>> ruleEntry : sellRules.entrySet()) {

			List<String> todays = ruleEntry.getValue().getOrDefault(date, Collections.emptyList());

			if (i == 0) {
				// seed with the first rule’s hits
				exitSet.addAll(todays);
			} else {
				// look at the *previous* rule’s connector
				RuleCondition prev = rules.get(i - 1);
				String conn = prev.getConnectOperator(); // e.g. "&&" or "||"

				if ("&&".equals(conn)) {
					// AND = intersection
					exitSet.retainAll(todays);
				} else if ("||".equals(conn)) {
					// OR = union
					exitSet.addAll(todays);
				} else {
					// fallback: treat as OR
					exitSet.addAll(todays);
				}
			}
			i++;
		}
		entrySet.retainAll(todayUniverse);

			//		Validation fro next Day
		validEntriesTommorow(date, entrySet, priceData);
		
		
//		
//		portfolioService.getLiveHoldingsLogger().
		entrySet.removeAll(portfolioService.getLiveHoldingsLogger());
		// Ranking for ENtry Set
		List<String> entries_list = new ArrayList<>(entrySet);
		Map<String, Double> rank = buySellData.getStrategyData().getRanking().get(date);
		if (rank != null && !rank.isEmpty()) {
		    if ("asc".equals(buySellData.getStrategyData().getRankingOrder())) {
		        entries_list.sort(Comparator.comparingDouble(e -> {
		            Double v = rank.get(e);
		            return v != null ? v : Double.MAX_VALUE;
		        }));
		    } else if ("desc".equals(buySellData.getStrategyData().getRankingOrder())) {
		        entries_list.sort(Comparator.comparingDouble((String e) -> {
		            Double v = rank.get(e);
		            return v != null ? v : Double.MAX_VALUE;
		        }).reversed());
		    }
		}


		entryExitMap.put("entry", entries_list);
		entryExitMap.put("exit", new ArrayList<>(exitSet));

		return entryExitMap;
	}

	private void validEntriesTommorow(LocalDate date, Set<String> entrySet, PriceData priceData) {

		int idx = Collections.binarySearch(priceData.getTrading_dates(), date);
		if (idx >= 0 && idx + 1 < priceData.getTrading_dates().size()) {
			LocalDate nextDate = priceData.getTrading_dates().get(idx + 1);

			// Retain only those in the next day's universe
			Set<String> nextDayUniverse = priceData.getDaily_universes().get(nextDate);
			System.err.println(date);
			entrySet.retainAll(nextDayUniverse);

			// Get the row in daily_closes for nextDate
			Map<LocalDate, Map<String, Double>> dailyCloses = priceData.getDaily_closes();

			Map<String, Double> nextDayCloses = dailyCloses.get(nextDate);
			entrySet.removeIf(ticker -> {
				return nextDayCloses.get(ticker).isNaN() || nextDayCloses.get(ticker).isInfinite()
						|| nextDayCloses.get(ticker) == null;
			});

		}
	}

//	private Set<String> getTodayUniverse(LocalDate date, PriceData priceData) {
//		Table withoutDate = priceData.getDaily_universes()
//				.where(priceData.getDaily_universes().dateTimeColumn("Date").date().isEqualTo(date))
//				.removeColumns("Date");
//
//		Set<String> todayUniverse = withoutDate.columnsOfType(IntColumnType.instance()).stream().map(c -> (IntColumn) c)
//				.filter(ic -> ic.get(0) == 1).map(IntColumn::name).collect(Collectors.toSet());
//		return todayUniverse;
//	}

//	public Map<LocalDate,List<String>> evaulateRules(Table t, RuleCondition rule){
//		
//        List<String> tickers = t.columnNames().stream()
//                .filter(col -> !col.equals("Date"))
//                .collect(Collectors.toList());
//		
//		Map<LocalDate, List<String>> eligibleByDate = new LinkedHashMap<>();
//		for (int i = 0; i < t.rowCount(); i++) {
//        	LocalDate d = t.dateColumn("Date").get(i);
//        	
//        	List<String> passedTickers = new ArrayList<>();
//        	for(String tick : tickers) {
//        		if(t.doubleColumn(tick).get(i) > rule.getValue()) {
//        			passedTickers.add(tick);
//        		}
//        	}
//        	
//        	eligibleByDate.put(d, passedTickers);
//        	
//        }
//		
//		return eligibleByDate;
//		
//	}

}
