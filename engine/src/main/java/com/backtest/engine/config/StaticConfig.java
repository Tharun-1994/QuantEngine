package com.backtest.engine.config;

import java.util.Map;

public class StaticConfig {

	public static final Map<String,String> systemType = Map.of("long","LONG","short","SHORT");
	
	public static final Map<String,String> timing = Map.of("eod","EOD","intraday","INTRADAY");
	
	public static final Map<String,String> stoplossType = Map.of("nrml","NORMAL","atr_based","ATRBASED");
	public static final Map<String,String> takeProfitType = Map.of("nrml","NORMAL","atr_based","ATRBASED");
	
	
	public static final Map<String,String> orderType = Map.of("normal","NORMAL","limit_atr","LIMIT_ATR","limit","LIMIT");
	
	public static final String AVGTRUERANGE = "AvgTrueRange";
	
	
}
