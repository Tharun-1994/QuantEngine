package com.backtest.engine.dto.request;

import java.time.LocalDate;
import java.util.List;
import lombok.Data;

@Data
public class ExecutionStepRequestDto {
    private StrategyBucketRequestDto strategy;
    private LocalDate runDate;
    private List<LiveHoldingsSeedDto> liveHoldings;

    // Patch 14: middleware-supplied path to today's exec_data folder.
    // Expected layout: <DATA_ROOT>/exec_data/{YYYYMMDD}, written by the
    // C1 exec_data_refresh service. When set, BacktestContext.inputPath
    // resolves parquet files as {dataRoot}/{universe}/Filename.parquet
    // (no {strategy_name}/input/ segments — exec_data is universe-shared).
    // Null/empty → engine falls back to the legacy backtest_data layout,
    // so backtest paths are unaffected.
    private String dataRoot;
}