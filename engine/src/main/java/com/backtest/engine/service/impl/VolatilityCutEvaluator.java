package com.backtest.engine.service.impl;

import com.backtest.engine.dto.request.RuleDto;
import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.RuleLeafNodeDto;
import com.backtest.engine.dto.request.RuleNodeDto;
import com.backtest.engine.util.ArrowDataFrame;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Volatility-cut (freeze / resume) per-date evaluator.
 *
 * Freeze/resume rules are MARKET-LEVEL conditions evaluated once per date.
 * They reduce to a single boolean PER DATE (not per ticker), so this evaluator
 * walks the tree once and returns the set of dates on which the tree is true.
 *
 * ── Frame key convention (FIX for Bug 1) ────────────────────────────────────
 * Both loadVolatilityCutFrames (BacktestContext) and this evaluator now use
 * the same key format:
 *
 *   {ticker}_{indicator}_{lookback}   e.g.  spy_close_0 , spy_sma_200
 *
 * When regime_ticker is blank/null the key degenerates to _{indicator}_{lb},
 * which is consistent with how it was loaded.
 *
 * ── SPY SMA on-the-fly (FIX for Bug 3) ──────────────────────────────────────
 * If the RHS frame key is not found in the frame map AND the RHS indicator is
 * "sma", the evaluator falls back to computing a simple rolling mean over the
 * LHS ticker's close frame.  This avoids needing a pre-built spy_sma_200.parquet.
 * The close frame for the ticker must already be present under key
 * {ticker}_close_0.
 *
 * ── Month-in operator (NEW) ──────────────────────────────────────────────────
 * Operator "month_in" allows calendar-based freeze rules such as
 * "month in [5, 6]".  The rule is configured as:
 *   indicator  = "month"   (special sentinel — no parquet file needed)
 *   operator   = "month_in"
 *   value      = comma-separated month numbers, e.g. "5,6"  (stored in label field)
 *   OR value   = a single month number stored in the numeric value field
 *
 * For a multi-month list the UI stores the months as a comma-separated string
 * in rule.label (e.g. "5,6").  The evaluator checks d.getMonthValue() against
 * that list.  If label is blank it falls back to (int) rule.getValue().
 */
public final class VolatilityCutEvaluator {

    private VolatilityCutEvaluator() {}

    /**
     * Evaluate a freeze/resume tree to the set of dates on which it holds true.
     *
     * @param tree        parsed freeze or resume tree (may be null/empty → empty set)
     * @param frames      map of {ticker}_{indicator}_{lookback} → ArrowDataFrame
     * @param sortedDates the backtest trading dates to evaluate over
     * @return set of dates where the tree is true (empty when tree is null/empty)
     */
    public static Set<LocalDate> evaluateTreeToDates(RuleGroupNodeDto tree,
                                                     Map<String, ArrowDataFrame> frames,
                                                     List<LocalDate> sortedDates) {
        Set<LocalDate> result = new HashSet<>();
        if (tree == null || tree.getChildren() == null || tree.getChildren().isEmpty()) {
            return result;
        }
        for (int i = 0; i < sortedDates.size(); i++) {
            if (evalNode(tree, frames, sortedDates, i)) {
                result.add(sortedDates.get(i));
            }
        }
        return result;
    }

    // ── Node evaluation ──────────────────────────────────────────────────────

    private static boolean evalNode(RuleNodeDto node, Map<String, ArrowDataFrame> frames,
            List<LocalDate> sortedDates, int i) {
        if (node instanceof RuleGroupNodeDto group) {
            List<RuleNodeDto> children = group.getChildren();
            if (children == null || children.isEmpty()) return false;
            boolean isAnd = group.getLogic() != RuleGroupNodeDto.Logic.OR;
            for (RuleNodeDto child : children) {
                boolean childVal = evalNode(child, frames, sortedDates, i);
                if (isAnd && !childVal) return false;
                if (!isAnd && childVal)  return true;
            }
            return isAnd;
        }
        if (node instanceof RuleLeafNodeDto leaf) {
            return evalLeaf(leaf.getRule(), frames, sortedDates, i);
        }
        return false;
    }

    // ── Leaf evaluation ──────────────────────────────────────────────────────

