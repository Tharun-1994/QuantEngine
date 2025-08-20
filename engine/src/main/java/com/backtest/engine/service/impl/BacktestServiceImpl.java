package com.backtest.engine.service.impl;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.backtest.engine.dto.request.EntrySignalsRequestDto;
import com.backtest.engine.dto.request.ExitSignalsRequestDto;
import com.backtest.engine.entity.BuySellData;
import com.backtest.engine.entity.PriceData;
import com.backtest.engine.service.BacktestService;
import com.backtest.engine.service.PortfolioService;
import com.backtest.engine.service.StrategyBuilderService;

@Service
public class BacktestServiceImpl implements BacktestService {

	private final StrategyBuilderServiceImpl strategyBuilderServiceImpl;

	@Autowired
	PortfolioService portfolioService;

	@Autowired
	StrategyBuilderService strategyBuilderService;

	BacktestServiceImpl(StrategyBuilderServiceImpl strategyBuilderServiceImpl) {
		this.strategyBuilderServiceImpl = strategyBuilderServiceImpl;
	}

	@Override
	public void runBacktest(PriceData priceData, BuySellData buySellData) {

		this.portfolioService.setPriceDate(priceData, 100000, 10);

		Map<String, List<String>> entryExitMap = null;
		LocalDate previousDate = null;
		
		
		for (LocalDate date : priceData.getAll_dates()) {
			System.err.println("current_ date " + date.toString() + " previous date " + previousDate );
			
			if(date.equals(LocalDate.of(2000, 9, 12))) {
				System.err.println();
			}
			

			if ((date.isAfter(priceData.getTrading_dates().get(0)) && date.isBefore(buySellData.getStrategyData().getEndDate()) )
					
					|| date.isEqual(buySellData.getStrategyData().getEndDate())) {
				
				if (priceData.getTrading_dates().contains(date)) {
					
					if (buySellData.getStrategyData().getExitTiming().equals("open")) {

						if (entryExitMap != null && entryExitMap.get("exit") != null
								&& !entryExitMap.get("exit").isEmpty()) {

							ExitSignalsRequestDto exitSignals = ExitSignalsRequestDto.builder().tradeDate(date)
									.exits(entryExitMap.get("exit")).reasonForExit("Exits").exitTime("open").build();

							this.portfolioService.executeExitSignals(exitSignals);
						}
					}
					
					

					if (buySellData.getStrategyData().getEntryTiming().equals("open")) {
						if (entryExitMap != null && entryExitMap.get("entry") != null
								&& !entryExitMap.get("entry").isEmpty()) {
							
							EntrySignalsRequestDto entrySignals = EntrySignalsRequestDto.builder().tradeDate(date)
									.entries(entryExitMap.get("entry"))
									.maxSingleStock(buySellData.getStrategyData().getMaxSameTicker())
									.reasonForEntry("Entries").entryTime("open")
									.slotCapital(buySellData.getStrategyData().getStartingCapital()/buySellData.getStrategyData().getSlots())
									.maxSlots(buySellData.getStrategyData().getSlots())
//					                 Max Quantities set to 5 default.
									.maxQuantitites(5).direction("long").previousDate(previousDate).build();
							
							this.portfolioService.executeEntrySignals(entrySignals);
							
						}
					}
				}
				
				this.portfolioService.markToMarket(date);

				this.portfolioService.checkLivePositionsOnTommorow(date);
				
				entryExitMap = strategyBuilderService.signalsForTheDay(date, priceData, buySellData,this.portfolioService);
			}

			if (date.isEqual(buySellData.getStrategyData().getEndDate())) {
				this.portfolioService.endOfBacktest(date);
			}
			
			previousDate = date;

		}

	}

}
