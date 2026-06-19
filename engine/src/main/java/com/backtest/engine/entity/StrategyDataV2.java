package com.backtest.engine.entity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.SafetyNetItemDto;
import com.backtest.engine.dto.request.TdomFilterDto;
import com.backtest.engine.dto.request.VolFilterDto;
import com.backtest.engine.ruleBuilder.LeafCacheResult;
import com.backtest.engine.service.safetynet.SafetyNetPolicy;
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
    private Float productionCapital;   // Patch 50: per-regime live execution sizing. Null for backtest.
    
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
	// Patch 72l: PORTFOLIO drawdown anchor — "PEAK" (default) or "DAILY".
	// Read by PortfolioServiceImplV2.checkPortfolioStoplossHit via a setter
	// on PortfolioServiceV2; null/empty defaults to PEAK at runtime.
	private String portfolioStoplossAnchor;
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
    private RuleGroupNodeDto freezeRulesTree;
    private RuleGroupNodeDto resumeRulesTree;
    private java.util.Set<java.time.LocalDate> freezeDays;
    private java.util.Set<java.time.LocalDate> resumeDays;
    private String freezeTiming;
    private String resumeTiming;
    /** Volatility safety net type — "none" | "simple" | "spy_volatility".
     *  Plumbed through but not yet consumed by the day-loop (Stage 3). */
    private String safetyNetType;
    /** List of stateful safety-net policies (Stage 3a contract).
     *  The raw DTOs from the request. Engine still references this for
     *  back-compat / introspection; runtime dispatch uses {@link #safetyPolicies}. */
    private java.util.List<SafetyNetItemDto> safetyNets;

    /** Initialised SafetyNetPolicy instances for this regime (Stage 3b).
     *  Built by BacktestContext via SafetyNetRegistry from {@link #safetyNets}.
     *  Empty list means no active policies — strategy trades freely.
     *  The day-loop iterates this list each day to gather freeze/resume decisions. */
    private java.util.List<SafetyNetPolicy> safetyPolicies;
    
    
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
	 * Per-regime: when this regime ends (market trend shifts away), force-close
	 * its open positions at next open. Default false (Python-compatible).
	 */
	private boolean closePositionsOnRegimeExit;

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

	// LRA Patch 22a: in-engine carriers for the 5 LONGSHORT regime fields.
	// Populated from MarketRegimeDto by the controller. Read only on the
	// LONGSHORT dispatch arm (Patch 22b); null for every LONG / SHORT strategy.

	/** Per-ticker static metadata, deserialised JSON. See MarketRegimeDto for shape. */
	private Map<String, Object> tickerClassification;

	/** disallowed_combos + backtracking — passed to PairingService. */
	private Map<String, Object> pairingEntryRules;

	/** Reserved for future pair-level exit rule trees. Empty for LRA. */
	private Map<String, Object> pairingExitRules;

	/** VIX bands + per-leg cap assignment — passed to SizingPolicyResolver. */
	private Map<String, Object> sizingPolicy;

	/** max_hold_sessions + force_close + profit_exit — passed to exit processors. */
	private Map<String, Object> pairExitPolicy;
	
	// LRA Patch 25b: per-leg entry rule trees. Null for LONG / SHORT strategies.

	/** Rule tree producing long-side entry candidates on the LONGSHORT path. */
	private Map<String, Object> entryRulesTreeLong;

	/** Rule tree producing short-side entry candidates on the LONGSHORT path. */
	private Map<String, Object> entryRulesTreeShort;

}