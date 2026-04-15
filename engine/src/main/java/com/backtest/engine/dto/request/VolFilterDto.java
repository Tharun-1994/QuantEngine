package com.backtest.engine.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VolFilterDto {

    /**
     * When false the engine skips all vol/turnover threshold logic entirely
     * and every entry candidate passes. Matches Python: new_volume_threshold=0.
     */
    private boolean enabled;

    /**
     * Key for SPY closes parquet: DAILY_closes_{spy_ticker}.parquet.
     * Default "spy". Used to compute SMA(200) for bull/bear detection.
     */
    @JsonProperty("spy_ticker")
    private String spyTicker;

    /**
     * Bull percentile cutoff for avg_volume (SPY close > SPY SMA200).
     * Bottom vol_pct_bull fraction excluded. Python: vol_threshold_pct_bull=0.20
     */
    @JsonProperty("vol_pct_bull")
    private float volPctBull;

    /**
     * Bear percentile cutoff for avg_volume (SPY close <= SPY SMA200).
     * Python: vol_threshold_pct_bear=0.45
     */
    @JsonProperty("vol_pct_bear")
    private float volPctBear;

    /**
     * Bull percentile cutoff for avg_turnover.
     * Python: turnover_threshold_pct_bull=0.35
     */
    @JsonProperty("turnover_pct_bull")
    private float turnoverPctBull;

    /**
     * Bear percentile cutoff for avg_turnover.
     * Python: turnover_threshold_pct_bear=0.05
     */
    @JsonProperty("turnover_pct_bear")
    private float turnoverPctBear;
}