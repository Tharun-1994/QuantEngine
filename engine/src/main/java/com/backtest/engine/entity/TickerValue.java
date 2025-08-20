package com.backtest.engine.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TickerValue {

	private String ticker;
	private Double value;
}
