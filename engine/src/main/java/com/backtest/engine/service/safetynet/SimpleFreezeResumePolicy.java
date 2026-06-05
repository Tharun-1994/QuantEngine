package com.backtest.engine.service.safetynet;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.SafetyNetItemDto;
import com.backtest.engine.service.impl.VolatilityCutEvaluator;
import com.backtest.engine.util.ArrowDataFrame;

/**
 * Stateless freeze/resume policy — wraps the existing rule-tree based
 * volatility-cut logic in the new framework.
 *
 * <p>Reads from its config params:</p>
 * <ul>
 *   <li>{@code freeze_rules_tree}  — RuleGroupNodeDto, when to enter frozen state</li>
 *   <li>{@code resume_rules_tree}  — RuleGroupNodeDto, when to leave frozen state</li>
 *   <li>{@code freeze_timing}      — "open" (default) or "close"</li>
 *   <li>{@code resume_timing}      — "open" (default) or "close"</li>
 * </ul>
 *
 * <p>At {@link #initialize} it precomputes the date sets where each
 * condition holds. At evaluation it just checks set membership against
 * the configured timing — O(1) per day.</p>
 *
 * <p>Behaviourally identical to the inline freeze/resume code path in
 * BacktestServiceImplV2 before Stage 3b.</p>
 */
public class SimpleFreezeResumePolicy implements SafetyNetPolicy {

    private Set<LocalDate> freezeDays = Collections.emptySet();
    private Set<LocalDate> resumeDays = Collections.emptySet();
    private String freezeTiming = "open";
    private String resumeTiming = "open";

    @Override
    public void initialize(SafetyNetItemDto config, SafetyNetInitContext initCtx) {
        Map<String, Object> params = config.getParams();
        if (params == null) params = Collections.emptyMap();

        // Convert raw maps from Jackson into typed RuleGroupNodeDto
        RuleGroupNodeDto freezeTree = toTree(params.get("freeze_rules_tree"), initCtx);
        RuleGroupNodeDto resumeTree = toTree(params.get("resume_rules_tree"), initCtx);

        this.freezeTiming = strOrDefault(params.get("freeze_timing"), "open");
        this.resumeTiming = strOrDefault(params.get("resume_timing"), "open");

        // Load market frames the trees reference, then evaluate trees to date sets
        Map<String, ArrowDataFrame> freezeFrames =
            (freezeTree == null) ? Map.of() : initCtx.getFrameLoader().apply(freezeTree);
        Map<String, ArrowDataFrame> resumeFrames =
            (resumeTree == null) ? Map.of() : initCtx.getFrameLoader().apply(resumeTree);

        this.freezeDays = VolatilityCutEvaluator.evaluateTreeToDates(
            freezeTree, freezeFrames, initCtx.getAllDates());
        this.resumeDays = VolatilityCutEvaluator.evaluateTreeToDates(
            resumeTree, resumeFrames, initCtx.getAllDates());
    }

    @Override
    public SafetyNetDecision evaluateAtOpen(LocalDate date, LocalDate previousDate) {
        // Resume check first so a freeze→resume on the same day cleanly lifts suspension
        if ("open".equalsIgnoreCase(resumeTiming)
                && previousDate != null && resumeDays.contains(previousDate)) {
            return SafetyNetDecision.resume("Vol cut resume (open timing)");
        }
        if ("open".equalsIgnoreCase(freezeTiming)
                && previousDate != null && freezeDays.contains(previousDate)) {
            return SafetyNetDecision.freeze("Volatility Cut");
        }
        return SafetyNetDecision.noop();
    }

    @Override
    public SafetyNetDecision evaluateAtClose(LocalDate date) {
        if ("close".equalsIgnoreCase(resumeTiming) && resumeDays.contains(date)) {
            return SafetyNetDecision.resume("Vol cut resume (close timing)");
        }
        if ("close".equalsIgnoreCase(freezeTiming) && freezeDays.contains(date)) {
            return SafetyNetDecision.freeze("Volatility Cut");
        }
        return SafetyNetDecision.noop();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static RuleGroupNodeDto toTree(Object obj, SafetyNetInitContext ctx) {
        if (obj == null) return null;
        if (obj instanceof RuleGroupNodeDto) return (RuleGroupNodeDto) obj;
        return ctx.getObjectMapper().convertValue(obj, RuleGroupNodeDto.class);
    }

    private static String strOrDefault(Object obj, String dflt) {
        if (obj == null) return dflt;
        String s = obj.toString();
        return s.isBlank() ? dflt : s;
    }
}