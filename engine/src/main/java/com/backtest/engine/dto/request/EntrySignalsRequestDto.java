package com.backtest.engine.dto.request;

import java.time.LocalDate;
import java.util.List;

import lombok.Builder;
import lombok.Data;
@Builder
@Data
public class EntrySignalsRequestDto {
	private LocalDate tradeDate;
	private LocalDate previousDate;
	private List<String> entries;
	private int maxSingleStock;
	private String reasonForEntry;
	private String entryTime;
	private int slotCapital;
	private int fees;
	private int slippage;
	private int maxSlots;
	private int startingCapital;
	private int maxQuantitites;
	private String direction;
}
