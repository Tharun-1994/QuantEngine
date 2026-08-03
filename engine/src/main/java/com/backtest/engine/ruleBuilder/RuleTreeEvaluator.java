package com.backtest.engine.ruleBuilder;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.RuleLeafNodeDto;
import com.backtest.engine.dto.request.RuleNodeDto;

public final class RuleTreeEvaluator {

    private RuleTreeEvaluator() {}

    public static Set<String> evalForDate(
            RuleNodeDto node,
            LocalDate date,
            Map<String, Map<LocalDate, Set<String>>> leafCache
    ) {
        if (node == null) return Set.of();

        if (node instanceof RuleLeafNodeDto leaf) {
            Map<LocalDate, Set<String>> byDate = leafCache.get(leaf.getId());
            if (byDate == null) return Set.of();
            return byDate.getOrDefault(date, Set.of());
        }

        RuleGroupNodeDto group = (RuleGroupNodeDto) node;
        var kids = group.getChildren();
        if (kids == null || kids.isEmpty()) return Set.of();

        if (group.getLogic() == RuleGroupNodeDto.Logic.OR) {
            Set<String> out = new HashSet<>();
            for (RuleNodeDto c : kids) out.addAll(evalForDate(c, date, leafCache));
            return out;
        } else {
            Set<String> out = new HashSet<>(evalForDate(kids.get(0), date, leafCache));
            for (int i = 1; i < kids.size(); i++) {
                out.retainAll(evalForDate(kids.get(i), date, leafCache));
                if (out.isEmpty()) break; // short-circuit
            }
            return out;
        }
    }
}

