package com.backtest.engine.entity;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.backtest.engine.util.ParquetToMap;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.Data;
import tech.tablesaw.api.Table;

@Builder
@Data
public class StrategyData {

	private List<RuleCondition> entryRulesList;
	private List<RuleCondition> exitRuleList;
	private Map<String, Map<LocalDate, Map<String, Double>>> entryIndicators;
	private Map<String, Map<LocalDate, Map<String, Double>>> exitIndicators;
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

	private Map<LocalDate, Map<String,Double>> ranking;
	private String rankingOrder;

}
