package com.backtest.engine.service.impl;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.service.BacktestServiceV3;
@Service
public class BacktestServiceImplV3 implements BacktestServiceV3{



	@Override
	public BacktestReponseDto runBacktestV2(PriceDataV2 priceData, BuySellDataV2 buySellData) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, BuySellDataV2 buySellData) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, Map<LocalDate, String> marketTrends,
			Map<String, BuySellDataV2> rulesOfDayRegimes) {
		// TODO Auto-generated method stub
		return null;
	}

}
