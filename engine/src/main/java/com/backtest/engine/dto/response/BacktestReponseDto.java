package com.backtest.engine.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.backtest.engine.entity.EquityLog;
import com.backtest.engine.entity.LimitOrder;
import com.backtest.engine.entity.TradeLog;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class BacktestReponseDto {
	private Map<String, TradeLog> tradeLogger;
	private Map<LocalDate, EquityLog> equityLogger;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private List<LimitOrder> proposedOrders;

	// Patch 13: regime label active on the last bar of the day loop. The
	// label is the underscore-concatenation of the market-trend rule labels
	// (same algorithm as BacktestEngineController:864-872 and
	// MarketTrendServiceV2Impl.buildLabelFromTree). Position Manager
	// (Phase C/C2) matches this against per-regime labels to look up the
	// active regime's `substitute_pool_size`. Only populated in execution
	// mode (seedHoldings != null); null for backtest responses → omitted
	// from JSON output via @JsonInclude(NON_NULL).
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String activeRegimeOnLastBar;
}