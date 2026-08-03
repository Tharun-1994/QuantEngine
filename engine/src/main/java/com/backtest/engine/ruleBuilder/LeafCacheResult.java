package com.backtest.engine.ruleBuilder;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.dto.request.RuleDto;

public class LeafCacheResult {
	private final Map<String, RuleDto> rulesByLeafId;
	private final Map<String, Map<LocalDate, Set<String>>> eligibleByLeafId;

	public LeafCacheResult(Map<String, RuleDto> rulesByLeafId,
			Map<String, Map<LocalDate, Set<String>>> eligibleByLeafId) {
		this.rulesByLeafId = rulesByLeafId;
		this.eligibleByLeafId = eligibleByLeafId;
	}

	public Map<String, RuleDto> getRulesByLeafId() {
		return rulesByLeafId;
	}

	public Map<String, Map<LocalDate, Set<String>>> getEligibleByLeafId() {
		return eligibleByLeafId;
	}
}
