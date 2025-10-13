package com.backtest.engine.dto.request;



import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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

    @JsonProperty("takeprofit_pct")
    private float takeprofitPct;

    @JsonProperty("stoploss_timing")
    private String stoplossTiming;

    @JsonProperty("takeprofit_timing")
    private String takeprofitTiming;

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

    private String universe;
    private float capital;
    private int slots;
    private String rebalance;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}

