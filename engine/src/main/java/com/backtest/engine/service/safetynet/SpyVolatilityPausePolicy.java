package com.backtest.engine.service.safetynet;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.backtest.engine.dto.request.RuleGroupNodeDto;
import com.backtest.engine.dto.request.SafetyNetItemDto;
import com.backtest.engine.util.ArrowDataFrame;

/**
 * Simple pause-trading safety net based on relative SPY volatility.
 *
 * <p>Mirrors PULLBACK-style strategies' pause gate:
 * <pre>
 *   pause[T] = SPY_vol[T-1]  >  SPY_vol_median[T-1] * vol_multiple
 * </pre>
 *
 * <p>When the gate is true: SUSPEND new entries. Does NOT close existing
 * positions — they exit normally via RSI/IBS/max-time/delisting rules.
 *
 * <p>No state machine, no escape routes. Just a daily yes/no gate that
 * tracks suspended/not-suspended transitions and emits SUSPEND or RESUME
 * decisions accordingly.
 */
public class SpyVolatilityPausePolicy implements SafetyNetPolicy {

    private String volTicker          = "SPY";
    private int    volLookback        = 20;
    private int    volMedianLookback  = 252;
    private double volMultiple        = 2.0;

    private Map<LocalDate, Double> volByDate    = Collections.emptyMap();
    private Map<LocalDate, Double> medianByDate = Collections.emptyMap();

    /** Tracks whether we're currently in the paused state.
     *  Used to emit SUSPEND/RESUME only on state transitions (not every day). */
    private boolean paused = false;

    @Override
    public void initialize(SafetyNetItemDto config, SafetyNetInitContext initCtx) {
        Map<String, Object> params = config.getParams();
        if (params == null) params = Collections.emptyMap();

        volTicker         = strOrDefault(params.get("vol_ticker"),         "SPY");
        volLookback       = intOrDefault(params.get("vol_lookback"),       20);
        volMedianLookback = intOrDefault(params.get("vol_median_lookback"), 252);
        volMultiple       = dblOrDefault(params.get("vol_multiple"),       2.0);

        // Synthesise a tree referencing both the vol and the median frames.
        // Middleware will have written both parquets when sn_type=spy_volatility_pause.
        RuleGroupNodeDto syntheticTree = synthesiseFrames(volTicker, volLookback, initCtx);
        Map<String, ArrowDataFrame> frames = initCtx.getFrameLoader().apply(syntheticTree);

        String volKey    = volTicker.toLowerCase() + "_rolling_vol_close_" + volLookback;
        String medianKey = volTicker.toLowerCase() + "_rolling_vol_median_" + volLookback;
        ArrowDataFrame volFrame    = frames.get(volKey);
        ArrowDataFrame medianFrame = frames.get(medianKey);

        if (volFrame == null || medianFrame == null) {
            System.err.println("[spy-vol-pause] missing frames — vol=" + (volFrame!=null)
                    + " median=" + (medianFrame!=null) + ". Expected keys: " + volKey + ", " + medianKey
                    + ". Did you re-save the strategy after configuring the pause safety net? Policy will be a no-op.");
            return;
        }

        // Use the actual column names from each frame (avoid hardcoded ticker case)
        String[] volCols    = volFrame.getTickerArray();
        String[] medianCols = medianFrame.getTickerArray();
        if (volCols == null || volCols.length == 0 || medianCols == null || medianCols.length == 0) {
            System.err.println("[spy-vol-pause] frames have no columns. Policy will be a no-op.");
            return;
        }
        String volCol    = volCols[0];
        String medianCol = medianCols[0];

        List<LocalDate> allDates = initCtx.getAllDates();
        volByDate    = new HashMap<>(allDates.size());
        medianByDate = new HashMap<>(allDates.size());

        for (int i = 0; i + 1 < allDates.size(); i++) {
            LocalDate src  = allDates.get(i);       // value as of this date
            LocalDate slot = allDates.get(i + 1);   // stored under the next trading day (the .shift(1))
            try {
                Float v = volFrame.getValue(src, volCol);
                if (v != null && !v.isNaN()) volByDate.put(slot, (double) v.floatValue());
            } catch (Exception ignore) { /* gap */ }
            try {
                Float m = medianFrame.getValue(src, medianCol);
                if (m != null && !m.isNaN()) medianByDate.put(slot, (double) m.floatValue());
            } catch (Exception ignore) { /* gap */ }
        }

        System.out.println("[spy-vol-pause] initialised: ticker=" + volTicker
                + " volLookback=" + volLookback
                + " medianLookback=" + volMedianLookback
                + " multiple=" + volMultiple
                + " · vol points=" + volByDate.size()
                + " · median points=" + medianByDate.size());
    }

