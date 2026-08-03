package com.backtest.engine.context;

import org.springframework.stereotype.Component;

import com.backtest.engine.config.ArrowDataFrameCache;
import com.backtest.engine.config.ArrowStringDataFrameCache;
import com.backtest.engine.service.MarketTrendServiceV2;
import com.backtest.engine.service.PriceDataLoaderService;
import com.backtest.engine.service.StrategyBuilderServiceV2;

@Component
public class BacktestContextFactory {

	private final ArrowDataFrameCache cache;
	private final ArrowStringDataFrameCache stringCache;

	public BacktestContextFactory(ArrowDataFrameCache cache, ArrowStringDataFrameCache stringCache) {
		this.cache = cache;
		this.stringCache = stringCache;
	}

	public BacktestContext create(
			PriceDataLoaderService priceDataService,
			StrategyBuilderServiceV2 strategyBuilderServiceV2,
			MarketTrendServiceV2 marketTrendServiceV2) {
        return new BacktestContext(priceDataService, strategyBuilderServiceV2,
                                   marketTrendServiceV2, cache, stringCache);
    }
}