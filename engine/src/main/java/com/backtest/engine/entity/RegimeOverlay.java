package com.backtest.engine.entity;

import com.backtest.engine.util.ArrowDataFrame;

import lombok.Builder;
import lombok.Data;

@Data
@Builder

public class RegimeOverlay {
	
    private ArrowDataFrame dailyAtr;
    private ArrowDataFrame ranking;
}
