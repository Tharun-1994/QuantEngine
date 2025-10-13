package com.backtest.engine.entity;

import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IndependentPriceDataContext implements PriceDataContext {
    private final Map<String, PriceDataV2> regimeData; // full PriceDataV2 per regime
}
