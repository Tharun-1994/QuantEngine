package com.backtest.engine.dto.request;

import java.time.LocalDate;

import lombok.Data;
@Data
public class TradeExitRequestDto {
	private String tradeId;
	private LocalDate tradeDate;
	private Double exitPrice;
	private String exitReason;
	private String priceUsed;
}
