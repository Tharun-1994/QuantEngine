package com.backtest.engine.ruleBuilder;

import java.util.ArrayList;
import java.util.List;

import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.RuleLeafNodeDto;
import com.backtest.engine.dto.request.RuleNodeDto;

public final class RuleTreeFlattener {

    private RuleTreeFlattener() {}

    public static List<RuleDto> flatten(RuleNodeDto node) {
        List<RuleDto> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(RuleNodeDto node, List<RuleDto> out) {
        if (node == null) return;

        if (node instanceof RuleLeafNodeDto leaf) {
            if (leaf.getRule() != null) out.add(leaf.getRule());
            return;
        }

        if (node instanceof RuleGroupNodeDto group && group.getChildren() != null) {
            for (RuleNodeDto c : group.getChildren()) collect(c, out);
        }
    }
}

