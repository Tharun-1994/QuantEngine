package com.backtest.engine.util;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

public class DataCache {
    private final float[][] values; 
    private final LocalDate[] dates;
    private final String[] tickers;
    private final Map<LocalDate, Integer> dateIndex;
    private final Map<String, Integer> tickerIndex;

    public DataCache(float[][] values, LocalDate[] dates, String[] tickers,
                     Map<LocalDate, Integer> dateIndex,
                     Map<String, Integer> tickerIndex) {
        this.values = values;
        this.dates = dates;
        this.tickers = tickers;
        this.dateIndex = dateIndex;
        this.tickerIndex = tickerIndex;
    }

    // Lookup by date+ticker
    public float get(LocalDate date, String ticker) {
        Integer row = dateIndex.get(date);
        Integer col = tickerIndex.get(ticker);
        if (row == null || col == null) return Float.NaN;
        return values[row][col];
    }

    // For compatibility with your old API: map.get(date).get(ticker)
    public Map<String, Float> get(LocalDate date) {
        Integer row = dateIndex.get(date);
        if (row == null) return Map.of();
        Map<String, Float> result = new HashMap<>();
        for (int c = 0; c < tickers.length; c++) {
            result.put(tickers[c], values[row][c]);
        }
        return result;
    }
}

