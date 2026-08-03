package com.backtest.engine.entity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.util.DateListArrowMap;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BuySellDataV2 {

	private Map<RuleDto, Map<LocalDate, List<String>>> buys;
	private Map<RuleDto,  Map<LocalDate, List<String>>> sells;
	private StrategyDataV2 strategyData;

}
