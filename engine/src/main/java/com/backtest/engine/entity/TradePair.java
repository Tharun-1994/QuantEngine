package com.backtest.engine.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A long+short pair candidate produced by PairingService (Patch 20) and consumed
 * by SizingPolicyResolver (this patch).
 *
 * pairId is stamped onto both legs' TradeLog entries when the pair opens (Patch 22),
 * letting PairProfitExitProcessor group legs.
 *
 * Used only on the LONGSHORT system_type path. Existing LONG / SHORT strategies
 * never instantiate this type.
 *
 * LRA Patch 18.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradePair {

    /** Ticker symbol of the long leg. */
    private String longLeg;

    /** Ticker symbol of the short leg. */
    private String shortLeg;

    /**
     * Sequential pair identifier assigned by PairingService at construction time.
     * Both legs' TradeLog entries carry this id, enabling pair-level exit logic
     * (combined-P&L close, force-close together at max_hold).
     */
    private Integer pairId;
}