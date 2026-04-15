package com.backtest.engine.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.config.StaticConfig;
import com.backtest.engine.dto.request.MarketRegimeDto;
import com.backtest.engine.dto.request.VolFilterDto;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class PriceLoader {
	
	private String rebalance;
	private String universe;
	private String rankingIndicator;
	private int rankingLookback;
	private int atrLimitLookback;
	private int atrLookbackStp;
	private int atrLookbackTp;

	
	private Set<String> marketTickers;

	/** When true, avg_volume, avg_turnover, and closes_spy are added to the file map. */
	private boolean volFilterEnabled;
	
	
	
	

	public Map<String, String> getFilesForRebalance(List<MarketRegimeDto> marketRegimes) {
	    Set<String> tickers = new HashSet<>();
	    for (MarketRegimeDto regime : marketRegimes) {
	        tickers.add(regime.getRegimeTicker().toLowerCase());
	    }

	    String prefix = switch (rebalance.toLowerCase()) {
	        case "daily" -> "DAILY_";
	        case "weekly" -> "weekly_";
	        case "monthly" -> "monthly_";
	        default -> throw new IllegalArgumentException("Unknown rebalance: " + rebalance);
	    };

	    String univ = switch (universe.toLowerCase()) {
	        case "sp500" -> "sp500_";
	        case "russell3000" -> "russell3000_";
	        case "liquid500" -> "liquid500_";
	        default -> throw new IllegalArgumentException("Unknown Universe: " + universe);
	    };

	    String ranking = rankingIndicator + "_" + rankingLookback;

	    Map<String, String> files = new HashMap<>();

	    // Base files
	    files.put("closes", prefix + "closes.parquet");
	    files.put("opens", prefix + "opens.parquet");
	    files.put("highs", prefix + "highs.parquet");
	    files.put("lows", prefix + "lows.parquet");
	    files.put("universes", univ + "universe.parquet");
	    files.put("trading_dates", "trading_dates.parquet");
	    files.put("all_dates", "all_dates.parquet");
	    files.put("ranking", ranking + ".parquet");

	    if (atrLimitLookback > 0) {
	        files.put("atr_limit", String.format("%s_%d.parquet", StaticConfig.AVGTRUERANGE, atrLimitLookback));
	    }
	    if (atrLookbackStp > 0) {
	        files.put("atr_stp", String.format("%s_%d.parquet", StaticConfig.AVGTRUERANGE, atrLookbackStp));
	    }
	    if (atrLookbackTp > 0) {
	        files.put("atr_tp", String.format("%s_%d.parquet", StaticConfig.AVGTRUERANGE, atrLookbackTp));
	    }

	    this.marketTickers = tickers;

	    // Vol/Turnover filter files — loaded when filter is enabled
	    if (volFilterEnabled) {
	        files.put("avg_volume",   "avg_volume.parquet");
	        files.put("avg_turnover", "avg_turnover.parquet");
	    }

	    for (String ticker : tickers) {
	    	if(!ticker.isBlank() && ! ticker.isEmpty()) {
		        String key = "closes_" + ticker;
		        String value = prefix + "closes_" + ticker + ".parquet";
		        files.put(key, value);
	    	}

	    }

	    return files;
	}

    
   

}