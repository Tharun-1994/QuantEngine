package com.backtest.engine.entity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.TdomFilterDto;
import com.backtest.engine.dto.request.VolFilterDto;
import com.backtest.engine.ruleBuilder.LeafCacheResult;
import com.backtest.engine.util.ArrowDataFrame;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class StrategyDataV2 {

	private List<RuleDto> entryRulesList;
	private List<RuleDto> exitRuleList;
	private Map<String, ArrowDataFrame> entryIndicators;
	private Map<String, ArrowDataFrame> exitIndicators;
	private float stopLossPct;
	private float takeProfitPct;
	private int maxSameTicker;
	private float startingCapital;
	private int slots;
	private LocalDate startDate;
	private LocalDate endDate;
	private String stoplossTiming;

	private String takeprofitTiming;

	private String entryTiming;

	private String exitTiming;

	private ArrowDataFrame ranking;
	private String rankingOrder;
	private float minQuantity;
	private float minPrice;
	private String stoplossType;
	private String takeprofitType;
	private String systemType;

	private String orderType;
	private float limitPct;
	private int atrLimitLookback;
	
	private int maxTime;
	
	private Map<String,String> paths;
	
	private List<Integer> bannedMonths;
	
    // ---------------- NEW: rule trees + caches ----------------
    private RuleGroupNodeDto entryRulesTree;
    private RuleGroupNodeDto exitRulesTree;
    
 // leafId -> rule
    private Map<String, RuleDto> entryLeafRulesById;
    private Map<String, RuleDto> exitLeafRulesById;


    // leafId -> (date -> eligible tickers)
    private Map<String, Map<LocalDate, Set<String>>> entryLeafCache;
    private Map<String, Map<LocalDate, Set<String>>> exitLeafCache;
    
    private LeafCacheResult entryLeafCacheResult;
    private LeafCacheResult exitLeafCacheResult;

	private Map<String, String> sectorMap;  // ticker → sector/industry name
	private int sectorLevel;
	private int sectorLimit;
	
	private float gapFilterPct;
	
	private int maxDuplicates;
	private int maxDuplicateSets;

	/**
	 * Dynamic TDOM calendar filters — passed through from MarketRegimeDto.
	 * Evaluated per-day in the backtest loop before any limit orders are placed.
	 */
	private List<TdomFilterDto> tdomFilters;

	// ── Vol/Turnover filter ──────────────────────────────────────────
	/** Config from request. Null or !enabled → filter skipped. */
	private VolFilterDto volFilter;

	/** avg_volume parquet: rolling(200).mean(turnovers/unadj_closes). Date×Ticker. */
	private ArrowDataFrame avgVolume;

	/** avg_turnover parquet: rolling(200).mean(closes*volumes). Date×Ticker. */
	private ArrowDataFrame avgTurnover;

	/** SPY close prices for SMA(200) bull/bear detection. Date×1col. */
	private ArrowDataFrame spyCloses;

	/**
	 * Recalculated once per year (first Jan trading day).
	 * Starts at 0 (pass all) until first recalculation fires.
	 * Set via setVolThreshold() in BacktestServiceImplV2.
	 */
	@Builder.Default private volatile float volThreshold = 0f;

	/**
	 * Recalculated once per year (first Jan trading day).
	 * Starts at 0 (pass all) until first recalculation fires.
	 */
	@Builder.Default private volatile float turnoverThreshold = 0f;
}