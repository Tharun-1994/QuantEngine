package com.backtest.engine.controllers;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtest.engine.context.BacktestContext;
import com.backtest.engine.context.BacktestContextFactory;
import com.backtest.engine.dto.request.MarketRegimeDto;
import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.dto.request.StrategyBucketRequestDto;
import com.backtest.engine.dto.request.StrategyRequestDto;
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.dto.response.SingleBarSignalsResponseDto; // Patch 31
import com.backtest.engine.service.SingleBarEvaluator; // Patch 31
import com.backtest.engine.entity.BuySellData;
import com.backtest.engine.entity.BuySellDataV2;
import com.backtest.engine.entity.PriceData;
import com.backtest.engine.entity.PriceDataV2;
import com.backtest.engine.entity.RegimeOverlay;
import com.backtest.engine.entity.RuleCondition;
import com.backtest.engine.entity.StrategyData;
import com.backtest.engine.entity.StrategyDataV2;
import com.backtest.engine.service.BacktestService;
import com.backtest.engine.service.BacktestServiceV2;
import com.backtest.engine.service.MarketTrendServiceV2;
import com.backtest.engine.service.PortfolioService;
import com.backtest.engine.service.PriceDataLoaderService;
import com.backtest.engine.service.StrategyBuilderService;
import com.backtest.engine.service.StrategyBuilderServiceV2;
import com.backtest.engine.util.ArrowDataFrame;
import com.backtest.engine.util.ArrowStringDataFrame;
import com.backtest.engine.dto.request.ExecutionStepRequestDto;
import com.backtest.engine.util.BacktestExecutionException;
import org.springframework.http.ResponseEntity;
import com.backtest.engine.util.IndicatorRuleLoader;
import com.backtest.engine.util.ParquetToMap;
import com.backtest.engine.util.PriceLoader;
import com.backtest.engine.ruleBuilder.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import net.tlabs.tablesaw.parquet.TablesawParquet;
import tech.tablesaw.api.DateTimeColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.io.csv.CsvWriteOptions;

@RestController
public class BacktestEngineController {
	@Value("${app.data.dir}")
	private String dataDir;

	@Value("${backtest.data.path}")
	private String backtestDataPath;

	@Value("${backtest.output.path}")
	private String backtestOPath;

	private final BacktestService backtestService;
	private final BacktestServiceV2 backtestServiceV2;

	private final BacktestContextFactory backtestContextFactory;
	private final SingleBarEvaluator singleBarEvaluator;
	private final PriceDataLoaderService priceDataService;
	private final StrategyBuilderService strategyBuilderService;
	private final StrategyBuilderServiceV2 strategyBuilderServiceV2;

	private final MarketTrendServiceV2 marketTrendServiceV2;

	private final PortfolioService portfolioService;

	public BacktestEngineController(BacktestService backtestService, PriceDataLoaderService priceDataService,
			StrategyBuilderService strategyBuilderService, PortfolioService portfolioService,
			StrategyBuilderServiceV2 strategyBuilderServiceV2, BacktestServiceV2 backtestServiceV2,
			MarketTrendServiceV2 marketTrendServiceV2, BacktestContextFactory backtestContextFactory,
			SingleBarEvaluator singleBarEvaluator) {
		this.backtestService = backtestService;
		this.priceDataService = priceDataService;
		this.strategyBuilderService = strategyBuilderService;
		this.portfolioService = portfolioService;
		this.strategyBuilderServiceV2 = strategyBuilderServiceV2;
		this.backtestServiceV2 = backtestServiceV2;
		this.marketTrendServiceV2 = marketTrendServiceV2;
		this.backtestContextFactory = backtestContextFactory;
		this.singleBarEvaluator = singleBarEvaluator;
	}

