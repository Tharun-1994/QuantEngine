package com.backtest.engine.dto.request;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RuleDto {

    private String indicator;
    private Integer lookback;
    private String operator;
    private float value;
    private String connector;
    
	@JsonProperty("value_indicator")
    private String valueIndicator;
	@JsonProperty("value_type")
    private String valueType;
	
	@JsonProperty("value_lookback")
    private Integer valueLookback;
    
    private String label;  // optional
    
    private Map<String, Object> params; // params?: Record<string, any>
    @JsonProperty("regime_ticker")
    private String regimeTicker;  // per-rule ticker for market trend (e.g. "SPY", "VIX")
    
    @JsonProperty("ranking_order")
    private String rankingOrder;
}

