package com.backtest.engine.service.impl;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.backtest.engine.config.StaticConfig;
import com.backtest.engine.dto.request.EntrySignalsRequestDto;
import com.backtest.engine.dto.request.ExitSignalsRequestDto;
import com.backtest.engine.dto.request.LimitEntrySignalDto;
import com.backtest.engine.entity.BuySellData;
import com.backtest.engine.entity.LimitOrder;
import com.backtest.engine.entity.PriceData;
import com.backtest.engine.service.BacktestService;
import com.backtest.engine.service.PortfolioService;
import com.backtest.engine.service.StrategyBuilderService;

@Service
public class BacktestServiceImpl implements BacktestService {



	@Autowired
	PortfolioService portfolioService;


	StrategyBuilderService strategyBuilderService;

	BacktestServiceImpl(StrategyBuilderServiceImpl strategyBuilderServiceImpl) {
		this.strategyBuilderService = strategyBuilderServiceImpl;
	}

	@Override
	public void runBacktest(PriceData priceData, BuySellData buySellData) {

		this.portfolioService.setPriceDate(priceData, buySellData.getStrategyData().getStartingCapital(),
				buySellData.getStrategyData().getSlots(), buySellData.getStrategyData().getStopLossPct(),
				buySellData.getStrategyData().getTakeProfitPct());

		Map<String, List<String>> entryExitMap = null;
		
		Map<String,List<LimitOrder>> limitOrderMap = null;
		
		LocalDate previousDate = null;
		
		
		for (LocalDate date : priceData.getAll_dates()) {
			System.err.println("current_ date " + date.toString() + " previous date " + previousDate );
			
			if(date.equals(LocalDate.of(2000, 1, 19))) {
				System.err.println();
			}
			

			if (((date.isEqual(priceData.getTrading_dates().get(0))||date.isAfter(priceData.getTrading_dates().get(0)))  && date.isBefore(buySellData.getStrategyData().getEndDate()) )
					
					|| date.isEqual(buySellData.getStrategyData().getEndDate())) {
				
				if (priceData.getTrading_dates().contains(date)) {
					

					// Exit Orders 					
					if (buySellData.getStrategyData().getExitTiming().equals("open")) {

						if (entryExitMap != null && entryExitMap.get("exit") != null
								&& !entryExitMap.get("exit").isEmpty()) {

							ExitSignalsRequestDto exitSignals = ExitSignalsRequestDto.builder().tradeDate(date)
									.exits(entryExitMap.get("exit")).reasonForExit("Exits").exitTime("open").build();

							this.portfolioService.executeExitSignals(exitSignals);
						}
					}
					
					
					// Entry Orders 					

					if (buySellData.getStrategyData().getEntryTiming().equals("open")) {
						if(buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("normal"))) {
							if(buySellData.getStrategyData().getSystemType().equals(StaticConfig.systemType.get("long"))) { 
								processNormalOrders(date,previousDate,entryExitMap,buySellData);
							}
							
						}else if(buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit_atr")) ||
								buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit"))) {
							
							if(buySellData.getStrategyData().getSystemType().equals(StaticConfig.systemType.get("long"))) {
								processLimitOrdersLong(date,previousDate,limitOrderMap,buySellData);
							}
							
						}
					}
				}
				
				this.portfolioService.markToMarket(date);
				
				//StopLoss
				if(StaticConfig.stoplossType.get("nrml").equals(buySellData.getStrategyData().getStoplossType()) && buySellData.getStrategyData().getStopLossPct() > 0) {
					this.portfolioService.checkStoplossHit(date, buySellData.getStrategyData().getSystemType(), buySellData.getStrategyData().getStoplossTiming());
				}
				
				//Takeprofit
				if(StaticConfig.takeProfitType.get("nrml").equals(buySellData.getStrategyData().getTakeprofitType()) && buySellData.getStrategyData().getTakeProfitPct() > 0) {
					this.portfolioService.checkTakeProfit(date, buySellData.getStrategyData().getSystemType(), buySellData.getStrategyData().getStoplossTiming());
				}
				
				
				

				this.portfolioService.checkLivePositionsOnTommorow(date);
				
				entryExitMap = strategyBuilderService.signalsForTheDay(date, priceData, buySellData,this.portfolioService);
				
				
				if(buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit_atr"))) {
					
					Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();
					
					int targetSec = buySellData.getStrategyData().getSlots() - livePositions.size();
					limitOrderMap = new HashMap<>();
					
					List<LimitOrder> limitOrdersList = new LinkedList<>();
					for (String entry : entryExitMap.get("entry")) {

						if (!livePositions.contains(entry) && targetSec > 0 ) {

							float atrValue = priceData.getDaily_atr().get(date).get(entry);
							float closePrice = priceData.getDaily_closes().get(date).get(entry);
							float limit_price = closePrice - (buySellData.getStrategyData().getLimitPct() * atrValue);
							limit_price = Math.round(limit_price * 100f) / 100f;
							limitOrdersList.add(LimitOrder.builder().ticker(entry).limitPrice(limit_price).build());
							
							targetSec--;

						}

					}
					limitOrderMap.put("limit_orders", limitOrdersList);

				}
				
				
			}

			if (date.isEqual(buySellData.getStrategyData().getEndDate())) {
				this.portfolioService.endOfBacktest(date);
			}
			
			previousDate = date;

		}

	}

	private void processLimitOrdersLong(LocalDate date, LocalDate previousDate, Map<String, List<LimitOrder>> limitOrderMap,
			BuySellData buySellData) {
		if (limitOrderMap != null && limitOrderMap.get("limit_orders") != null
				&& !limitOrderMap.get("limit_orders").isEmpty()) {
			
			
			
			int maxQuantity = buySellData.getStrategyData().getMinQuantity() > 0 ?
					(int)buySellData.getStrategyData().getMinQuantity() : 1 ;
			
			float minStockPricePerSlot = buySellData.getStrategyData().getMinPrice() > 0 
					? buySellData.getStrategyData().getMinPrice() : 0 ;
			
			LimitEntrySignalDto entrySignals = LimitEntrySignalDto.builder().tradeDate(date)
					.limitOrders(limitOrderMap.get("limit_orders"))
					.maxSingleStock(buySellData.getStrategyData().getMaxSameTicker())
					.reasonForEntry("Entries").entryTime("open")
					.slotCapital(buySellData.getStrategyData().getStartingCapital()/buySellData.getStrategyData().getSlots())
					.maxSlots(buySellData.getStrategyData().getSlots())
//	                 Max Quantities set to 5 default.
					.maxQuantitites(maxQuantity).direction("long")
					.minStockPricePerSlot(minStockPricePerSlot)
					.previousDate(previousDate).build();
			
			this.portfolioService.executeLimitOrdersLong(entrySignals);
		
			} 
		}



	private void processNormalOrders(LocalDate date,LocalDate previousDate, Map<String, List<String>> entryExitMap,BuySellData buySellData) {

		if (entryExitMap != null && entryExitMap.get("entry") != null
				&& !entryExitMap.get("entry").isEmpty()) {
			
			
			
			int maxQuantity = buySellData.getStrategyData().getMinQuantity() > 0 ?
					(int)buySellData.getStrategyData().getMinQuantity() : 1 ;
			
			float minStockPricePerSlot = buySellData.getStrategyData().getMinPrice() > 0 
					? buySellData.getStrategyData().getMinPrice() : 0 ;
			
			EntrySignalsRequestDto entrySignals = EntrySignalsRequestDto.builder().tradeDate(date)
					.entries(entryExitMap.get("entry"))
					.maxSingleStock(buySellData.getStrategyData().getMaxSameTicker())
					.reasonForEntry("Entries").entryTime("open")
					.slotCapital(buySellData.getStrategyData().getStartingCapital()/buySellData.getStrategyData().getSlots())
					.maxSlots(buySellData.getStrategyData().getSlots())
//	                 Max Quantities set to 5 default.
					.maxQuantitites(maxQuantity).direction("long")
					.minStockPricePerSlot(minStockPricePerSlot)
					.previousDate(previousDate).build();
			
			this.portfolioService.executeEntrySignals(entrySignals);
			
		}
		
	}

}
