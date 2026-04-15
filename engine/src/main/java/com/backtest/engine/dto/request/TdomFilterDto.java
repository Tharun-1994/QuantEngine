package com.backtest.engine.dto.request;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TdomFilterDto {

    /**
     * 0-based trading-day-of-month index.
     * 0 = first trading day of the month, 1 = second, etc.
     * Matches Python: filtered_timestamps2.index(x)
     * Null when this rule is weekday-based.
     */
    private Integer tdom;

    /**
     * Python-style weekday: 0 = Monday … 4 = Friday.
     * Matches Python: d.weekday()
     * Null when this rule is tdom-based.
     */
    private Integer weekday;

    /**
     * Calendar months (1 = Jan … 12 = Dec) that are blocked
     * when the tdom or weekday condition is met.
     */
    @JsonProperty("banned_months")
    private List<Integer> bannedMonths;
}