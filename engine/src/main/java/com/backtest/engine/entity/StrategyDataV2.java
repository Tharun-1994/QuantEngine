package com.backtest.engine.entity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.util.ArrowDataFrame;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class StrategyDataV2 {

	private List<RuleDto> entryRulesList;
	private List<RuleDto> exitRuleList;
	private Map<String, ArrowDataFrame> entryIndicators;
	private Map<String, ArrowDataFrame> exitIndicators;
	private float stopLossPct;
	private float takeProfitPct;
	private int maxSameTicker;
	private float startingCapital;
	private int slots;
	private LocalDate startDate;
	private LocalDate endDate;
	private String stoplossTiming;

	private String takeprofitTiming;

	private String entryTiming;

	private String exitTiming;

	private ArrowDataFrame ranking;
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