	public Map<String, Map<LocalDate, Map<String, Float>>> loadTables(List<RuleCondition> conditions, String universe,
			String dataDir) {

		Map<String, Map<LocalDate, Map<String, Float>>> tableMap = new HashMap<>();
		for (RuleCondition rc : conditions) {
			// build filename: e.g. "rsi_14_sp500.parquet"
			String fileName = "";

			if (rc.getIndicator().equals("unadjusted_close")) {
				fileName = RuleParser.buildParquetFileName("DAILY_unadjusted_closes");
			} else {
				fileName = RuleParser.buildParquetFileName(rc.getIndicator(), rc.getIndicatorLookBack());
			}

			Path parquetPath = Paths.get(dataDir, fileName);
			Map<LocalDate, Map<String, Float>> indicatorMap;
			try {
				indicatorMap = ParquetToMap.loadParquetToMap(parquetPath.toString());
				tableMap.put(rc.getIndicator() + "_" + rc.getIndicatorLookBack(), indicatorMap);
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}
		return tableMap;
	}

	@GetMapping("/api/data")
	public String getAll() {
		Table data = Table.read().csv("C:\\\\Tharun\\\\Prices\\\\Sp500\\\\daily_closes.csv");
		return data.first(1).toString();
	}

	@GetMapping("/backtest/1")
	public BacktestReponseDto backtestRun() throws InterruptedException, ExecutionException {
		Instant start = Instant.now();

		int slots = 10;

		TablesawParquet.register();

		Instant start_prices_time = Instant.now();

		ExecutorService executor = Executors.newFixedThreadPool(4);

		CompletableFuture<Table> closesFuture = CompletableFuture.supplyAsync(
				() -> Table.read().file("C:\\\\\\\\Tharun\\\\\\\\Prices\\\\\\\\Sp500\\\\\\\\daily_closes.parquet"),
				executor);
		CompletableFuture<Table> opensFuture = CompletableFuture.supplyAsync(
				() -> Table.read().file("C:\\\\\\\\Tharun\\\\\\\\Prices\\\\\\\\Sp500\\\\\\\\daily_opens.parquet"),
				executor);
		CompletableFuture<Table> highsFuture = CompletableFuture.supplyAsync(
				() -> Table.read().file("C:\\\\\\\\Tharun\\\\\\\\Prices\\\\\\\\Sp500\\\\\\\\daily_highs.parquet"),
				executor);
		CompletableFuture<Table> lowsFuture = CompletableFuture.supplyAsync(
				() -> Table.read().file("C:\\\\\\\\Tharun\\\\\\\\Prices\\\\\\\\Sp500\\\\\\\\daily_lows.parquet"),
				executor);
		CompletableFuture<Table> daily_universesFuture = CompletableFuture.supplyAsync(
				() -> Table.read()
						.file("C:\\\\\\\\Tharun\\\\\\\\Prices\\\\\\\\Sp500\\\\\\\\sp500univereseList.parquet"),
				executor);
		CompletableFuture<Table> trading_datesFuture = CompletableFuture.supplyAsync(
				() -> Table.read().file("C:\\\\\\\\Tharun\\\\\\\\Prices\\\\\\\\Sp500\\\\\\\\trading_dates.parquet"),
				executor);
		CompletableFuture<Table> all_datesFuture = CompletableFuture.supplyAsync(
				() -> Table.read().file("C:\\\\\\\\Tharun\\\\\\\\Prices\\\\\\\\Sp500\\\\\\\\all_dates.parquet"),
				executor);
		CompletableFuture<Table> rsi_14Future = CompletableFuture.supplyAsync(
				() -> Table.read().file("C:\\\\\\\\Tharun\\\\\\\\Prices\\\\\\\\Sp500\\\\\\\\rsi_14.parquet"), executor);

		// Wait for all to complete
		Table daily_closes = closesFuture.get().sortAscendingOn("Date");
		Table daily_opens = opensFuture.get().sortAscendingOn("Date");
		;
		Table daily_highs = highsFuture.get().sortAscendingOn("Date");
		;
		Table daily_lows = lowsFuture.get().sortAscendingOn("Date");
		;
		Table daily_universes = daily_universesFuture.get().sortAscendingOn("Date");
		;
		trading_datesFuture.get().column(0).setName("DateTime");
		Table trading_dates = trading_datesFuture.get().sortAscendingOn("DateTime");
		;
		Table all_dates = all_datesFuture.get().sortAscendingOn("Date");
		;

		executor.shutdown();

//		Table daily_closes = Table.read().file("C:\\\\Tharun\\\\Prices\\\\Sp500\\\\daily_closes.parquet");
//		Table daily_opens = Table.read().file("C:\\\\Tharun\\\\Prices\\\\Sp500\\\\daily_opens.parquet");
//		Table daily_highs = Table.read().file("C:\\\\Tharun\\\\Prices\\\\Sp500\\\\daily_highs.parquet");
//		Table daily_lows = Table.read().file("C:\\\\Tharun\\\\Prices\\\\Sp500\\\\daily_lows.parquet");

//		Table daily_universes = Table.read().file("C:\\\\Tharun\\\\Prices\\\\Sp500\\\\sp500univereseList.parquet");
//
//		Table trading_dates = Table.read().file("C:\\\\Tharun\\\\Prices\\\\Sp500\\\\trading_dates.parquet");
//		Table all_dates = Table.read().file("C:\\\\Tharun\\\\Prices\\\\Sp500\\\\all_dates.parquet");
//		Table rsi_14 = Table.read().file("C:\\\\Tharun\\\\Prices\\\\Sp500\\\\rsi_14_sp500.parquet");

		DateTimeColumn dateTimeCol = trading_dates.dateTimeColumn("DateTime");
		List<LocalDate> trading_dates_list = dateTimeCol.asList().stream().map(zdt -> zdt.toLocalDate())
				.collect(Collectors.toList());

		DateTimeColumn all_dates_col = all_dates.dateTimeColumn("Date");

		List<LocalDate> all_dates_list = all_dates_col.asList().stream().map(zdt -> zdt.toLocalDate())
				.collect(Collectors.toList());

		long end_prices_time = Duration.between(start_prices_time, Instant.now()).toMillis();
		System.err.println("Read Prices Timing : " + end_prices_time);

//		-------------------------------------------------------------------------Logic Of backtesting------------------------

		Instant priceDate_time = Instant.now();
		PriceData priceData = null;

		long end_pricedata = Duration.between(priceDate_time, Instant.now()).toMillis();
		System.err.println("Read PricesData : " + end_pricedata);

		String entry_rules = "rsi_14 < 35";
		String exit_rules = "rsi_14 > 70";
		String universe = "Sp500";

		List<RuleCondition> entryRuleConditions = RuleParser.parseEntryRules(entry_rules);
		List<RuleCondition> exitRuleConditions = RuleParser.parseEntryRules(exit_rules);

		Instant indicator_load_start_time = Instant.now();

		Map<String, Table> entryMap = null;

		Map<String, Table> exitMap = null;

		StrategyData strategyData = null;

		long indicator_load_end_time = Duration.between(indicator_load_start_time, Instant.now()).toMillis();
		System.err.println("Read Indicator : " + indicator_load_end_time);

		BuySellData buySellData = this.strategyBuilderService.generateSignals(strategyData);
		buySellData.setStrategyData(strategyData);

		Instant backtest_start = Instant.now();
		backtestService.runBacktest(priceData, buySellData);

		long backtest_end = Duration.between(backtest_start, Instant.now()).toMillis();
		System.err.println("Read PricesData : " + backtest_end);

		long elapsed = Duration.between(start, Instant.now()).toMillis();
		System.err.println(elapsed);

		// Setup Jackson ObjectMapper
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule()); // Handles LocalDate, etc.
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // Use ISO-8601 dates
		mapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty print

		try {
			// Serialize to JSON files
			mapper.writeValue(Paths.get("C:/Tharun/Prices/Sp500/Equity.json").toFile(),
					this.portfolioService.getPortfolio().getEquityLogger());

			mapper.writeValue(Paths.get("C:/Tharun/Prices/Sp500/TradeList.json").toFile(),
					this.portfolioService.getPortfolio().getTradeLogger());

		} catch (IOException e) {
			e.printStackTrace();
		}

		return this.portfolioService.getPortfolio();
	}

	public String inputPath(String strategy_name, String universe) {
		return String.format("%s/%s/%s/%s", backtestDataPath, strategy_name, "input", universe);
	}

	public String path(String strategy_name, String fileName, String universe) {

		if (fileName.startsWith("trading_") || fileName.startsWith("all_dates")) {
			return Paths.get(inputPath(strategy_name, universe), fileName).toString();
		}
		return Paths.get(inputPath(strategy_name, universe), fileName).toUri().toString();
	}

//	public CompletableFuture<Table> loadTableAsync(String fileName, Executor executor) {
//		return CompletableFuture.supplyAsync(() -> Table.read().file(path(fileName)), executor);
//	}

//	public Map<String, CompletableFuture<Table>> loadAllAsync(Executor executor, String rebalance, String universe) {
//		Map<String, String> files = PriceLoader.getFilesForRebalance(rebalance, universe);
//
//		return files.entrySet().stream()
//				.collect(Collectors.toMap(Map.Entry::getKey, e -> loadTableAsync(e.getValue(), executor)));
//	}