    private static boolean evalLeaf(RuleDto rule, Map<String, ArrowDataFrame> frames,
            List<LocalDate> sortedDates, int i) {
        if (rule == null) return false;

        // Shift (bars-ago): evaluate this leaf `shift` TRADING bars before the decision
        // bar (default 0 = the decision bar). Bar-indexed via the sorted trading
        // calendar, so shift=1 from a Monday lands on the prior Friday. Enables the RSI
        // overbought-rollover (rsi[T-2] vs rsi[T-1]). shift=0 == prior behaviour.
        int shift = (rule.getShift() == null || rule.getShift() < 0) ? 0 : rule.getShift();
        int shiftedIdx = i - shift;
        if (shiftedIdx < 0) return false; // not enough history for the requested shift
        LocalDate d = sortedDates.get(shiftedIdx);

        // ── Special case: month_in operator

        // ── Special case: month_in operator ─────────────────────────────────
        // indicator="month", operator="month_in", label="5,6" (or value=5 for single)
        if ("month_in".equalsIgnoreCase(rule.getOperator())) {
            return evalMonthIn(rule, d);
        }

        // ── Normal indicator comparison ──────────────────────────────────────
        // FIX (Bug 1): key format is now {ticker}_{indicator}_{lookback}
        // matching exactly what loadVolatilityCutFrames stores.
        String ticker = (rule.getRegimeTicker() == null || rule.getRegimeTicker().isBlank())
                ? ""
                : rule.getRegimeTicker().toLowerCase();

        String lhsKey = buildKey(ticker, rule.getIndicator(), rule.getLookback());
        Float lhs = scalarFor(frames.get(lhsKey), d);
        if (lhs == null || lhs.isNaN()) return false;

        // ── RHS ──────────────────────────────────────────────────────────────
        float rhs;
        boolean isIndicatorRhs = "indicator_price".equalsIgnoreCase(rule.getValueType())
                && rule.getValueIndicator() != null && !rule.getValueIndicator().isBlank();
        if (isIndicatorRhs) {
            String rhsKey = buildKey(ticker, rule.getValueIndicator(), rule.getValueLookback());
            ArrowDataFrame rhsDf = frames.get(rhsKey);

            // FIX (Bug 3): if RHS frame is absent AND indicator is "sma", compute on-the-fly
            // from the close frame of the same ticker.
            if (rhsDf == null && "sma".equalsIgnoreCase(rule.getValueIndicator())) {
                String closeKey = buildKey(ticker, "close", 0);
                ArrowDataFrame closeDf = frames.get(closeKey);
                if (closeDf == null) return false;
                int smaPeriod = (rule.getValueLookback() == null || rule.getValueLookback() <= 0)
                        ? 200 : rule.getValueLookback();
                Float computed = computeSmaPriorDay(closeDf, d, smaPeriod);
                if (computed == null || computed.isNaN()) return false;
                rhs = computed;
            } else {
                Float rhsVal = scalarFor(rhsDf, d);
                if (rhsVal == null || rhsVal.isNaN()) return false;
                rhs = rhsVal;
            }
        } else {
            rhs = rule.getValue();
        }

        return compare(lhs, rhs, rule.getOperator());
    }

    // ── month_in helper ──────────────────────────────────────────────────────

    /**
     * Returns true if d's month-of-year is in the configured month list.
     * Months are stored as a comma-separated string in rule.label, e.g. "5,6".
     * Fallback: if label is blank, treat rule.value as a single month number.
     */
    private static boolean evalMonthIn(RuleDto rule, LocalDate d) {
        int month = d.getMonthValue();
        String label = rule.getLabel();
        if (label != null && !label.isBlank()) {
            try {
                return Arrays.stream(label.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .mapToInt(Integer::parseInt)
                        .anyMatch(m -> m == month);
            } catch (NumberFormatException ignored) {}
        }
        // fallback: single month in numeric value field
        return (int) rule.getValue() == month;
    }

    // ── SPY SMA on-the-fly ───────────────────────────────────────────────────

    /**
     * Simple rolling SMA of the given lookback period over the single-column
     * close frame, ending at (and INCLUDING) {@code date}. The single
     * decide-yesterday / act-today lag is applied by the main loop's
     * freezeDays.contains(previousDate) gate, so this must be same-day:
     *   freeze-set(d)  ==  close[d] < SMA(d-period+1 .. d)
     * which, consumed one day later by the loop, reproduces the Python
     *   spy_close.iloc[idx-1] < spy_ma.iloc[idx-1].
     */
    private static Float computeSmaPriorDay(ArrowDataFrame closeDf, LocalDate date, int period) {
        if (closeDf == null) return null;
        String[] cols = closeDf.getTickerArray();
        if (cols == null || cols.length == 0) return null;
        String col = cols[0];

        List<LocalDate> dates = new ArrayList<>(closeDf.getDates());
        Collections.sort(dates);
        if (dates.isEmpty()) return null;

        int todayIdx = Collections.binarySearch(dates, date);
        if (todayIdx < 0) return null;                 // date not in frame
        int endIdx   = todayIdx;                        // include today's close (same-day SMA)
        int startIdx = Math.max(0, endIdx - period + 1);

        double sum = 0; int count = 0;
        for (int k = startIdx; k <= endIdx; k++) {
            LocalDate d = dates.get(k);
            try {
                if (closeDf.hasValue(d, col)) {
                    Float v = closeDf.getValue(d, col);
                    if (v != null && !v.isNaN()) { sum += v; count++; }
                }
            } catch (Exception ignored) {}
        }
        return count == 0 ? null : (float) (sum / count);
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    /**
     * Build the canonical frame-map key: {ticker}_{indicator}_{lookback}.
     *
     * Exception: when the indicator name already encodes the ticker
     * (e.g. indicator="vix_close" with ticker="vix"), the ticker prefix is
     * skipped so the lookup key matches what the middleware wrote
     * (vix_close_0, not vix_vix_close_0). Must stay symmetric with
     * BacktestContext.buildVolCutKey.
     */
    private static String buildKey(String ticker, String indicator, Integer lookback) {
        String ind = indicator == null ? "" : indicator.toLowerCase();
        int lb = (lookback == null) ? 0 : lookback;
        if (ticker == null || ticker.isBlank()) {
            return ind + "_" + lb;
        }
        String tk = ticker.toLowerCase();
        if (ind.startsWith(tk + "_")) return ind + "_" + lb;   // indicator already ticker-prefixed
        return tk + "_" + ind + "_" + lb;
    }
    

    /** Read the single-column scalar for a date (first column of the frame). */
    private static Float scalarFor(ArrowDataFrame df, LocalDate d) {
        if (df == null) return null;
        String[] cols = df.getTickerArray();
        if (cols == null || cols.length == 0) return null;
        try {
            return df.hasValue(d, cols[0]) ? df.getValue(d, cols[0]) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean compare(float v, float t, String op) {
        if (op == null) return false;
        return switch (op) {
            case ">"  -> v > t;
            case ">=" -> v >= t;
            case "<"  -> v < t;
            case "<=" -> v <= t;
            case "==" -> Float.compare(v, t) == 0;
            case "!=" -> Float.compare(v, t) != 0;
            default   -> false;
        };
    }
}