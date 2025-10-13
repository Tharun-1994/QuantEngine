package com.backtest.engine.entity;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class LimitOrder {
	private String ticker;
	private float limitPrice;
	
	
}
