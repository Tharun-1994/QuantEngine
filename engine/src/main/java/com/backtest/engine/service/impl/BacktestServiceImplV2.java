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
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.LimitOrder;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.service.BacktestServiceV2;
import com.backtest.engine.service.PortfolioServiceV2;
import com.backtest.engine.service.StrategyBuilderServiceV2;
@Service
public class BacktestServiceImplV2 implements BacktestServiceV2 {

    private final PortfolioServiceImpl portfolioServiceImpl;

    private final PortfolioServiceImplV2 portfolioServiceImplV2;

	@Autowired
	PortfolioServiceV2 portfolioService;

	StrategyBuilderServiceV2 strategyBuilderService;

	BacktestServiceImplV2(StrategyBuilderServiceImplV2 strategyBuilderServiceImpl, PortfolioServiceImplV2 portfolioServiceImplV2, PortfolioServiceImpl portfolioServiceImpl) {
		this.strategyBuilderService = strategyBuilderServiceImpl;
		this.portfolioServiceImplV2 = portfolioServiceImplV2;
		this.portfolioServiceImpl = portfolioServiceImpl;
	}

	private void processLimitOrdersLong(LocalDate date, LocalDate previousDate,
			Map<String, List<LimitOrder>> limitOrderMap, BuySellDataV2 buySellData) {
		if (limitOrderMap != null && limitOrderMap.get("limit_orders") != null
				&& !limitOrderMap.get("limit_orders").isEmpty()) {

			int maxQuantity = buySellData.getStrategyData().getMinQuantity() > 0
					? (int) buySellData.getStrategyData().getMinQuantity()
					: 1;

			float minStockPricePerSlot = buySellData.getStrategyData().getMinPrice() > 0
					? buySellData.getStrategyData().getMinPrice()
					: 0;

			LimitEntrySignalDto entrySignals = LimitEntrySignalDto.builder().tradeDate(date)
					.limitOrders(limitOrderMap.get("limit_orders"))
					.maxSingleStock(buySellData.getStrategyData().getMaxSameTicker()).reasonForEntry("Entries")
					.entryTime("open")
					.slotCapital((int) buySellData.getStrategyData().getStartingCapital()
							/ buySellData.getStrategyData().getSlots())
					.maxSlots(buySellData.getStrategyData().getSlots())
//	                 Max Quantities set to 5 default.
					.maxQuantitites(maxQuantity).direction(buySellData.getStrategyData().getSystemType()).minStockPricePerSlot(minStockPricePerSlot)
					.previousDate(previousDate).build();

			this.portfolioService.executeLimitOrdersLong(entrySignals);

		}
	}

	private void processNormalOrders(LocalDate date, LocalDate previousDate, Map<String, List<String>> entryExitMap,
			BuySellDataV2 buySellData) {

		if (entryExitMap != null && entryExitMap.get("entry") != null && !entryExitMap.get("entry").isEmpty()) {

			int maxQuantity = buySellData.getStrategyData().getMinQuantity() > 0
					? (int) buySellData.getStrategyData().getMinQuantity()
					: 1;

			float minStockPricePerSlot = buySellData.getStrategyData().getMinPrice() > 0
					? buySellData.getStrategyData().getMinPrice()
					: 0;

			EntrySignalsRequestDto entrySignals = EntrySignalsRequestDto.builder().tradeDate(date)
					.entries(entryExitMap.get("entry")).maxSingleStock(buySellData.getStrategyData().getMaxSameTicker())
					.reasonForEntry("Entries").entryTime("open")
					.slotCapital((int) (buySellData.getStrategyData().getStartingCapital()
							/ buySellData.getStrategyData().getSlots()))
					.maxSlots(buySellData.getStrategyData().getSlots())
//	                 Max Quantities set to 5 default.
					.maxQuantitites(maxQuantity).direction(buySellData.getStrategyData().getSystemType()).minStockPricePerSlot(minStockPricePerSlot)
					.previousDate(previousDate).build();

			this.portfolioService.executeEntrySignals(entrySignals);

		}

	}

