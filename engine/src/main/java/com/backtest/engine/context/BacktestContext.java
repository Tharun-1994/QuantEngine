package com.backtest.engine.context;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import com.backtest.engine.dto.request.MarketRegimeDto;
import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.StrategyBucketRequestDto;
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.entity.StrategyDataV2;
import com.backtest.engine.ruleBuilder.RuleTreeFlattener;
import com.backtest.engine.service.PriceDataLoaderService;
import com.backtest.engine.service.StrategyBuilderServiceV2;
import com.backtest.engine.util.ArrowDataFrame;
import com.backtest.engine.util.ArrowStringDataFrame;
import com.backtest.engine.util.IndicatorRuleLoader;
import com.backtest.engine.util.ParquetToMap;
import com.backtest.engine.util.PriceLoader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class BacktestContext implements AutoCloseable {
	
	PriceDataV2 priceDataV2;
	private PriceDataLoaderService priceDataService;
	

	
	private StrategyBuilderServiceV2 strategyBuilderServiceV2;
	
	
	Map<String, List<LocalDate>> parquetDatesMapList;
	Map<String, ArrowDataFrame> parquetFileValueMap;
	Map<String, ArrowStringDataFrame> parquetMapSet;	
	
	public BacktestContext(PriceDataLoaderService priceDataService, StrategyBuilderServiceV2 strategyBuilderServiceV2) {
		this.priceDataService = priceDataService;

		this.strategyBuilderServiceV2 = strategyBuilderServiceV2;
	}
	
	public String inputPath(String strategy_name, String universe,String backtestDataPath) {
		return String.format("%s/%s/%s/%s", backtestDataPath, strategy_name, "input", universe);
	}
	public String path(String strategy_name, String fileName, String universe,String backtestDataPath) {

		if (fileName.startsWith("trading_") || fileName.startsWith("all_dates")) {
			return Paths.get(inputPath(strategy_name, universe,backtestDataPath), fileName).toString();
		}
		return Paths.get(inputPath(strategy_name, universe,backtestDataPath), fileName).toUri().toString();
	}
	
	
	public PriceDataV2 getPriceData(StrategyBucketRequestDto strategyRequest,String backtestDataPath) {
		this.parquetDatesMapList = new HashMap<>();
		this.parquetFileValueMap = new HashMap<>();
		this.parquetMapSet = new HashMap<>();	
		
		Map<Integer, Map<String, String>> priceLoaderPathMap = new HashMap<>();
		int i = 0;
		for (MarketRegimeDto marketRegime : strategyRequest.getRegimes()) {
			PriceLoader priceLoader = PriceLoader.builder().universe(marketRegime.getUniverse())
					.atrLimitLookback(marketRegime.getAtrLimitLookback())
					.atrLookbackStp(marketRegime.getAtrLookbackStp()).atrLookbackTp(marketRegime.getAtrLookbackTp())
					.rebalance(strategyRequest.getRebalance()).rankingIndicator(marketRegime.getRanking())
					.rankingLookback(marketRegime.getRankingLookback()).build();

			priceLoaderPathMap.put(i, priceLoader.getFilesForRebalance(strategyRequest.getRegimes()));
			i++;
		}
		
		
		for (Integer regimeIndex : priceLoaderPathMap.keySet()) {
			for (String keyFile : priceLoaderPathMap.get(regimeIndex).keySet()) {
				try {
					if (keyFile.equals("trading_dates") || keyFile.equals("all_dates")) {
						List<LocalDate> eachList = ParquetToMap
								.loadParquetToDateList(path(strategyRequest.getName(),priceLoaderPathMap.get(regimeIndex).get(keyFile),
										strategyRequest.getRegimes().get(0).getUniverse(),backtestDataPath));
						parquetDatesMapList.put(keyFile, eachList);
					} else if (keyFile.equals("universes")) {
						ArrowStringDataFrame universe = ArrowStringDataFrame
								.load(path(strategyRequest.getName(),priceLoaderPathMap.get(regimeIndex).get(keyFile),
										strategyRequest.getRegimes().get(0).getUniverse(),backtestDataPath));
						parquetMapSet.put(keyFile, universe);
					} else {
						if ((keyFile.equals("atr_limit")
								&& (priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
										|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty()))
								|| keyFile.equals("atr_stp")
										&& (priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
												|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty())
								|| keyFile.equals("atr_tp")
										&& (priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
												|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty())) {
							continue;
						}
						
						if(priceLoaderPathMap.get(regimeIndex).get(keyFile) == null || priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
								|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty() || priceLoaderPathMap.get(regimeIndex).get(keyFile).contains("null")) {
							continue;
						}
						
						ArrowDataFrame currentDf = ArrowDataFrame.load(path(strategyRequest.getName(),priceLoaderPathMap.get(regimeIndex).get(keyFile),
										strategyRequest.getRegimes().get(0).getUniverse(),backtestDataPath));
						parquetFileValueMap.put(keyFile, currentDf);
					}
				} catch (Exception e) { // Broadened from SQLException, as no SQL is involved
					// Replace with proper logging or error handling
					e.printStackTrace();
				}
			}
		}
		
		ArrowDataFrame daily_atr = Stream.of("atr_limit", "atr_stp", "atr_tp").map(parquetFileValueMap::get)
				.filter(Objects::nonNull).findFirst().orElse(null);
		
		
		PriceDataV2 priceData = this.priceDataService.loadPricesMarketDatav2(parquetFileValueMap.get("closes"),
				parquetFileValueMap.get("opens"), parquetFileValueMap.get("highs"), parquetFileValueMap.get("lows"),
				parquetMapSet.get("universes"), parquetDatesMapList.get("trading_dates"),
				parquetDatesMapList.get("all_dates"), daily_atr,null);
		priceData.setEndDate(strategyRequest.getEndDate());
		
		return priceData;
		
	}
	
	
	
	public BuySellDataV2 getBuySellData(StrategyBucketRequestDto strategyRequest,PriceDataV2 priceData,String backtestDataPath) {
		
//		List<RuleDto> entryRuleConditions = strategyRequest.getRegimes().get(0).getEntryRules();
//		List<RuleDto> exitRuleConditions = strategyRequest.getRegimes().get(0).getExitRules();
		String univ = strategyRequest.getRegimes().get(0).getUniverse();
		ObjectMapper mapper = new ObjectMapper()
		        .findAndRegisterModules()
		        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
		        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);

		Object entryObj = strategyRequest.getRegimes().get(0).getEntryRulesTree();
		Object exitObj  = strategyRequest.getRegimes().get(0).getExitRulesTree();

		RuleGroupNodeDto entryTree = mapper.convertValue(entryObj, RuleGroupNodeDto.class);
		RuleGroupNodeDto exitTree  = mapper.convertValue(exitObj,  RuleGroupNodeDto.class);
		
		List<RuleDto> entryLeafRules = RuleTreeFlattener.flatten(entryTree);
		List<RuleDto> exitLeafRules  = RuleTreeFlattener.flatten(exitTree);
		
		Map<String, ArrowDataFrame> entryMap = IndicatorRuleLoader.loadTables(entryLeafRules,inputPath(strategyRequest.getName(), univ,backtestDataPath));
		Map<String, ArrowDataFrame> exitMap = IndicatorRuleLoader.loadTables(exitLeafRules, inputPath(strategyRequest.getName(), univ,backtestDataPath));

		StrategyDataV2 strategyData = StrategyDataV2.builder().entryRulesList(entryLeafRules)
				.exitRuleList(exitLeafRules).entryIndicators(entryMap).exitIndicators(exitMap)
				.startingCapital(strategyRequest.getRegimes().get(0).getCapital())
				.slots(strategyRequest.getRegimes().get(0).getSlots())
				.stopLossPct(strategyRequest.getRegimes().get(0).getStoplossPct())
				.takeProfitPct(strategyRequest.getRegimes().get(0).getTakeprofitPct())
				.stoplossTiming(strategyRequest.getRegimes().get(0).getStoplossTiming())
				.takeprofitTiming(strategyRequest.getRegimes().get(0).getTakeprofitTiming())
				.entryTiming(strategyRequest.getRegimes().get(0).getEntryTiming())
				.exitTiming(strategyRequest.getRegimes().get(0).getExitTiming())
				.ranking(this.parquetFileValueMap.get("ranking"))
				.rankingOrder(strategyRequest.getRegimes().get(0).getRankingOrder())
				.startDate(strategyRequest.getStartDate()).endDate(strategyRequest.getEndDate())
				.minPrice(strategyRequest.getMinPrice()).minQuantity(strategyRequest.getMinQuantity())
				.stoplossType(strategyRequest.getRegimes().get(0).getStoplossType())
				.takeprofitType(strategyRequest.getRegimes().get(0).getTakeprofitType())
				.systemType(strategyRequest.getSystemType())
				.orderType(strategyRequest.getRegimes().get(0).getOrderType())
				.atrLimitLookback(strategyRequest.getRegimes().get(0).getAtrLimitLookback())
				.limitPct(strategyRequest.getRegimes().get(0).getLimitPct())
				.maxTime(strategyRequest.getRegimes().get(0).getMaxTime())
				.bannedMonths(strategyRequest.getRegimes().get(0).getBannedMonths())
				.entryRulesTree(entryTree)
				.exitRulesTree(exitTree)
				.build();
		
		BuySellDataV2 buySellData = this.strategyBuilderServiceV2.generateSignalsV1(strategyData,priceData);
		buySellData.setStrategyData(strategyData);
		return buySellData;
	}
	
	
	public void writeBacktestResponse(StrategyBucketRequestDto strategyRequest,BacktestReponseDto backtestResponse,String backtestOPath) {
		// Setup Jackson ObjectMapper
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule()); // Handles LocalDate, etc.
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // Use ISO-8601 dates
		mapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty print
		
		try {
			// Serialize to JSON files
			mapper.writeValue(Paths.get(String.format("%s/%s/%s/%s.json", backtestOPath,strategyRequest.getName(),"output", "Equity")).toFile(),
					backtestResponse.getEquityLogger());

			mapper.writeValue(Paths.get(String.format("%s/%s/%s/%s.json", backtestOPath,strategyRequest.getName(),"output", "TradeList")).toFile(),
					backtestResponse.getTradeLogger());

		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}
	
	
	
	
	
	
	
	
	

	@Override
	public void close() {
	    // 1. Close ArrowDataFrames in the value map
	    if (parquetFileValueMap != null) {
	        parquetFileValueMap.values().stream()
	            .filter(Objects::nonNull)
	            .forEach(df -> {
	                try {
	                    df.close();
	                } catch (Exception e) {
	                    System.err.println("Error closing ArrowDataFrame: " + e.getMessage());
	                }
	            });
	        parquetFileValueMap.clear();
	    }

	    // 2. Close ArrowStringDataFrames in the map set
	    if (parquetMapSet != null) {
	        parquetMapSet.values().stream()
	            .filter(Objects::nonNull)
	            .forEach(df -> {
	                try {
	                    df.close();
	                } catch (Exception e) {
	                    System.err.println("Error closing ArrowStringDataFrame: " + e.getMessage());
	                }
	            });
	        parquetMapSet.clear();
	    }
	    
	    // 3. Optional: Clear the list map to free up references
	    if (parquetDatesMapList != null) {
	        parquetDatesMapList.clear();
	    }
	}

}
