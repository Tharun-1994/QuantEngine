package com.backtest.engine.dto.request;



import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.backtest.engine.dto.request.VolFilterDto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MarketRegimeDto {

    private int id;

    @JsonProperty("strategy_id")
    private int strategyId;

    @JsonProperty("regime_type")
    private String regimeType;   // Normal | Simple | Complex

    @JsonProperty("regime_ticker")
    private String regimeTicker;

    @JsonProperty("market_trend_type")
    private String marketTrendType;

    @JsonProperty("market_trend_rules")
    private List<RuleDto> marketTrendRules;

    @JsonProperty("volatility_rules")
    private List<RuleDto> volatilityRules;

    @JsonProperty("entry_rules")
    private List<RuleDto> entryRules;

    @JsonProperty("exit_rules")
    private List<RuleDto> exitRules;

    @JsonProperty("entry_timing")
    private String entryTiming;

    @JsonProperty("exit_timing")
    private String exitTiming;

    @JsonProperty("stoploss_type")
    private String stoplossType;

    @JsonProperty("takeprofit_type")
    private String takeprofitType;

    @JsonProperty("stoploss_pct")
    private float stoplossPct;

    // Patch 99: cap on ATR stop offset as % of anchor price. 0/null = disabled.
    @JsonProperty("stoploss_max_pct")
    private float stoplossMaxPct;

    @JsonProperty("takeprofit_pct")
    private float takeprofitPct;

    @JsonProperty("stoploss_timing")
    private String stoplossTiming;

    @JsonProperty("takeprofit_timing")
    private String takeprofitTiming;

    // Patch 72k: PORTFOLIO drawdown anchor. PEAK or DAILY. Null when
    // stoploss_type != PORTFOLIO. Validated at the Python save layer.
    @JsonProperty("portfolio_stoploss_anchor")
    private String portfolioStoplossAnchor;
    
    @JsonProperty("atr_lookback_stp")
    private int atrLookbackStp;

    @JsonProperty("atr_lookback_tp")
    private int atrLookbackTp;

    private String ranking;

    @JsonProperty("ranking_lookback")
    private int rankingLookback;

    @JsonProperty("ranking_order")
    private String rankingOrder;

    @JsonProperty("order_type")
    private String orderType;

    @JsonProperty("limit_pct")
    private float limitPct;

    @JsonProperty("atr_limit_lookback")
    private int atrLimitLookback;
    // Patch 167 v2: mode-specific limit parameters (mirrors middleware's
    // limit_params_json). LIMIT_HV keys: hv_lookback, divider, lower,
    // upper, reduction. Future LIMIT_* modes reuse this map -- no new fields.
    @JsonProperty("limit_params")
    private java.util.Map<String, Float> limitParams;
    
    private String universe;
    private float capital;
    private int slots;
    private String rebalance;
    
    @JsonProperty("production_capital")
    private Float productionCapital;   // Patch 50: nullable — null means not set

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
    
    @JsonProperty("max_time")
    private int maxTime;    
    
    @JsonProperty("banned_months")
    private List<Integer> bannedMonths;
    
 // --- New Rule Tree Integration (Dict[str, Any] equivalent) ---
    @JsonProperty("market_trend_rules_tree")
    private Map<String, Object> marketTrendRulesTree;

    @JsonProperty("volatility_rules_tree")
    private Map<String, Object> volatilityRulesTree;

    @JsonProperty("entry_rules_tree")
    private Map<String, Object> entryRulesTree;

    @JsonProperty("exit_rules_tree")
    private Map<String, Object> exitRulesTree;

    @JsonProperty("freeze_rules_tree")
    private Map<String, Object> freezeRulesTree;

    @JsonProperty("resume_rules_tree")
    private Map<String, Object> resumeRulesTree;
    
    @JsonProperty("freeze_timing")
    private String freezeTiming;

    /** "open" (default) — resume check uses previousDate. "close" — uses today. */
    @JsonProperty("resume_timing")
    private String resumeTiming;
    
    /**
     * Volatility safety net type for this regime.
     *   "none"           — no safety net (default)
     *   "simple"         — stateless freeze/resume rule trees (current behaviour)
     *   "spy_volatility" — stateful 4-escape state machine (Stage 3 — not yet wired)
     */
    @JsonProperty("safety_net_type")
    private String safetyNetType;
    
    /**
     * List-based safety-net contract (Stage 3a). Each item is a stateful
     * policy with its own {@code params} blob. The engine iterates the list
     * each day; any item saying "freeze" stops trading. Null/empty means
     * "no safety nets configured".
     *
     * <p>Plumbed but inert in Stage 3a — engine reads only {@link #safetyNetType}
     * for behaviour. Stage 3b adds the policy registry and switches dispatch
     * to this list.</p>
     */
    @JsonProperty("safety_nets")
    private java.util.List<SafetyNetItemDto> safetyNets;
    
    @JsonProperty("sector_level")
    private int sectorLevel;
 
    @JsonProperty("sector_limit")
    private int sectorLimit;
    
    @JsonProperty("gap_filter_pct")
    private float gapFilterPct;
    
    @JsonProperty("max_duplicates")
    private int maxDuplicates;

    @JsonProperty("max_duplicate_sets")
    private int maxDuplicateSets;


    @JsonProperty("tdom_filters")
    private List<TdomFilterDto> tdomFilters;

    /**
     * Optional vol/turnover filter config.
     * When null or !enabled the engine skips vol/turnover threshold logic.
     */
    @JsonProperty("vol_filter")
    private VolFilterDto volFilter;
    /**
     * If true, all open positions belonging to THIS regime are force-closed at
     * next open when the market trend shifts away from this regime.
     * Default false (matches Python: positions exit normally via signals/stop).
     */
    @JsonProperty("close_positions_on_regime_exit")
    private boolean closePositionsOnRegimeExit;

    // LRA Patch 22a: 5 new fields for LONGSHORT (pair-trading) regimes.
    // For LONG / SHORT strategies the middleware doesn't send these, so
    // Jackson leaves them null. The engine only reads them on the LONGSHORT
    // dispatch arm (Patch 22b); existing code paths never touch them.

    /** Per-ticker static metadata: {symbol -> {risk:..., range_tier:..., ...}} */
    @JsonProperty("ticker_classification")
    private Map<String, Object> tickerClassification;

    /** disallowed_combos + backtracking config — consumed by PairingService */
    @JsonProperty("pairing_entry_rules")
    private Map<String, Object> pairingEntryRules;

    /** Reserved for future pair-level exit rule trees. Empty for LRA. */
    @JsonProperty("pairing_exit_rules")
    private Map<String, Object> pairingExitRules;

    /** VIX bands + per-leg cap assignment — consumed by SizingPolicyResolver */
    @JsonProperty("sizing_policy")
    private Map<String, Object> sizingPolicy;

    /** max_hold_sessions + force_close + profit_exit — consumed by exit processors */
    @JsonProperty("pair_exit_policy")
    private Map<String, Object> pairExitPolicy;
    // LRA Patch 25b: per-leg entry rule trees for LONGSHORT strategies.
    // Each leg has its own tree (e.g. LRA bull regime: long side = IBS bottom-N
    // + daily_range_pct + RSI carve-out; short side = IBS top-N + daily_range_pct
    // + RSI > 50). For LONG / SHORT strategies, both stay null and the existing
    // entryRulesTree is used instead.

    /** Rule tree producing long-side entry candidates. Null on LONG / SHORT strategies. */
    @JsonProperty("entry_rules_tree_long")
    private Map<String, Object> entryRulesTreeLong;

    /** Rule tree producing short-side entry candidates. Null on LONG / SHORT strategies. */
    @JsonProperty("entry_rules_tree_short")
    private Map<String, Object> entryRulesTreeShort;
}