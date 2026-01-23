package com.backtest.engine.context;

import org.springframework.stereotype.Component;

import com.backtest.engine.service.PriceDataLoaderService;
import com.backtest.engine.service.StrategyBuilderServiceV2;

@Component
public class BacktestContextFactory {
	public BacktestContext create(PriceDataLoaderService priceDataService, StrategyBuilderServiceV2 strategyBuilderServiceV2) {
		
		
        return new BacktestContext(priceDataService,strategyBuilderServiceV2);
    }
}