    /** Open phase: evaluate the gate based on the previous trading day's vol/median.
     *  Mirrors Python's pause = (vol.shift(1) > median.shift(1) * multiple). */
    @Override
    public SafetyNetDecision evaluateAtOpen(LocalDate date, LocalDate previousDate) {
        if (previousDate == null) return SafetyNetDecision.noop();

        Double vol    = volByDate.get(previousDate);
        Double median = medianByDate.get(previousDate);
        // Python parity: until both vol and median are computable (need ~272 trading
        // days of warmup), treat the safety net as ACTIVE — don't trade.
        if (vol == null || median == null) {
            if (!paused) {
                paused = true;
                return SafetyNetDecision.suspend("Warmup — median not yet available");
            }
            return SafetyNetDecision.noop();
        }

        boolean shouldPause = vol > median * volMultiple;

        if (shouldPause && !paused) {
            paused = true;
            return SafetyNetDecision.suspend(
                "SPY vol " + r(vol) + " > " + r(median * volMultiple)
                + " (" + r(median) + " × " + volMultiple + ")");
        }
        if (!shouldPause && paused) {
            paused = false;
            return SafetyNetDecision.resume("SPY vol " + r(vol) + " back below " + r(median * volMultiple));
        }
        return SafetyNetDecision.noop();
    }

    /** Close phase is a noop — this policy operates only at open. */
    @Override
    public SafetyNetDecision evaluateAtClose(LocalDate date) {
        return SafetyNetDecision.noop();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static RuleGroupNodeDto synthesiseFrames(String ticker, int volLookback,
            SafetyNetInitContext ctx) {
        Map<String, Object> volLeaf = new HashMap<>();
        volLeaf.put("indicator",     "rolling_vol_close");
        volLeaf.put("lookback",      volLookback);
        volLeaf.put("operator",      ">=");
        volLeaf.put("value",         0);
        volLeaf.put("regime_ticker", ticker);

        Map<String, Object> medianLeaf = new HashMap<>();
        medianLeaf.put("indicator",     "rolling_vol_median");
        medianLeaf.put("lookback",      volLookback);   // primary lookback is volLookback
        medianLeaf.put("operator",      ">=");
        medianLeaf.put("value",         0);
        medianLeaf.put("regime_ticker", ticker);


        Map<String, Object> volNode = new HashMap<>();
        volNode.put("type", "rule");
        volNode.put("rule", volLeaf);
        Map<String, Object> medianNode = new HashMap<>();
        medianNode.put("type", "rule");
        medianNode.put("rule", medianLeaf);

        Map<String, Object> tree = new HashMap<>();
        tree.put("type",     "group");
        tree.put("logic",    "AND");
        tree.put("children", List.of(volNode, medianNode));
        return ctx.getObjectMapper().convertValue(tree, RuleGroupNodeDto.class);
    }

    private static String r(double d) { return String.format("%.4f", d); }

    private static String strOrDefault(Object o, String dflt) {
        if (o == null) return dflt;
        String s = o.toString();
        return s.isBlank() ? dflt : s;
    }
    private static int intOrDefault(Object o, int dflt) {
        if (o == null) return dflt;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return dflt; }
    }
    private static double dblOrDefault(Object o, double dflt) {
        if (o == null) return dflt;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return dflt; }
    }
}