	@PostMapping("api/backtest")
	public BacktestReponseDto runBacktest(@RequestBody StrategyRequestDto strategyRequest)
			throws InterruptedException, ExecutionException {
		Instant start = Instant.now();

		Instant start_prices_time = Instant.now();

//		Map<String, String> files = PriceLoader.getFilesForRebalance(strategyRequest.getRebalance(),
//				strategyRequest.getUniverse(), strategyRequest.getRanking(), strategyRequest.getRankingLookback(),
//				strategyRequest.getAtrLimitLookback(), strategyRequest.getAtrLookbackStp(),
//				strategyRequest.getAtrLookbackTp());
		Map<String, String> files = new HashMap<>();
		Map<String, Map<LocalDate, Map<String, Float>>> parquetFileValueMap = new HashMap<>();
		Map<String, List<LocalDate>> parquetDatesMapList = new HashMap<>();
		Map<String, Map<LocalDate, Set<String>>> parquetMapSet = new HashMap<>();

		for (String keyFile : files.keySet()) {

			try {
				if (keyFile.equals("trading_dates") || keyFile.equals("all_dates")) {

					List<LocalDate> eachList = ParquetToMap.loadParquetToDateList(
							path(strategyRequest.getName(), files.get(keyFile), strategyRequest.getUniverse()));
					parquetDatesMapList.put(keyFile, eachList);

				} else if (keyFile.equals("universes")) {
					Map<LocalDate, Set<String>> universe = ParquetToMap.loadParquetToMapListTickers(
							path(strategyRequest.getName(), files.get(keyFile), strategyRequest.getUniverse()));

					parquetMapSet.put(keyFile, universe);
				} else {

					System.err.println(keyFile);

					if ((keyFile.equals("atr_limit") && (files.get(keyFile).isBlank() || files.get(keyFile).isEmpty()))
							|| keyFile.equals("atr_stp")
									&& (files.get(keyFile).isBlank() || files.get(keyFile).isEmpty())
							|| keyFile.equals("atr_tp")
									&& (files.get(keyFile).isBlank() || files.get(keyFile).isEmpty())) {
						continue;
					}

					System.err.println(keyFile);

					Map<LocalDate, Map<String, Float>> parquetIter = ParquetToMap.loadParquetToMap(
							path(strategyRequest.getName(), files.get(keyFile), strategyRequest.getUniverse()));
					parquetFileValueMap.put(keyFile, parquetIter);

				}
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}

//		System.exit(0);
		System.err.println(parquetFileValueMap.keySet());

//		Map<LocalDate, Map<String, Float>> daily_atr = !parquetFileValueMap.get("atr_limit").isEmpty() ? parquetFileValueMap.get("atr_limit") : null;
//		
//		if(daily_atr == null) {
//			daily_atr = !parquetFileValueMap.get("atr_stp").isEmpty() ? parquetFileValueMap.get("atr_stp") : null;
//		}
//		
//		if(daily_atr == null) {
//			daily_atr = !parquetFileValueMap.get("atr_tp").isEmpty() ? parquetFileValueMap.get("atr_tp") : null;
//		}

		Map<LocalDate, Map<String, Float>> daily_atr = Stream.of("atr_limit", "atr_stp", "atr_tp")
				.map(parquetFileValueMap::get).filter(Objects::nonNull).filter(m -> !m.isEmpty()).findFirst()
				.orElse(null);

		PriceData priceData = this.priceDataService.loadPricesMarketData(parquetFileValueMap.get("closes"),
				parquetFileValueMap.get("opens"), parquetFileValueMap.get("highs"), parquetFileValueMap.get("lows"),
				parquetMapSet.get("universes"), parquetDatesMapList.get("trading_dates"),
				parquetDatesMapList.get("all_dates"), daily_atr);

		long end_prices_time = Duration.between(start_prices_time, Instant.now()).toMillis();
		System.err.println("Read Prices Timing : " + end_prices_time);

//		-------------------------------------------------------------Logic Of backtesting------------------------------

		Instant priceDate_time = Instant.now();

		long end_pricedata = Duration.between(priceDate_time, Instant.now()).toMillis();
		System.err.println("Read PricesData : " + end_pricedata);

		List<RuleCondition> entryRuleConditions = RuleParser.parseEntryRules(strategyRequest.getEntryRules());
		List<RuleCondition> exitRuleConditions = RuleParser.parseEntryRules(strategyRequest.getExitRules());

		Instant indicator_load_start_time = Instant.now();

		Map<String, Map<LocalDate, Map<String, Float>>> entryMap = loadTables(entryRuleConditions,
				strategyRequest.getUniverse(), backtestDataPath);

		Map<String, Map<LocalDate, Map<String, Float>>> exitMap = loadTables(exitRuleConditions,
				strategyRequest.getUniverse(), backtestDataPath);

		StrategyData strategyData = StrategyData.builder().entryRulesList(entryRuleConditions)
				.exitRuleList(exitRuleConditions).entryIndicators(entryMap).exitIndicators(exitMap)
				.startingCapital(strategyRequest.getCapital()).slots(strategyRequest.getSlots())
				.stopLossPct(strategyRequest.getStoplossPct()).takeProfitPct(strategyRequest.getTakeprofitPct())
				.stoplossTiming(strategyRequest.getStoplossTiming())
				// Patch 72m.1: anchor passes through builder; null when not PORTFOLIO.
//				.portfolioStoplossAnchor(
//					strategyRequest.getRegimes() != null && !strategyRequest.getRegimes().isEmpty()
//						? strategyRequest.getRegimes().get(0).getPortfolioStoplossAnchor()
//						: null)
				.takeprofitTiming(strategyRequest.getTakeprofitTiming()).entryTiming(strategyRequest.getEntryTiming())
				.exitTiming(strategyRequest.getExitTiming()).ranking(parquetFileValueMap.get("ranking"))
				.rankingOrder(strategyRequest.getRankingOrder()).startDate(strategyRequest.getStartDate())
				.endDate(strategyRequest.getEndDate()).minPrice(strategyRequest.getMinPrice())
				.minQuantity(strategyRequest.getMinQuantity()).stoplossType(strategyRequest.getStoplossType())
				.takeprofitType(strategyRequest.getTakeprofitType()).systemType(strategyRequest.getSystemType())
				.orderType(strategyRequest.getOrderType()).atrLimitLookback(strategyRequest.getAtrLimitLookback())
				.limitPct(strategyRequest.getLimitPct()).build();

		long indicator_load_end_time = Duration.between(indicator_load_start_time, Instant.now()).toMillis();
		System.err.println("Read Indicator : " + indicator_load_end_time);

		BuySellData buySellData = this.strategyBuilderService.generateSignals(strategyData);
		buySellData.setStrategyData(strategyData);

		// Max Single Same Slot
		buySellData.getStrategyData().setMaxSameTicker(1);

		Instant backtest_start = Instant.now();
		backtestService.runBacktest(priceData, buySellData);

		long backtest_end = Duration.between(backtest_start, Instant.now()).toMillis();
		System.err.println("Read PricesData : " + backtest_end);

		long elapsed = Duration.between(start, Instant.now()).toMillis();
		System.err.println(elapsed);

		// Setup Jackson ObjectMapper
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule()); // Handles LocalDate, etc.
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // Use ISO-8601 dates
		mapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty print

		try {
			// Serialize to JSON files
			mapper.writeValue(Paths.get(String.format("%s%s.json", backtestOPath, "/Equity")).toFile(),
					this.portfolioService.getPortfolio().getEquityLogger());

			mapper.writeValue(Paths.get(String.format("%s%s.json", backtestOPath, "/TradeList")).toFile(),
					this.portfolioService.getPortfolio().getTradeLogger());

		} catch (Exception e) {
			e.printStackTrace();
		}

		return this.portfolioService.getPortfolio();
	}