	@Override
	public BacktestReponseDto runBacktestV2(PriceDataV2 priceData, BuySellDataV2 buySellData) {

		this.portfolioService.setPriceDate(priceData, buySellData.getStrategyData().getStartingCapital(),
				buySellData.getStrategyData().getSlots(), buySellData.getStrategyData().getStopLossPct(),
				buySellData.getStrategyData().getTakeProfitPct());

		Map<String, List<String>> entryExitMap = null;

		Map<String, List<LimitOrder>> limitOrderMap = null;

		LocalDate previousDate = null;

		for (LocalDate date : priceData.getAll_dates()) {


			if (date.equals(LocalDate.of(2000, 1, 3))) {
				System.err.println();
			}

			if (((date.isEqual(priceData.getTrading_dates().get(0))
					|| date.isAfter(priceData.getTrading_dates().get(0)))
					&& date.isBefore(buySellData.getStrategyData().getEndDate()))

					|| date.isEqual(buySellData.getStrategyData().getEndDate())) {
				
				
				// Max Time is Enabled
				if(buySellData.getStrategyData().getMaxTime() > 0 ) {
					this.portfolioService.checkMaxTime(date,buySellData.getStrategyData().getMaxTime(),priceData);
				}
				

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
						if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("normal"))) {
							if (buySellData.getStrategyData().getSystemType()
							        .equals(StaticConfig.systemType.get("long"))
							    || buySellData.getStrategyData().getSystemType()
							        .equals(StaticConfig.systemType.get("short"))) {
							    processNormalOrders(date, previousDate, entryExitMap, buySellData);
							}

						} else if (buySellData.getStrategyData().getOrderType()
								.equals(StaticConfig.orderType.get("limit_atr"))
								|| buySellData.getStrategyData().getOrderType()
										.equals(StaticConfig.orderType.get("limit"))) {

							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("long"))  || buySellData.getStrategyData().getSystemType()
							        .equals(StaticConfig.systemType.get("short"))) {
								processLimitOrdersLong(date, previousDate, limitOrderMap, buySellData);
							}

						}
					}
				}

				this.portfolioService.markToMarket(date);
				this.portfolioService.updateTradeDayCount(date);

				// StopLoss
				if (StaticConfig.stoplossType.get("nrml").equals(buySellData.getStrategyData().getStoplossType())
						&& buySellData.getStrategyData().getStopLossPct() > 0) {
					this.portfolioService.checkStoplossHit(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				}

				// Takeprofit
				if (StaticConfig.takeProfitType.get("nrml").equals(buySellData.getStrategyData().getTakeprofitType())
						&& buySellData.getStrategyData().getTakeProfitPct() > 0) {
					this.portfolioService.checkTakeProfit(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				}

				this.portfolioService.checkLivePositionsOnTommorow(date);

				entryExitMap = strategyBuilderService.signalsForTheDayV1(date, priceData, buySellData,
						this.portfolioService);
				
				// If exit_timing is "close", execute exits immediately at today's close
				if (buySellData.getStrategyData().getExitTiming().equals("close")) {
				    if (entryExitMap != null && entryExitMap.get("exit") != null
				            && !entryExitMap.get("exit").isEmpty()) {
				        ExitSignalsRequestDto exitSignals = ExitSignalsRequestDto.builder().tradeDate(date)
				                .exits(entryExitMap.get("exit")).reasonForExit("Exits").exitTime("close").build();
				        this.portfolioService.executeExitSignals(exitSignals);
				    }
				}

				if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit_atr"))) {

					Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();

					int targetSec = buySellData.getStrategyData().getSlots() - livePositions.size();
					limitOrderMap = new HashMap<>();

					List<LimitOrder> limitOrdersList = new LinkedList<>();
					for (String entry : entryExitMap.get("entry")) {

						if (!livePositions.contains(entry) && targetSec > 0) {

							float atrValue = priceData.getDaily_atr().getValue(date, entry);
							float closePrice = priceData.getDaily_closes().getValue(date, entry);
							

							float limit_price = closePrice - (buySellData.getStrategyData().getLimitPct() * atrValue);
							limit_price = Math.round(limit_price * 100f) / 100f;
							limitOrdersList.add(LimitOrder.builder().ticker(entry).limitPrice(limit_price).build());

							targetSec--;

						}

					}
					limitOrderMap.put("limit_orders", limitOrdersList);

				} else if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit"))) {
					Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();
					int targetSec = buySellData.getStrategyData().getSlots() - livePositions.size();
					limitOrderMap = new HashMap<>();
					List<LimitOrder> limitOrdersList = new LinkedList<>();
					for (String entry : entryExitMap.get("entry")) {
						if (!livePositions.contains(entry) && targetSec > 0) {
							float closePrice = priceData.getDaily_closes().getValue(date, entry);
							float limit_price = closePrice * (1 - (buySellData.getStrategyData().getLimitPct() / 100));
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
		return this.portfolioService.getPortfolio();
	}
	
	
	@Override
	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, BuySellDataV2 buySellData) {

		this.portfolioService.setPriceDate(priceData, buySellData.getStrategyData().getStartingCapital(),
				buySellData.getStrategyData().getSlots(), buySellData.getStrategyData().getStopLossPct(),
				buySellData.getStrategyData().getTakeProfitPct());

		Map<String, List<String>> entryExitMap = null;

		Map<String, List<LimitOrder>> limitOrderMap = null;

		LocalDate previousDate = null;

		for (LocalDate date : priceData.getAll_dates()) {

			if (date.equals(LocalDate.of(2000, 1, 3))) {
				System.err.println();
			}

			if (((date.isEqual(priceData.getTrading_dates().get(0))
					|| date.isAfter(priceData.getTrading_dates().get(0)))
					&& date.isBefore(buySellData.getStrategyData().getEndDate()))

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
						if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("normal"))) {
							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("long"))
								|| buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("short"))) {
								processNormalOrders(date, previousDate, entryExitMap, buySellData);
							}

						} else if (buySellData.getStrategyData().getOrderType()
								.equals(StaticConfig.orderType.get("limit_atr"))
								|| buySellData.getStrategyData().getOrderType()
										.equals(StaticConfig.orderType.get("limit"))) {

							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("long"))
								|| buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("short"))) {
								processLimitOrdersLong(date, previousDate, limitOrderMap, buySellData);
							}

						}
					}
				}

				this.portfolioService.markToMarket(date);

				// StopLoss
				if (StaticConfig.stoplossType.get("nrml").equals(buySellData.getStrategyData().getStoplossType())
						&& buySellData.getStrategyData().getStopLossPct() > 0) {
					this.portfolioService.checkStoplossHit(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				}

				// Takeprofit
				if (StaticConfig.takeProfitType.get("nrml").equals(buySellData.getStrategyData().getTakeprofitType())
						&& buySellData.getStrategyData().getTakeProfitPct() > 0) {
					this.portfolioService.checkTakeProfit(date, buySellData.getStrategyData().getSystemType(),
							buySellData.getStrategyData().getStoplossTiming());
				}

				this.portfolioService.checkLivePositionsOnTommorow(date);

				entryExitMap = strategyBuilderService.signalsForTheDay(date, priceData, buySellData,
						this.portfolioService);

				if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit_atr"))) {

					Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();

					int targetSec = buySellData.getStrategyData().getSlots() - livePositions.size();
					limitOrderMap = new HashMap<>();

					List<LimitOrder> limitOrdersList = new LinkedList<>();
					for (String entry : entryExitMap.get("entry")) {

						if (!livePositions.contains(entry) && targetSec > 0) {

							float atrValue = priceData.getDaily_atr().getValue(date, entry);
							float closePrice = priceData.getDaily_closes().getValue(date, entry);
							

							float limit_price = closePrice - (buySellData.getStrategyData().getLimitPct() * atrValue);
							limit_price = Math.round(limit_price * 100f) / 100f;
							limitOrdersList.add(LimitOrder.builder().ticker(entry).limitPrice(limit_price).build());

							targetSec--;

						}

					}
					limitOrderMap.put("limit_orders", limitOrdersList);

				}else if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit"))) {
					Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();
					int targetSec = buySellData.getStrategyData().getSlots() - livePositions.size();
					limitOrderMap = new HashMap<>();
					List<LimitOrder> limitOrdersList = new LinkedList<>();
					for (String entry : entryExitMap.get("entry")) {
						if (!livePositions.contains(entry) && targetSec > 0) {
							float closePrice = priceData.getDaily_closes().getValue(date, entry);
							float limit_price = closePrice * (1 - (buySellData.getStrategyData().getLimitPct() / 100));
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
		return this.portfolioService.getPortfolio();
	}

	@Override
	public BacktestReponseDto runBacktestSimpleV2(PriceDataV2 priceData, Map<LocalDate, String> marketTrends,
			Map<String, BuySellDataV2> rulesOfDayRegimes) {
		
		Map<String, List<String>> entryExitMap = null;

		Map<String, List<LimitOrder>> limitOrderMap = null;
		
		LocalDate previousDate = null;
		
		BuySellDataV2 buySellData = null; 
		
		String marketTrendOfDay = "";
		String previousDayMarketTrend = "";
		
		for (LocalDate date : priceData.getAll_dates()) {
//			System.err.println(date);
			
			if (date.equals(LocalDate.of(2000, 1, 3))) {
				System.err.println();
				continue;
			}
			if (date.equals(LocalDate.of(2000, 2, 18))) {
				System.err.println();
			}
			if (((date.isEqual(priceData.getTrading_dates().get(0))
					|| date.isAfter(priceData.getTrading_dates().get(0)))
					&& date.isBefore(priceData.getEndDate()))

					|| date.isEqual(priceData.getEndDate())) {

				if (priceData.getTrading_dates().contains(date) && buySellData != null) {
					
					// Exit Orders
					if (buySellData.getStrategyData().getExitTiming().equals("open")) {

						if (entryExitMap != null && entryExitMap.get("exit") != null
								&& !entryExitMap.get("exit").isEmpty()) {

							ExitSignalsRequestDto exitSignals = ExitSignalsRequestDto.builder().tradeDate(date)
									.exits(entryExitMap.get("exit")).reasonForExit("Exits").exitTime("open").build();

							this.portfolioService.executeExitSignals(exitSignals);
						}
					}
					
					// Max Time is Enabled
					if(buySellData.getStrategyData().getMaxTime() > 0 ) {
						this.portfolioService.checkMaxTime(date,buySellData.getStrategyData().getMaxTime(),priceData);
					}
					
					// REGIME SHIFT
					if(!previousDayMarketTrend.equalsIgnoreCase(marketTrendOfDay)) {
						
						String reason = String.format("%s %s %s %s", "Market Shift", previousDayMarketTrend,"to", marketTrendOfDay);
						this.portfolioServiceImplV2.closeAllPositionsOnOpenPrice(date, priceData, reason);
						
					}
					
					
					// Entry Orders
					if (buySellData.getStrategyData().getEntryTiming().equals("open")) {
						if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("normal"))) {
							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("long"))
								|| buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("short"))) {
								processNormalOrders(date, previousDate, entryExitMap, buySellData);
							}

						} else if (buySellData.getStrategyData().getOrderType()
								.equals(StaticConfig.orderType.get("limit_atr"))
								|| buySellData.getStrategyData().getOrderType()
										.equals(StaticConfig.orderType.get("limit"))) {

							if (buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("long"))
								|| buySellData.getStrategyData().getSystemType()
									.equals(StaticConfig.systemType.get("short"))) {
								
								if(!buySellData.getStrategyData().getBannedMonths().contains(date.getMonthValue())) {
									processLimitOrdersLong(date, previousDate, limitOrderMap, buySellData);
								}
								
							}

						}
					}
					
					
					
				}
				
				this.portfolioService.markToMarket(date);
				
				if (buySellData != null) {
					
					// StopLoss
					if (StaticConfig.stoplossType.get("nrml").equals(buySellData.getStrategyData().getStoplossType())
							&& buySellData.getStrategyData().getStopLossPct() > 0) {
						this.portfolioService.checkStoplossHit(date, buySellData.getStrategyData().getSystemType(),
								buySellData.getStrategyData().getStoplossTiming());
					}

					// Takeprofit
					if (StaticConfig.takeProfitType.get("nrml").equals(buySellData.getStrategyData().getTakeprofitType())
							&& buySellData.getStrategyData().getTakeProfitPct() > 0) {
						this.portfolioService.checkTakeProfit(date, buySellData.getStrategyData().getSystemType(),
								buySellData.getStrategyData().getStoplossTiming());
					}
				}

				
				

				
				this.portfolioService.checkLivePositionsOnTommorow(date);
				
				previousDayMarketTrend = marketTrendOfDay;
				String newTrend = marketTrends.get(date);
				if (newTrend != null) {
				    marketTrendOfDay = newTrend;
				    BuySellDataV2 newBuySellData = rulesOfDayRegimes.get(marketTrendOfDay);
				    if (newBuySellData != null) {
				        buySellData = newBuySellData;
				    }
				}
				
				if (buySellData != null) {
					this.portfolioService.setBasicDeatils(priceData, buySellData.getStrategyData().getStartingCapital(),
							buySellData.getStrategyData().getSlots(), buySellData.getStrategyData().getStopLossPct(),
							buySellData.getStrategyData().getTakeProfitPct());

//					long start = System.nanoTime();
					entryExitMap = strategyBuilderService.signalsForTheDayV1(date, priceData, buySellData,this.portfolioService);
					
				}
				

