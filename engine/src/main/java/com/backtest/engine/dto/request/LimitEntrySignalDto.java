package com.backtest.engine.dto.request;

import java.time.LocalDate;
import java.util.List;

import com.backtest.engine.entity.LimitOrder;

import lombok.Builder;
import lombok.Data;
@Builder
@Data
public class LimitEntrySignalDto {

	private LocalDate tradeDate;
	private LocalDate previousDate;
	private List<LimitOrder> limitOrders;
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
	private float minStockPricePerSlot;
	private float gapFilterPct;
}
