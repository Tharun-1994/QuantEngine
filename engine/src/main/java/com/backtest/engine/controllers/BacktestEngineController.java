package com.backtest.engine.controllers;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtest.engine.dto.request.StrategyRequestDto;
import com.backtest.engine.dto.response.BacktestReponseDto;
import com.backtest.engine.entity.BuySellData;
import com.backtest.engine.entity.PriceData;
import com.backtest.engine.entity.RuleCondition;
import com.backtest.engine.entity.StrategyData;
import com.backtest.engine.entity.TickerValue;
import com.backtest.engine.service.BacktestService;
import com.backtest.engine.service.PortfolioService;
import com.backtest.engine.service.PriceDataLoaderService;
import com.backtest.engine.service.StrategyBuilderService;
import com.backtest.engine.util.ParquetToMap;
import com.backtest.engine.util.PriceLoader;
import com.backtest.engine.util.RuleParser;
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
	private final PriceDataLoaderService priceDataService;
	private final StrategyBuilderService strategyBuilderService;
	private final PortfolioService portfolioService;

	public BacktestEngineController(BacktestService backtestService, PriceDataLoaderService priceDataService,
			StrategyBuilderService strategyBuilderService, PortfolioService portfolioService) {
		this.backtestService = backtestService;
		this.priceDataService = priceDataService;
		this.strategyBuilderService = strategyBuilderService;
		this.portfolioService = portfolioService;
	}

	public Map<String, Map<LocalDate, Map<String, Double>>> loadTables(List<RuleCondition> conditions, String universe, String dataDir) {

		Map<String, Map<LocalDate, Map<String, Double>>> tableMap = new HashMap<>();
		for (RuleCondition rc : conditions) {
			// build filename: e.g. "rsi_14_sp500.parquet"
			String fileName = RuleParser.buildParquetFileName(rc.getIndicator(), rc.getIndicatorLookBack());
			Path parquetPath = Paths.get(dataDir, fileName);
			Map<LocalDate, Map<String, Double>> indicatorMap;
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

	public String path(String fileName) {
		return Paths.get(backtestDataPath, fileName).toString();
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

		Map<LocalDate, Map<String, Double>> ranking = Collections.emptyMap();
		Instant start_prices_time = Instant.now();

		Map<String, String> files = PriceLoader.getFilesForRebalance(strategyRequest.getRebalance(),
				strategyRequest.getUniverse(), strategyRequest.getRanking(), strategyRequest.getRankingLookback());

		Map<String, Map<LocalDate, Map<String, Double>>> parquetFileValueMap = new HashMap<>();
		Map<String, List<LocalDate>> parquetDatesMapList = new HashMap<>();
		Map<String, Map<LocalDate, Set<String>>> parquetMapSet = new HashMap<>();

		for (String keyFile : files.keySet()) {

			String key_path = path(files.get(keyFile));
			try {
				if (keyFile.equals("trading_dates") || keyFile.equals("all_dates")) {

					List<LocalDate> eachList = ParquetToMap.loadParquetToDateList(path(files.get(keyFile)));
					parquetDatesMapList.put(keyFile, eachList);

				} else if (keyFile.equals("universes")) {
					Map<LocalDate, Set<String>> universe = ParquetToMap.loadParquetToMapList(path(files.get(keyFile)));

					parquetMapSet.put(keyFile, universe);
				} else {

					Map<LocalDate, Map<String, Double>> parquetIter = ParquetToMap
							.loadParquetToMap(path(files.get(keyFile)));
					parquetFileValueMap.put(keyFile, parquetIter);

				}
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}


		PriceData priceData = this.priceDataService.loadPricesMarketData(parquetFileValueMap.get("closes"),
				parquetFileValueMap.get("opens"), parquetFileValueMap.get("highs"), parquetFileValueMap.get("lows"),
				parquetMapSet.get("universes"), parquetDatesMapList.get("trading_dates"), parquetDatesMapList.get("all_dates"));
		
		

		long end_prices_time = Duration.between(start_prices_time, Instant.now()).toMillis();
		System.err.println("Read Prices Timing : " + end_prices_time);

//		-------------------------------------------------------------------------Logic Of backtesting------------------------

		Instant priceDate_time = Instant.now();

		long end_pricedata = Duration.between(priceDate_time, Instant.now()).toMillis();
		System.err.println("Read PricesData : " + end_pricedata);

		List<RuleCondition> entryRuleConditions = RuleParser.parseEntryRules(strategyRequest.getEntryRules());
		List<RuleCondition> exitRuleConditions = RuleParser.parseEntryRules(strategyRequest.getExitRules());

		Instant indicator_load_start_time = Instant.now();

		Map<String, Map<LocalDate, Map<String, Double>>> entryMap = loadTables(entryRuleConditions, strategyRequest.getUniverse(), backtestDataPath);

		Map<String, Map<LocalDate, Map<String, Double>>> exitMap = loadTables(exitRuleConditions, strategyRequest.getUniverse(), backtestDataPath);

		StrategyData strategyData = StrategyData.builder().entryRulesList(entryRuleConditions)
				.exitRuleList(exitRuleConditions).entryIndicators(entryMap).exitIndicators(exitMap)
				.startingCapital(strategyRequest.getCapital()).slots(strategyRequest.getSlots())
				.stopLossPct(strategyRequest.getStoplossPct()).takeProfitPct(strategyRequest.getTakeprofitPct())
				.stoplossTiming(strategyRequest.getStoplossTiming())
				.takeprofitTiming(strategyRequest.getTakeprofitTiming()).entryTiming(strategyRequest.getEntryTiming())
				.exitTiming(strategyRequest.getExitTiming()).ranking(parquetFileValueMap.get("ranking")).rankingOrder(strategyRequest.getRankingOrder()).startDate(strategyRequest.getStartDate()).endDate(strategyRequest.getEndDate()).build();

		long indicator_load_end_time = Duration.between(indicator_load_start_time, Instant.now()).toMillis();
		System.err.println("Read Indicator : " + indicator_load_end_time);

		BuySellData buySellData = this.strategyBuilderService.generateSignals(strategyData);
		buySellData.setStrategyData(strategyData);
		
//		Max Single Same Slot
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
//			// Serialize to JSON files
			mapper.writeValue(Paths.get(String.format("%s%s.json", backtestOPath, "/Equity")).toFile(),
					this.portfolioService.getPortfolio().getEquityLogger());

			mapper.writeValue(Paths.get(String.format("%s%s.json", backtestOPath, "/TradeList")).toFile(),
					this.portfolioService.getPortfolio().getTradeLogger());
//			writeToCsvWithTablesaw(backtestOPath);

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
}
