package com.backtest.engine.ruleBuilder;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.config.StaticConfig;
import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.RuleLeafNodeDto;
import com.backtest.engine.dto.request.RuleNodeDto;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.service.impl.StrategyBuilderServiceImplV2;
import com.backtest.engine.util.ArrowDataFrame;

public final class RuleTreeCache {

	private RuleTreeCache() {
	}

	// leafId -> (date -> eligibleTickers)
	public static LeafCacheResult buildLeafCache(RuleNodeDto root, Map<String, ArrowDataFrame> indicatorFrames,
			PriceDataV2 priceData, StrategyBuilderServiceImplV2 evaluator) {
		Map<String, RuleDto> leaves = new HashMap<>();
		collectLeaves(root, leaves); // leafId -> RuleDto

		Map<String, Map<LocalDate, Set<String>>> cache = new HashMap<>();

		for (Map.Entry<String, RuleDto> e : leaves.entrySet()) {
			String leafId = e.getKey();
			RuleDto rule = e.getValue();

			ArrowDataFrame mainDf;
			if (rule.getIndicator().equalsIgnoreCase(StaticConfig.N_WEEK_HIGH_RECENT)) {
				mainDf = indicatorFrames.get(StaticConfig.getN_WEEK_HIGH_RECENT(rule));
			} else if (rule.getIndicator().equalsIgnoreCase(StaticConfig.SHARPE)) {
				mainDf = indicatorFrames.get(StaticConfig.getSharpeKey(rule));
			}

			else {
				mainDf = indicatorFrames.get(rule.getIndicator() + "_" + rule.getLookback());
			}

			ArrowDataFrame valueDf = null;
			if ("indicator_price".equalsIgnoreCase(rule.getValueType())) {
				valueDf = indicatorFrames.get(rule.getValueIndicator() + "_" + rule.getValueLookback());
			}

			Map<LocalDate, List<String>> eligible = evaluator.evaluateRule(mainDf, rule, priceData, valueDf);

			Map<LocalDate, Set<String>> asSet = new HashMap<>();
			for (var row : eligible.entrySet()) {
				asSet.put(row.getKey(), new HashSet<>(row.getValue()));
			}

			cache.put(leafId, asSet);
		}

		return new LeafCacheResult(leaves, cache);
	}

	private static void collectLeaves(RuleNodeDto node, Map<String, RuleDto> out) {
		if (node == null)
			return;

		if (node instanceof RuleLeafNodeDto leaf) {
			out.put(leaf.getId(), leaf.getRule());
			return;
		}

		if (node instanceof RuleGroupNodeDto group && group.getChildren() != null) {
			for (RuleNodeDto c : group.getChildren())
				collectLeaves(c, out);
		}
	}
}
