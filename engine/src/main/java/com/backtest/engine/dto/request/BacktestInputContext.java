package com.backtest.engine.dto.request;

import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.entity.StrategyDataV2;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class BacktestInputContext {
	private PriceDataV2 priceData;
	private StrategyDataV2 strategyData;
	private BuySellDataV2 buySellData;
}
