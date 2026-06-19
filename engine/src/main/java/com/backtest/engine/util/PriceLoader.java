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
	
	
	/**
     * Walk a rule tree and collect any `regime_ticker` values from leaf rules.
     *
     * Used by {@link #getFilesForRebalance} to determine which {@code closes_{ticker}.parquet}
     * files to load. Tickers live on rule-tree leaves in the current UI design;
     * the regime-level {@code regime_ticker} field is legacy and ignored.
     */
    @SuppressWarnings("unchecked")
    private void collectTickersFromTree(Object node, Set<String> tickers) {
        if (!(node instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) node;
        Object type = m.get("type");
        if ("rule".equals(type)) {
            Map<String, Object> r = (Map<String, Object>) m.get("rule");
            if (r == null) return;
            Object t = r.get("regime_ticker");
            if (t != null && !t.toString().isBlank()) {
                tickers.add(t.toString().toLowerCase());
            }
            return;
        }
        Object children = m.get("children");
        if (children instanceof List<?> list) {
            for (Object c : list) collectTickersFromTree(c, tickers);
        }
    }

	/** Collect frame filenames referenced by a freeze/resume tree (VIX close + VIX SMA, etc.). */
    @SuppressWarnings("unchecked")
    private void collectVolCutFiles(Object node, String prefix, Map<String, String> files) {
        if (!(node instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) node;
        Object type = m.get("type");
        if ("rule".equals(type)) {
            Map<String, Object> r = (Map<String, Object>) m.get("rule");
            if (r == null) return;
            String ticker = r.get("regime_ticker") == null ? "" : r.get("regime_ticker").toString().toLowerCase();
            if (ticker.isBlank()) return;
            // LHS frame: {ticker}_{indicator}_{lookback}
            String ind = r.get("indicator") == null ? "" : r.get("indicator").toString().toLowerCase();
            int lb = r.get("lookback") == null ? 0 : ((Number) r.get("lookback")).intValue();
            if (!ind.isBlank()) {
                String key = ticker + "_" + ind + "_" + lb;
                files.put(key, key + ".parquet");
            }
            // RHS frame (only when comparing to an indicator): {ticker}_{value_indicator}_{value_lookback}
            Object vt = r.get("value_type");
            String valInd = r.get("value_indicator") == null ? "" : r.get("value_indicator").toString().toLowerCase();
            if ("indicator_price".equalsIgnoreCase(String.valueOf(vt)) && !valInd.isBlank()) {
                int vlb = r.get("value_lookback") == null ? 0 : ((Number) r.get("value_lookback")).intValue();
                String key = ticker + "_" + valInd + "_" + vlb;
                files.put(key, key + ".parquet");
            }
            return;
        }
        Object children = m.get("children");
        if (children instanceof List<?> list) {
            for (Object c : list) collectVolCutFiles(c, prefix, files);
        }
    }

	public Map<String, String> getFilesForRebalance(List<MarketRegimeDto> marketRegimes) {
	    Set<String> tickers = new HashSet<>();
	    for (MarketRegimeDto regime : marketRegimes) {
	        
	        collectTickersFromTree(regime.getMarketTrendRulesTree(), tickers);
	        collectTickersFromTree(regime.getFreezeRulesTree(),      tickers);
	        collectTickersFromTree(regime.getResumeRulesTree(),      tickers);
	    }

	    String prefix = switch (rebalance.toLowerCase()) {
	        case "daily" -> "DAILY_";
	        case "weekly" -> "weekly_";
	        case "monthly" -> "monthly_";
	        default -> throw new IllegalArgumentException("Unknown rebalance: " + rebalance);
	    };

	    String univ = switch (universe.toLowerCase()) {
	        case "sp500" -> "sp500_";
	        case "sp100" -> "sp100_";
	        case "nasdaq100" -> "nasdaq100_";
	        case "russell3000" -> "russell3000_";
	        case "liquid500" -> "liquid500_";
	        case "lra14" -> "lra14_";   // LRA Patch 43
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