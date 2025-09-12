package com.backtest.engine.util;

import java.util.Map;

import com.backtest.engine.config.StaticConfig;

public class PriceLoader {

    public static Map<String, String> getFilesForRebalance(String rebalance, String universe,String rankingIndicator,int rankingLookback, int atrLimitLookback) {

        String prefix = switch (rebalance.toLowerCase()) {
            case "daily" -> "daily_";
            case "weekly" -> "weekly_";
            case "monthly" -> "monthly_";
            default -> throw new IllegalArgumentException("Unknown rebalance: " + rebalance);
        };

        String univ =  switch (universe.toLowerCase()) {
        case "sp500" -> "sp500_";
        case "r3000" -> "r3000_";
        case "liquid 500" -> "liquid500_";
        default -> throw new IllegalArgumentException("Unknown Universe: " + universe);
       
    };
    
    
    if(atrLimitLookback > 0 ) {
    	
    }
    
    String ranking = rankingIndicator+"_"+rankingLookback;
    
        return Map.of(
            "closes", prefix + "closes.parquet",
            "opens", prefix + "opens.parquet",
            "highs", prefix + "highs.parquet",
            "lows", prefix + "lows.parquet",
            "universes", univ + "universe.parquet",
            "trading_dates", "trading_dates.parquet",
            "all_dates", "all_dates.parquet",
            "ranking", ranking+".parquet",
            "atr_limit", atrLimitLookback > 0  ? String.format("%s_%d.parquet", StaticConfig.AVGTRUERANGE, atrLimitLookback) : ""
        );
    
    
    }
    
   

}