	public void writeToCsvWithTablesaw(String backtestOPath) {
		try {
			// --- Process EquityLogger data ---
			Table equityTable = Table.create("Equity");
			// Assume EquityLogger has a list of objects, each with a date and a value
			// Add columns to the table from your EquityLogger data
			// For example:
			// DateColumn dateCol = DateColumn.create("Date");
			// DoubleColumn valueCol = DoubleColumn.create("Equity Value");
			// ... populate columns with data from
			// this.portfolioService.getPortfolio().getEquityLogger() ...
			// equityTable.addColumns(dateCol, valueCol);

			// Define CSV write options for pretty printing
			CsvWriteOptions equityOptions = CsvWriteOptions
					.builder(Paths.get(String.format("%s%s.csv", backtestOPath, "/Equity")).toFile()).header(true)
					.separator(',').quoteChar('"').build();

			equityTable.write().usingOptions(equityOptions);

			// --- Process TradeList data ---
			Table tradeListTable = Table.create("TradeList");
			// Add columns to the table from your TradeList data
			// For example:
			// StringColumn symbolCol = StringColumn.create("Symbol");
			// DoubleColumn profitCol = DoubleColumn.create("Profit");
			// ... populate columns from
			// this.portfolioService.getPortfolio().getTradeLogger() ...
			// tradeListTable.addColumns(symbolCol, profitCol);

			// Define CSV write options
			CsvWriteOptions tradeListOptions = CsvWriteOptions
					.builder(Paths.get(String.format("%s%s.csv", backtestOPath, "/TradeList")).toFile()).header(true)
					.separator(',').quoteChar('"').build();

			tradeListTable.write().usingOptions(tradeListOptions);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@PostMapping("api/runbacktestv2")
	public BacktestReponseDto runBacktestV2(@RequestBody StrategyBucketRequestDto strategyRequest) {

		if (strategyRequest.getMarketRegimeType().equalsIgnoreCase("normal")) {

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

			Map<String, List<LocalDate>> parquetDatesMapList = new HashMap<>();
			Map<String, ArrowDataFrame> parquetFileValueMap = new HashMap<>();
			Map<String, ArrowStringDataFrame> parquetMapSet = new HashMap<>();

			for (Integer regimeIndex : priceLoaderPathMap.keySet()) {
				for (String keyFile : priceLoaderPathMap.get(regimeIndex).keySet()) {
					try {
						if (keyFile.equals("trading_dates") || keyFile.equals("all_dates")) {
							List<LocalDate> eachList = ParquetToMap.loadParquetToDateList(
									path(strategyRequest.getName(), priceLoaderPathMap.get(regimeIndex).get(keyFile),
											strategyRequest.getRegimes().get(0).getUniverse()));
							parquetDatesMapList.put(keyFile, eachList);
						} else if (keyFile.equals("universes")) {
							ArrowStringDataFrame universe = ArrowStringDataFrame.load(
									path(strategyRequest.getName(), priceLoaderPathMap.get(regimeIndex).get(keyFile),
											strategyRequest.getRegimes().get(0).getUniverse()));
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

							if (priceLoaderPathMap.get(regimeIndex).get(keyFile) == null
									|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isBlank()
									|| priceLoaderPathMap.get(regimeIndex).get(keyFile).isEmpty()
									|| priceLoaderPathMap.get(regimeIndex).get(keyFile).contains("null")) {
								continue;
							}

							ArrowDataFrame currentDf = ArrowDataFrame.load(
									path(strategyRequest.getName(), priceLoaderPathMap.get(regimeIndex).get(keyFile),
											strategyRequest.getRegimes().get(0).getUniverse()));
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
					parquetDatesMapList.get("all_dates"), daily_atr, null);
			priceData.setEndDate(strategyRequest.getEndDate());

			List<RuleDto> entryRuleConditions = strategyRequest.getRegimes().get(0).getEntryRules();
			List<RuleDto> exitRuleConditions = strategyRequest.getRegimes().get(0).getExitRules();
			String univ = strategyRequest.getRegimes().get(0).getUniverse();

			Map<String, ArrowDataFrame> entryMap = IndicatorRuleLoader.loadTables(entryRuleConditions,
					inputPath(strategyRequest.getName(), univ));
			Map<String, ArrowDataFrame> exitMap = IndicatorRuleLoader.loadTables(exitRuleConditions,
					inputPath(strategyRequest.getName(), univ));

			StrategyDataV2 strategyData = StrategyDataV2.builder().entryRulesList(entryRuleConditions)
					.exitRuleList(exitRuleConditions).entryIndicators(entryMap).exitIndicators(exitMap)
					.startingCapital(strategyRequest.getRegimes().get(0).getCapital())
					.slots(strategyRequest.getRegimes().get(0).getSlots())
					.stopLossPct(strategyRequest.getRegimes().get(0).getStoplossPct())
					.stoplossMaxPct(strategyRequest.getRegimes().get(0).getStoplossMaxPct()) // Patch 99
					.takeProfitPct(strategyRequest.getRegimes().get(0).getTakeprofitPct())
					.stoplossTiming(strategyRequest.getRegimes().get(0).getStoplossTiming())
					// Patch 72m.2: anchor passes through builder.
					.portfolioStoplossAnchor(strategyRequest.getRegimes().get(0).getPortfolioStoplossAnchor())
					.takeprofitTiming(strategyRequest.getRegimes().get(0).getTakeprofitTiming())
					.entryTiming(strategyRequest.getRegimes().get(0).getEntryTiming())
					.exitTiming(strategyRequest.getRegimes().get(0).getExitTiming())
					.ranking(parquetFileValueMap.get("ranking"))
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
					// LRA Patch 22a: propagate the 5 LONGSHORT regime fields.
					// Null for LONG / SHORT — additive, no behavioural impact.
					.tickerClassification(strategyRequest.getRegimes().get(0).getTickerClassification())
					.pairingEntryRules(strategyRequest.getRegimes().get(0).getPairingEntryRules())
					.pairingExitRules(strategyRequest.getRegimes().get(0).getPairingExitRules())
					.sizingPolicy(strategyRequest.getRegimes().get(0).getSizingPolicy())
					.pairExitPolicy(strategyRequest.getRegimes().get(0).getPairExitPolicy())
					// LRA Patch 25b: per-leg entry rule trees. Null for LONG / SHORT.
					.entryRulesTreeLong(strategyRequest.getRegimes().get(0).getEntryRulesTreeLong())
					.entryRulesTreeShort(strategyRequest.getRegimes().get(0).getEntryRulesTreeShort()).build();

			BuySellDataV2 buySellData = this.strategyBuilderServiceV2.generateSignals(strategyData, priceData);
			buySellData.setStrategyData(strategyData);

			// Max Single Same Slot
			buySellData.getStrategyData().setMaxSameTicker(1);

			BacktestReponseDto backtestResponse = backtestServiceV2.runBacktestV2(priceData, buySellData);

			IndicatorRuleLoader.closeTables(entryMap);
			IndicatorRuleLoader.closeTables(exitMap);

			// After backtest completes, close all open dataframes to free resources
			parquetFileValueMap.values().forEach(df -> {
				try {
					if (df != null)
						df.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
			parquetMapSet.values().forEach(df -> {
				try {
					if (df != null)
						df.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			});

//			long backtest_end = Duration.between(backtest_start, Instant.now()).toMillis();
//			System.err.println("Read PricesData : " + backtest_end);

//			long elapsed = Duration.between(start, Instant.now()).toMillis();
//			System.err.println(elapsed);
//
			// Setup Jackson ObjectMapper
			ObjectMapper mapper = new ObjectMapper();
			mapper.registerModule(new JavaTimeModule()); // Handles LocalDate, etc.
			mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // Use ISO-8601 dates
			mapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty print

			try {
				// Serialize to JSON files
				mapper.writeValue(Paths.get(
						String.format("%s/%s/%s/%s.json", backtestOPath, strategyRequest.getName(), "output", "Equity"))
						.toFile(), backtestResponse.getEquityLogger());

				mapper.writeValue(Paths.get(String.format("%s/%s/%s/%s.json", backtestOPath, strategyRequest.getName(),
						"output", "TradeList")).toFile(), backtestResponse.getTradeLogger());

			} catch (Exception e) {
				e.printStackTrace();
			}

			return this.portfolioService.getPortfolio();

		} else if (strategyRequest.getMarketRegimeType().equalsIgnoreCase("simple")) {

			Set<String> universes = strategyRequest.getRegimes().stream().map(MarketRegimeDto::getUniverse)
					.collect(Collectors.toSet());

			if (universes.size() == 1) {

				String universe = universes.iterator().next();
				// load core once
				PriceLoader coreLoader = PriceLoader.builder().universe(universe)
						.rebalance(strategyRequest.getRebalance())

						.build();

				// Load Prices and Universe for Shared one.
				Map<String, String> priceLoaderPathMap = coreLoader.getFilesForRebalance(strategyRequest.getRegimes());
				Map<String, ArrowStringDataFrame> parquetMapSet = new HashMap<>();
				Map<String, List<LocalDate>> parquetDatesMapList = new HashMap<>();
				Map<String, ArrowDataFrame> parquetFileValueMap = new HashMap<>();

				for (String keyFile : priceLoaderPathMap.keySet()) {
					try {
						if (keyFile.equals("trading_dates") || keyFile.equals("all_dates")) {
							List<LocalDate> eachList = ParquetToMap.loadParquetToDateList(
									path(strategyRequest.getName(), priceLoaderPathMap.get(keyFile), universe));
							parquetDatesMapList.put(keyFile, eachList);
						} else if (keyFile.equals("universes")) {
							ArrowStringDataFrame sharedUniverse;

							sharedUniverse = ArrowStringDataFrame
									.load(path(strategyRequest.getName(), priceLoaderPathMap.get(keyFile), universe));
							parquetMapSet.put(keyFile, sharedUniverse);

						} else if (priceLoaderPathMap.get(keyFile) != null && !keyFile.contains("ranking")
								&& !keyFile.contains("atr_")) {
							ArrowDataFrame currentDf = ArrowDataFrame
									.load(path(strategyRequest.getName(), priceLoaderPathMap.get(keyFile), universe));
							parquetFileValueMap.put(keyFile, currentDf);
						}

					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}

				}

				Map<String, ArrowDataFrame> marketTickerPrices = new HashMap<>();

				// Step 2: Add ticker-specific closes
				for (String ticker : coreLoader.getMarketTickers()) {
					String tickerKey = "closes_" + ticker;
					ArrowDataFrame tickerDf = parquetFileValueMap.get(tickerKey);
					if (tickerDf != null) {
						marketTickerPrices.put("closes_" + ticker, tickerDf);
					}
				}

				PriceDataV2 priceData = this.priceDataService.loadPricesMarketDatav2(parquetFileValueMap.get("closes"),
						parquetFileValueMap.get("opens"), parquetFileValueMap.get("highs"),
						parquetFileValueMap.get("lows"), parquetMapSet.get("universes"),
						parquetDatesMapList.get("trading_dates"), parquetDatesMapList.get("all_dates"), null,
						marketTickerPrices);
				priceData.setEndDate(strategyRequest.getEndDate());

				Map<LocalDate, String> marketTrend = new HashMap<>();

				Map<String, Set<Integer>> indicatorLookbackMap = new HashMap<>();
				// load overlays per regime
				Map<MarketRegimeDto, RegimeOverlay> overlays = new HashMap<>();

				Map<String, BuySellDataV2> rulesOfDayRegimes = new HashMap<>();

				for (MarketRegimeDto regime : strategyRequest.getRegimes()) {

					coreLoader.setUniverse(regime.getUniverse());
					coreLoader.setAtrLimitLookback(regime.getAtrLimitLookback());
					coreLoader.setAtrLookbackStp(regime.getAtrLookbackStp());
					coreLoader.setAtrLookbackTp(regime.getAtrLookbackTp());
					coreLoader.setRebalance(strategyRequest.getRebalance());
					coreLoader.setRankingLookback(regime.getRankingLookback());
					coreLoader.setRankingIndicator(regime.getRanking());

					PriceLoader regimeLoader = coreLoader;

					RegimeOverlay regimeOverlay = RegimeOverlay.builder().build();
					Map<String, String> rebalanceFiles = coreLoader.getFilesForRebalance(strategyRequest.getRegimes());
					for (String label : Arrays.asList("atr_limit", "atr_stp", "atr_tp", "ranking")) {

						ArrowDataFrame currentDf;
						try {
							if (rebalanceFiles.get(label) == null || rebalanceFiles.get(label).isBlank()
									|| rebalanceFiles.get(label).isEmpty()
									|| rebalanceFiles.get(label).contains("null")) {
								continue;
							}

							currentDf = ArrowDataFrame
									.load(path(strategyRequest.getName(), rebalanceFiles.get(label), universe));
							if (label.contains("atr")) {
								regimeOverlay.setDailyAtr(currentDf);
							} else {
								regimeOverlay.setRanking(currentDf);
							}
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}

					}

					// This is for ATR limit/STP/Takeprofit For now.
					overlays.put(regime, regimeOverlay);

					List<RuleDto> entryRuleConditions = regime.getEntryRules();
					List<RuleDto> exitRuleConditions = regime.getExitRules();
					List<RuleDto> marketTrendRuleConditions = regime.getMarketTrendRules();

					for (RuleDto ruleDto : entryRuleConditions) {

						if (indicatorLookbackMap.containsKey(ruleDto.getIndicator())) {
							indicatorLookbackMap.get(ruleDto.getIndicator()).add(ruleDto.getLookback());
						} else {
							Set<Integer> lookbackList = new HashSet<>();
							lookbackList.add(ruleDto.getLookback());
							indicatorLookbackMap.put(ruleDto.getIndicator(), lookbackList);
						}
					}

					for (RuleDto ruleDto : exitRuleConditions) {

						if (indicatorLookbackMap.containsKey(ruleDto.getIndicator())) {
							indicatorLookbackMap.get(ruleDto.getIndicator()).add(ruleDto.getLookback());
						} else {
							Set<Integer> lookbackList = new HashSet<>();
							lookbackList.add(ruleDto.getLookback());
							indicatorLookbackMap.put(ruleDto.getIndicator(), lookbackList);
						}
					}

					for (RuleDto ruleDto : marketTrendRuleConditions) {
						String key = String.format("%s_%s", regime.getRegimeTicker().toLowerCase(),
								ruleDto.getIndicator());
						if (indicatorLookbackMap.containsKey(key)) {
							indicatorLookbackMap.get(key).add(ruleDto.getLookback());
						} else {
							Set<Integer> lookbackList = new HashSet<>();
							lookbackList.add(ruleDto.getLookback());
							indicatorLookbackMap.put(key, lookbackList);
						}
					}

					Map<String, ArrowDataFrame> entryMap = IndicatorRuleLoader.loadTables(entryRuleConditions,
							inputPath(strategyRequest.getName(), universe), indicatorLookbackMap);
					Map<String, ArrowDataFrame> exitMap = IndicatorRuleLoader.loadTables(exitRuleConditions,
							inputPath(strategyRequest.getName(), universe), indicatorLookbackMap);

					// LRA Patch 35: auto-load indicators referenced by entry_rules_tree_long,
					// entry_rules_tree_short, and sizing_policy.params.conditional_on. Empty
					// for ROC regimes (no LRA fields populated) → zero overhead.
					Map<String, Set<Integer>> lraIndicators = IndicatorRuleLoader.collectLraIndicators(regime);
					if (!lraIndicators.isEmpty()) {
						Map<String, ArrowDataFrame> lraIndicatorMap = IndicatorRuleLoader.loadFromMap(lraIndicators,
								inputPath(strategyRequest.getName(), universe));
						entryMap.putAll(lraIndicatorMap);
					}

					StrategyDataV2 strategyData = StrategyDataV2.builder().entryRulesList(entryRuleConditions)
							.exitRuleList(exitRuleConditions).entryIndicators(entryMap).exitIndicators(exitMap)
							.startingCapital(regime.getCapital()).slots(regime.getSlots())
							.stopLossPct(regime.getStoplossPct()).stoplossMaxPct(regime.getStoplossMaxPct()) // Patch 99
							.takeProfitPct(regime.getTakeprofitPct())
							.stoplossTiming(regime.getStoplossTiming()).takeprofitTiming(regime.getTakeprofitTiming())
							// Patch 72m.3: anchor passes through builder.
							.portfolioStoplossAnchor(regime.getPortfolioStoplossAnchor())
							.entryTiming(regime.getEntryTiming()).exitTiming(regime.getExitTiming())
							.ranking(overlays.get(regime).getRanking()).rankingOrder(regime.getRankingOrder())
							.startDate(strategyRequest.getStartDate()).endDate(strategyRequest.getEndDate())
							.minPrice(strategyRequest.getMinPrice()).minQuantity(strategyRequest.getMinQuantity())
							.stoplossType(regime.getStoplossType()).takeprofitType(regime.getTakeprofitType())
							.systemType(strategyRequest.getSystemType()).orderType(regime.getOrderType())
							.atrLimitLookback(regime.getAtrLimitLookback()).limitPct(regime.getLimitPct())
							.maxTime(regime.getMaxTime()).bannedMonths(regime.getBannedMonths())
							// LRA Patch 22a: propagate the 5 LONGSHORT regime fields. Null-safe.
							.tickerClassification(regime.getTickerClassification())
							.pairingEntryRules(regime.getPairingEntryRules())
							.pairingExitRules(regime.getPairingExitRules()).sizingPolicy(regime.getSizingPolicy())
							.pairExitPolicy(regime.getPairExitPolicy())
							// LRA Patch 25b: per-leg entry rule trees. Null for LONG / SHORT.
							.entryRulesTreeLong(regime.getEntryRulesTreeLong())
							.entryRulesTreeShort(regime.getEntryRulesTreeShort()).build();

					// Collecting All the Labels of the Market Trend and make it as key.

					StringBuilder marketRuleBuilder = new StringBuilder();
					for (RuleDto ruleDto : marketTrendRuleConditions) {
						marketRuleBuilder.append(ruleDto.getLabel()).append("_");
					}
					String marketRuleOfDay = marketRuleBuilder.toString();

					// Optional: If you want to remove the trailing underscore:
					if (marketRuleOfDay.length() > 0) {
						marketRuleOfDay = marketRuleOfDay.substring(0, marketRuleOfDay.length() - 1);
					}

					BuySellDataV2 buySellData = this.strategyBuilderServiceV2.generateSignals(strategyData, priceData);
					// Max Single Same Slot
					buySellData.getStrategyData().setMaxSameTicker(1);

					rulesOfDayRegimes.put(marketRuleOfDay, buySellData);

				}

				// Pricedata has the OHLC,
				// regimeOverlays has the ATR(Limit/Stp/TP) and Ranking (For each BULL and BEAR)

				// regimeOfDay Contains the Buy Sell Data of the data.

				Map<String, ArrowDataFrame> marketTrendMap = IndicatorRuleLoader.loadTablesMarketTrend(
						strategyRequest.getRegimes(), inputPath(strategyRequest.getName(), universe),
						indicatorLookbackMap);

				Map<LocalDate, String> marketTrends = this.marketTrendServiceV2
						.generateMarketTrend(strategyRequest.getRegimes(), marketTrendMap, priceData);

				long start = System.nanoTime();

				BacktestReponseDto backtestResponse = this.backtestServiceV2.runBacktestSimpleV2(priceData,
						marketTrends, rulesOfDayRegimes);

				long end = System.nanoTime();

				double elapsedSeconds = (end - start) / 1_000_000_000.0;

				System.err.println();

				for (String regime : rulesOfDayRegimes.keySet()) {
					BuySellDataV2 s = rulesOfDayRegimes.get(regime);

					IndicatorRuleLoader.closeTables(s.getStrategyData().getEntryIndicators());
					IndicatorRuleLoader.closeTables(s.getStrategyData().getExitIndicators());

				}

				// After backtest completes, close all open dataframes to free resources
				parquetFileValueMap.values().forEach(df -> {
					try {
						if (df != null)
							df.close();
					} catch (Exception e) {
						e.printStackTrace();
					}
				});
				parquetMapSet.values().forEach(df -> {
					try {
						if (df != null)
							df.close();
					} catch (Exception e) {
						e.printStackTrace();
					}
				});

//				long backtest_end = Duration.between(backtest_start, Instant.now()).toMillis();
//				System.err.println("Read PricesData : " + backtest_end);

//				long elapsed = Duration.between(start, Instant.now()).toMillis();
//				System.err.println(elapsed);
				//
				// Setup Jackson ObjectMapper
				ObjectMapper mapper = new ObjectMapper();
				mapper.registerModule(new JavaTimeModule()); // Handles LocalDate, etc.
				mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // Use ISO-8601 dates
				mapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty print

				try {
					// Serialize to JSON files
					mapper.writeValue(Paths.get(String.format("%s/%s/%s/%s.json", backtestOPath,
							strategyRequest.getName(), "output", "Equity")).toFile(),
							backtestResponse.getEquityLogger());

					mapper.writeValue(Paths.get(String.format("%s/%s/%s/%s.json", backtestOPath,
							strategyRequest.getName(), "output", "TradeList")).toFile(),
							backtestResponse.getTradeLogger());

				} catch (Exception e) {
					e.printStackTrace();
				}
				System.out.printf("⏱️ Backtest execution time: %.3f seconds%n", elapsedSeconds);
				return this.portfolioService.getPortfolio();

			} else {
				// Price Data is going to be unique.

			}

		}

		return null;
	}

	@PostMapping("api/runbacktestv3")
	public BacktestReponseDto runBacktestV3(@RequestBody StrategyBucketRequestDto strategyRequest) {

		try (BacktestContext context = this.backtestContextFactory.create(this.priceDataService,
				this.strategyBuilderServiceV2, this.marketTrendServiceV2)) {
			java.time.Instant T0 = java.time.Instant.now();
			System.err.println("⏱ ===== T0 Request received: " + strategyRequest.getName() + " =====");

			if (strategyRequest.getMarketRegimeType().equalsIgnoreCase("normal")) {
				PriceDataV2 priceData = context.getPriceData(strategyRequest, backtestDataPath);

				java.time.Instant T1 = java.time.Instant.now();
				System.err.printf("⏱ T1 getPriceData done | phase=%.3fs | cumul=%.3fs%n",
						java.time.Duration.between(T0, T1).toMillis() / 1000.0,
						java.time.Duration.between(T0, T1).toMillis() / 1000.0);

				BuySellDataV2 buySellData = context.getBuySellData(strategyRequest, priceData, backtestDataPath);
				java.time.Instant T2 = java.time.Instant.now();
				System.err.printf("⏱ T2 getBuySellData done | phase=%.3fs | cumul=%.3fs%n",
						java.time.Duration.between(T1, T2).toMillis() / 1000.0,
						java.time.Duration.between(T0, T2).toMillis() / 1000.0);

				buySellData.getStrategyData().setMaxSameTicker(1);

				BacktestReponseDto backtestResponse = backtestServiceV2.runBacktestV2(priceData, buySellData);
//				BacktestReponseDto backtestResponse = null;
				java.time.Instant T3 = java.time.Instant.now();
				System.err.printf("⏱ T3 runBacktestV2 (date loop) done | phase=%.3fs | cumul=%.3fs%n",
						java.time.Duration.between(T2, T3).toMillis() / 1000.0,
						java.time.Duration.between(T0, T3).toMillis() / 1000.0);

				context.writeBacktestResponse(strategyRequest, backtestResponse, backtestOPath);

				java.time.Instant T4 = java.time.Instant.now();
				System.err.printf("⏱ T4 writeBacktestResponse done | phase=%.3fs | cumul=%.3fs%n",
						java.time.Duration.between(T3, T4).toMillis() / 1000.0,
						java.time.Duration.between(T0, T4).toMillis() / 1000.0);
				System.err.println(
						"⏱ ===== TOTAL = " + (java.time.Duration.between(T0, T4).toMillis() / 1000.0) + "s =====");

				return this.portfolioService.getPortfolio();

			} else if (strategyRequest.getMarketRegimeType().equalsIgnoreCase("simple")) {

				// 1. Load shared prices + market ticker closes
				PriceDataV2 priceData = context.getSimplePriceData(strategyRequest, backtestDataPath);

				// 2. Per-regime: load indicators, build leaf cache, generate signals via tree
				// evaluation
				Map<String, BuySellDataV2> regimeSignals = context.getSimpleBuySellDataMap(strategyRequest, priceData,
						backtestDataPath);

				// 3. Evaluate market trend rules → date → active regime label
				Map<LocalDate, String> marketTrends = context.getMarketTrends(strategyRequest, priceData,
						backtestDataPath);

				// 4. Run multi-regime backtest with daily regime switching
				BacktestReponseDto backtestResponse = backtestServiceV2.runBacktestSimpleV2(priceData, marketTrends,
						regimeSignals);

				// 5. Write output
				context.writeBacktestResponse(strategyRequest, backtestResponse, backtestOPath);

				return backtestResponse;
			}

		} catch (Exception e) {

			throw new BacktestExecutionException("Failed to run backtest", e);
		}

		return null;
	}

	// ─── Patch 11: execution-mode endpoint ───────────────────────────────────
	// Generates PROPOSED orders for D+1 using:
	// - tradelist's LIVE rows (passed in liveHoldings) → seedLiveHoldings
	// - parquet data through req.strategy.end_date (= D)
	// - skip-last-bar guard captures D+1 orders into response.proposedOrders
	//
	// LONGSHORT not supported here — Phase 2 LRA will get its own endpoint.
	// Only market_regime_type='simple' supported in Phase 1 (the ROC family
	// path). market_regime_type='normal' rejected for now — single-regime
	// legacy strategies aren't on the execution roadmap.
	@PostMapping("api/execution/step/single")
	public ResponseEntity<?> executionStepSingle(@RequestBody ExecutionStepRequestDto req) {
		if (req == null || req.getStrategy() == null) {
			return ResponseEntity.badRequest().body("strategy is required");
		}
		StrategyBucketRequestDto strategy = req.getStrategy();
		if (strategy.getSystemType() != null && strategy.getSystemType().equalsIgnoreCase("LONGSHORT")) {
			return ResponseEntity.badRequest()
					.body("LONGSHORT system_type not supported on /api/execution/step/single. "
							+ "Pair execution will get its own endpoint in Phase 2 LRA.");
		}
		// Patch 17: removed the market_regime_type='simple' restriction.
		// runBacktestSimpleV2 handles both 'simple' (multi-regime) and 'Normal'
		// (single-regime) — the original Phase B restriction was overcautious.
		// LONGSHORT is still rejected above; that's the only restriction
		// execution mode needs in Phase 1.

		try (BacktestContext context = this.backtestContextFactory.create(this.priceDataService,
				this.strategyBuilderServiceV2, this.marketTrendServiceV2)) {
			context.setExecutionDataRoot(req.getDataRoot());
			// Mirror runbacktestv3's simple branch — same loaders, same path scheme.
			PriceDataV2 priceData = context.getSimplePriceData(strategy, backtestDataPath);
			Map<String, BuySellDataV2> regimeSignals = context.getSimpleBuySellDataMap(strategy, priceData,
					backtestDataPath);

			// Patch 19: marketTrends synthesis for single-regime "Normal" strategies.
			// runbacktestv3 branches Normal → runBacktestV2 (no trend map) vs simple →
			// runBacktestSimpleV2 (per-date label). The execution endpoint takes only
			// the simple path; if we call getMarketTrends() for a Normal strategy
			// whose market_trend_rules_tree is empty, the result is an empty map and
			// every date in the day-loop fails the regime-lookup → zero signals.
			//
			// Fix: when marketRegimeType="Normal", fabricate a one-label map. The
			// simple branch already has all the proposed-orders capture logic
			// instrumented (Patches 11+16) — this lets a Normal strategy flow
			// through that instrumented path without needing to mirror ~140 lines
			// of capture logic into runBacktestV2. Multi-regime "simple" strategies
			// continue to use getMarketTrends() as before.
			Map<LocalDate, String> marketTrends;
			if ("normal".equalsIgnoreCase(strategy.getMarketRegimeType())) {
				if (regimeSignals.isEmpty()) {
					throw new IllegalStateException(
							"Normal strategy " + strategy.getName() + " produced no regime signals");
				}
				String soleLabel = regimeSignals.keySet().iterator().next();
				marketTrends = new java.util.HashMap<>();
				for (LocalDate d : priceData.getAll_dates()) {
					marketTrends.put(d, soleLabel);
				}
				System.err.println("[execution] Normal strategy → synthesized " + marketTrends.size()
						+ " marketTrend entries → label=" + soleLabel);
			} else {
				marketTrends = context.getMarketTrends(strategy, priceData, backtestDataPath);
			}

			// 4-arg overload with seedHoldings — engine attaches proposedOrders
			// internally before returning. No call to writeBacktestResponse here
			// since execution mode doesn't persist TradeList.json on disk.
			BacktestReponseDto response = backtestServiceV2.runBacktestSimpleV2(priceData, marketTrends, regimeSignals,
					req.getLiveHoldings());

			return ResponseEntity.ok(response);

		} catch (Exception e) {
			throw new BacktestExecutionException("Failed to run execution step", e);
		}
	}

	// ─── Patch 31: single-bar execution endpoint (Phase B) ──────────────────
	//
	// Stateless single-bar signal evaluation. Replaces the day-loop endpoint
	// /api/execution/step/single for nightly execution once middleware cuts
	// over. The day-loop endpoint above stays alive in parallel during the
	// cutover window — RT decommissions it after parity testing.
	//
	// Differences from /api/execution/step/single:
	// - No 2023→today re-simulation. Evaluates ONLY the last bar.
	// - No tradeLogger / equityLogger / fillOutcomes in the response.
	// - Response shape: { proposedEntries, substitutePool, proposedExits,
	// stopUpdates, activeRegimeOnLastBar, dataDate, runDate }.
	// - ~100-200ms per strategy vs ~30s in the day-loop.
	//
	// LONGSHORT rejected — Phase 2 LRA pairs will get their own endpoint.
	@PostMapping("api/execution/signals/last-bar")
	public ResponseEntity<?> signalsLastBar(@RequestBody ExecutionStepRequestDto req) {
		if (req == null || req.getStrategy() == null) {
			return ResponseEntity.badRequest().body("strategy is required");
		}
		StrategyBucketRequestDto strategy = req.getStrategy();
		if (strategy.getSystemType() != null && strategy.getSystemType().equalsIgnoreCase("LONGSHORT")) {
			return ResponseEntity.badRequest()
					.body("LONGSHORT system_type not supported on /api/execution/signals/last-bar. "
							+ "Pair execution will get its own endpoint in Phase 2 LRA.");
		}
		try {
			SingleBarSignalsResponseDto response = singleBarEvaluator.evaluate(req);
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			throw new BacktestExecutionException("Failed to evaluate single-bar signals", e);
		}
	}
}