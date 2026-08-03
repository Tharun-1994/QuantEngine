package com.backtest.engine.ruleBuilder;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.RuleLeafNodeDto;
import com.backtest.engine.dto.request.RuleNodeDto;

/**
 * Date-set tree evaluator for MARKET TREND rules.
 *
 * Mirrors {@link RuleTreeEvaluator}, but each leaf's pre-computed result is
 * a {@code Set<LocalDate>} (dates when the leaf passes on its target market
 * ticker) instead of a per-date {@code Set<String>} (tickers passing the rule).
 *
 * AND-groups intersect date sets; OR-groups union them. The output is the
 * set of dates on which the entire tree evaluates to TRUE for its market.
 *
 * This lets the engine support nested groups and mixed AND/OR for trend
 * rules — same expressive power the entry/exit rule trees already have —
 * without flattening the tree at the data-flow boundary.
 */
public final class MarketTrendTreeEvaluator {

    private MarketTrendTreeEvaluator() {}

    public static Set<LocalDate> evalTree(
            RuleNodeDto node,
            Map<String, Set<LocalDate>> leafPassingDates
    ) {
        if (node == null) return Set.of();

        if (node instanceof RuleLeafNodeDto leaf) {
            return leafPassingDates.getOrDefault(leaf.getId(), Set.of());
        }

        RuleGroupNodeDto group = (RuleGroupNodeDto) node;
        var kids = group.getChildren();
        if (kids == null || kids.isEmpty()) return Set.of();

        if (group.getLogic() == RuleGroupNodeDto.Logic.OR) {
            Set<LocalDate> out = new HashSet<>();
            for (RuleNodeDto c : kids) out.addAll(evalTree(c, leafPassingDates));
            return out;
        } else {
            Set<LocalDate> out = new HashSet<>(evalTree(kids.get(0), leafPassingDates));
            for (int i = 1; i < kids.size(); i++) {
                out.retainAll(evalTree(kids.get(i), leafPassingDates));
                if (out.isEmpty()) break; // short-circuit
            }
            return out;
        }
    }
}