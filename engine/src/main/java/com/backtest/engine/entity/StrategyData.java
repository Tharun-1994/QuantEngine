package com.backtest.engine.entity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class StrategyData {

	private List<RuleCondition> entryRulesList;
	private List<RuleCondition> exitRuleList;
	private Map<String, Map<LocalDate, Map<String, Float>>> entryIndicators;
	private Map<String, Map<LocalDate, Map<String, Float>>> exitIndicators;
	private int stopLossPct;
	private int takeProfitPct;
	private int maxSameTicker;
	private int startingCapital;
	private int slots;
	private LocalDate startDate;
	private LocalDate endDate;
	private String stoplossTiming;

	private String takeprofitTiming;

	private String entryTiming;

	private String exitTiming;

	private Map<LocalDate, Map<String, Float>> ranking;
	private String rankingOrder;
	private float minQuantity;
	private float minPrice;
	private String stoplossType;
	private String takeprofitType;
	private String systemType;

	private String orderType;
	private float limitPct;
	private int atrLimitLookback;
	
	private Map<String,String> paths;
	
	

}
