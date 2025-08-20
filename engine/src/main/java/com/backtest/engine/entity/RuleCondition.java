package com.backtest.engine.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RuleCondition {
	private final String indicator;
	private final int indicatorLookBack;
	private final String operator;
	private final double value;
	private final String connectOperator;
	
	
}
