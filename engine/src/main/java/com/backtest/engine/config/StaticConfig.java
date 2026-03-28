package com.backtest.engine.config;

import java.util.Map;

import com.backtest.engine.dto.request.RuleDto;

public class StaticConfig {

	public static final Map<String,String> systemType = Map.of("long","LONG","short","SHORT");
	
	public static final Map<String,String> timing = Map.of("eod","EOD","intraday","INTRADAY");
	
	public static final Map<String,String> stoplossType = Map.of("nrml","NORMAL","atr_based","ATRBASED");
	public static final Map<String,String> takeProfitType = Map.of("nrml","NORMAL","atr_based","ATRBASED");
	
	
	public static final Map<String,String> orderType = Map.of("normal","NORMAL","limit_atr","LIMIT_ATR","limit","LIMIT");
	
	public static final String AVGTRUERANGE = "AvgTrueRange";
	
	public static final String N_WEEK_HIGH_RECENT = "n_week_high_recent";
	
	public static final String SHARPE = "sharpe";
	
	public static String getN_WEEK_HIGH_RECENT(RuleDto rc) {
	    if (rc == null || !N_WEEK_HIGH_RECENT.equals(rc.getIndicator())) {
	        return "";
	    }

	    // Use Object.toString() and Integer.parseInt to be safer against type mismatches
	    try {
	        Object nWeeksObj = rc.getParams().get("n_week_days");
	        Object withinObj = rc.getParams().get("within_days");
	        
	        if (nWeeksObj == null || withinObj == null) return "";

	        return String.format("%s_%s_%s", 
	            rc.getIndicator(), 
	            nWeeksObj.toString(), 
	            withinObj.toString()
	        );
	    } catch (Exception e) {
	        // Log error: Parameters missing or invalid
	        return "";
	    }
	}
	
	
	
	public static String getSharpeKey(RuleDto rc) {
	    if (rc == null || !SHARPE.equals(rc.getIndicator())) {
	        return "";
	    }
	    try {
	        Object momObj = rc.getParams().get("momentum_lookback");
	        Object volObj = rc.getParams().get("vol_lookback");
	        Object skipObj = rc.getParams().get("skip_days");
 
	        if (momObj == null || volObj == null) return "";
 
	        String skip = (skipObj != null) ? skipObj.toString() : "0";
	        return String.format("%s_%s_%s_%s",
	            rc.getIndicator(),
	            momObj.toString(),
	            volObj.toString(),
	            skip
	        );
	    } catch (Exception e) {
	        return "";
	    }
	}
	
	
}