//				long end = System.nanoTime();
				
//				double elapsedSeconds = (end - start) / 1_000_000_000.0;
//				System.err.printf("⏱️ Signals For the Day: %.3f seconds%n", elapsedSeconds);
				
				this.portfolioService.updateTradeDayCount(date);
				
				if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit_atr"))) {

					Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();

					int targetSec = buySellData.getStrategyData().getSlots() - livePositions.size();
					limitOrderMap = new HashMap<>();

					List<LimitOrder> limitOrdersList = new LinkedList<>();
					for (String entry : entryExitMap.get("entry")) {

						if (!livePositions.contains(entry) && targetSec > 0) {

							float atrValue = priceData.getDaily_atr().getValue(date, entry);
							float closePrice = priceData.getDaily_closes().getValue(date, entry);
							

							float limit_price = closePrice - (buySellData.getStrategyData().getLimitPct() * atrValue);
							limit_price = Math.round(limit_price * 100f) / 100f;
							limitOrdersList.add(LimitOrder.builder().ticker(entry).limitPrice(limit_price).build());

							targetSec--;

						}

					}
					limitOrderMap.put("limit_orders", limitOrdersList);

				} else if (buySellData.getStrategyData().getOrderType().equals(StaticConfig.orderType.get("limit"))) {
					Set<String> livePositions = this.portfolioService.getLiveHoldingsLogger();
					int exitCount = 0;
					for(String live : livePositions) {
							if(entryExitMap.get("exit").contains(live)) {
								exitCount++;
							}
					}
					
					int targetSec = buySellData.getStrategyData().getSlots() - (livePositions.size() - exitCount);
					limitOrderMap = new HashMap<>();
					List<LimitOrder> limitOrdersList = new LinkedList<>();
					for (String entry : entryExitMap.get("entry")) {
						if (!livePositions.contains(entry) && targetSec > 0) {
							float closePrice = priceData.getDaily_closes().getValue(date, entry);
							float limit_price = closePrice * (1 - (buySellData.getStrategyData().getLimitPct() / 100));
							limitOrdersList.add(LimitOrder.builder().ticker(entry).limitPrice(limit_price).build());
							targetSec--;

						}
					}
					limitOrderMap.put("limit_orders", limitOrdersList);

				}
				
				
				
			
			
		}
		
			if (date.isEqual(priceData.getEndDate())) {
				this.portfolioService.endOfBacktest(date);
			}

			previousDate = date;
			
			if(buySellData == null) {
				previousDayMarketTrend = marketTrendOfDay;
				 marketTrendOfDay = marketTrends.get(date);
				if(marketTrendOfDay != null) {
					buySellData = rulesOfDayRegimes.get(marketTrendOfDay);
					this.portfolioService.setPriceDate(priceData, buySellData.getStrategyData().getStartingCapital(),
							buySellData.getStrategyData().getSlots(), buySellData.getStrategyData().getStopLossPct(),
							buySellData.getStrategyData().getTakeProfitPct());
				}

			}

		
	}
		return this.portfolioService.getPortfolio();
	}

}