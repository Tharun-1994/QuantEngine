package com.backtest.engine.entity;

import java.util.Map;

import com.backtest.engine.dto.request.MarketRegimeDto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SharedPriceDataContext implements PriceDataContext {
    private final PriceDataV2 core;   // shared OHLC/universe/dates
    private final Map<MarketRegimeDto, RegimeOverlay> regimeOverlays; // keyed by regimeId
}
