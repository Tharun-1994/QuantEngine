package com.backtest.engine.entity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;
import tech.tablesaw.api.Table;

@Data
@Builder
public class BuySellData {
	private Map<RuleCondition, Map<LocalDate, List<String>>> buys;
	private Map<RuleCondition, Map<LocalDate, List<String>>> sells;

	private StrategyData strategyData;
}
