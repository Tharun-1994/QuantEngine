package com.backtest.engine.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.backtest.engine.entity.TradePair;
import com.backtest.engine.service.PairingContext;
import com.backtest.engine.service.PairingService;

/**
 * Default PairingService implementation: positional pairing with single-pass
 * backtracking when a pair matches a disallowed combo.
 *
 * Algorithm (per call, one trading day):
 *   1. Equalise: n = min(longCands.size, shortCands.size, maxPairs)
 *   2. For i in 0..n-1: take longCands[i], shortCands[i] as the candidate pair.
 *   3. If the pair matches any disallowed_combo, try backtracking:
 *      - Read backtracking.swap_target ("long_leg" | "short_leg") and
 *        backtracking.pool ("pre_reduction_top" | "pre_reduction_bottom").
 *      - Filter that pool: remove already-used replacements, the current
 *        pair's two legs, and any ticker whose backtracking.exclude_attribute
 *        equals backtracking.exclude_value.
 *      - Pick the first or last surviving candidate per backtracking.selection.
 *      - Swap it into the offending leg.
 *   4. If backtracking can't find a replacement, drop the pair entirely.
 *   5. Assign sequential pairIds (0, 1, 2 ...) to surviving pairs.
 *
 * LRA Patch 19.
 */
@Service
public class PairingServiceImpl implements PairingService {

    @Override
    public List<TradePair> constructPairs(PairingContext ctx) {
        List<String> longCands  = ctx.getLongCandidates()  != null ? ctx.getLongCandidates()  : new ArrayList<>();
        List<String> shortCands = ctx.getShortCandidates() != null ? ctx.getShortCandidates() : new ArrayList<>();

        int n = Math.min(longCands.size(), shortCands.size());
        n = Math.min(n, ctx.getMaxPairs());

        List<TradePair> result = new ArrayList<>();
        Set<String> usedFromPool = new HashSet<>();
        int pairId = ctx.getPairIdStart();

        for (int i = 0; i < n; i++) {
            String longLeg  = longCands.get(i);
            String shortLeg = shortCands.get(i);

            if (isDisallowed(longLeg, shortLeg, ctx)) {
                TradePair swapped = backtrack(longLeg, shortLeg, ctx, usedFromPool);
                if (swapped == null) {
                    continue;   // could not resolve — drop the pair
                }
                longLeg  = swapped.getLongLeg();
                shortLeg = swapped.getShortLeg();
            }

            result.add(TradePair.builder()
                    .longLeg(longLeg)
                    .shortLeg(shortLeg)
                    .pairId(pairId++)
                    .build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean isDisallowed(String longLeg, String shortLeg, PairingContext ctx) {
        Map<String, Object> rules = ctx.getPairingRules();
        if (rules == null) {
            return false;
        }
        List<Map<String, Object>> combos =
                (List<Map<String, Object>>) rules.get("disallowed_combos");
        if (combos == null || combos.isEmpty()) {
            return false;
        }

        for (Map<String, Object> combo : combos) {
            String longAttr  = (String) combo.get("long_attribute");
            String longVal   = (String) combo.get("long_value");
            String shortAttr = (String) combo.get("short_attribute");
            String shortVal  = (String) combo.get("short_value");

            String actualLong  = readAttribute(ctx, longLeg,  longAttr);
            String actualShort = readAttribute(ctx, shortLeg, shortAttr);

            if (longVal  != null && longVal.equals(actualLong)
             && shortVal != null && shortVal.equals(actualShort)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private TradePair backtrack(String longLeg, String shortLeg,
                                PairingContext ctx, Set<String> usedFromPool) {
        Map<String, Object> rules = ctx.getPairingRules();
        Map<String, Object> bt    = (Map<String, Object>) rules.get("backtracking");
        if (bt == null) {
            return null;
        }

        String swapTarget = (String) bt.get("swap_target");
        String poolKey    = (String) bt.get("pool");
        String excludeAttr = (String) bt.get("exclude_attribute");
        String excludeVal  = (String) bt.get("exclude_value");
        String selection   = (String) bt.getOrDefault("selection", "last");

        List<String> pool = "pre_reduction_top".equals(poolKey)
                ? ctx.getPreReductionShortPool()
                : ctx.getPreReductionLongPool();
        if (pool == null) {
            return null;
        }

        List<String> candidates = new ArrayList<>();
        for (String t : pool) {
            if (usedFromPool.contains(t)) continue;
            if (t.equals(longLeg) || t.equals(shortLeg)) continue;
            if (excludeAttr != null && excludeVal != null) {
                String attrVal = readAttribute(ctx, t, excludeAttr);
                if (excludeVal.equals(attrVal)) continue;
            }
            candidates.add(t);
        }
        if (candidates.isEmpty()) {
            return null;
        }

        String replacement = "first".equals(selection)
                ? candidates.get(0)
                : candidates.get(candidates.size() - 1);
        usedFromPool.add(replacement);

        if ("short_leg".equals(swapTarget)) {
            return TradePair.builder().longLeg(longLeg).shortLeg(replacement).build();
        }
        // default: swap the long leg
        return TradePair.builder().longLeg(replacement).shortLeg(shortLeg).build();
    }

    private String readAttribute(PairingContext ctx, String symbol, String attribute) {
        if (attribute == null || symbol == null) {
            return null;
        }
        Map<String, Map<String, Object>> classification = ctx.getTickerClassification();
        if (classification == null) {
            return null;
        }
        Map<String, Object> meta = classification.get(symbol);
        if (meta == null) {
            return null;
        }
        return (String) meta.get(attribute);
    }
}