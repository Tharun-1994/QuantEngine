package com.backtest.engine.dto.request;

import java.time.LocalDate;

import lombok.Data;

@Data
public class TradeEnterRequestDto {
	private String ticker;
	private LocalDate tradeDate;
	private String direction;
	private int quantity;
	private String reason;
	private String priceUsed;
	private String entryTiming;
	private float entryprice;
	private int capital;
}
