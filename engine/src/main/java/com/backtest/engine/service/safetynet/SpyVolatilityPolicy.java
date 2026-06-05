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
 * Stateful 4-escape volatility safety net (mirrors Python L_SMR_STATIC).
 *
 * <p>Three internal states:
 * <ul>
 *   <li>NORMAL  — trading allowed; watch vol.</li>
 *   <li>FROZEN  — vol crossed threshold; trading blocked, positions closed at entry.
 *                 Each day, check four escape routes.</li>
 *   <li>RESUMED — escape granted; trading allowed but still tracking peak vol.
 *                 Watch for re-arm.</li>
 * </ul>
 *
 * <p>Transitions:
 * <pre>
 *   NORMAL  → FROZEN   : vol >= threshold
 *   FROZEN  → NORMAL   : vol < threshold       (full reset)
 *   FROZEN  → RESUMED  : timeout, selloff, OR vol < peak_drop_pct × peak
 *   RESUMED → FROZEN   : vol > rearm_pct × peak  (re-arm — see close_on_rearm)
 *   RESUMED → NORMAL   : vol < threshold
 * </pre>
 *
 * <p>Param defaults match Python: vol_ticker=SPY, vol_lookback=5,
 * vol_threshold=0.025, timeout_days=20, selloff_pct=0.20,
 * peak_drop_pct=0.80, rearm_pct=0.80, close_on_rearm=false.
 *
 * <p>Operates at close phase only — open-phase evaluation is a noop.
 */
public class SpyVolatilityPolicy implements SafetyNetPolicy {

    // ── Params (from config.params with defaults) ────────────────────────
    private String  volTicker     = "SPY";
    private int     volLookback   = 5;
    private double  volThreshold  = 0.025;
    private int     timeoutDays   = 20;
    private double  selloffPct    = 0.20;
    private double  peakDropPct   = 0.80;
    private double  rearmPct      = 0.80;
    /** When true, re-arm closes existing positions (more aggressive).
     *  Default false matches Python's L_SMR_STATIC behaviour — positions
     *  exit normally via RSI/stop-loss instead. */
    private boolean closeOnRearm  = false;

    // ── Pre-computed market data (built in initialize) ───────────────────
    private Map<LocalDate, Double> closeByDate = Collections.emptyMap();
    private Map<LocalDate, Double> volByDate   = Collections.emptyMap();

    // ── State (evolves across days) ──────────────────────────────────────
    private boolean safetyHit       = false;
    private boolean breakSafety     = false;
    private double  peakVol         = 0;
    private int     daysInFreeze    = 0;
    private double  anchorClose     = 0;
    private boolean step2VolReduced = false;

    @Override
    public void initialize(SafetyNetItemDto config, SafetyNetInitContext initCtx) {
        Map<String, Object> params = config.getParams();
        if (params == null) params = Collections.emptyMap();

        volTicker    = strOrDefault(params.get("vol_ticker"),    "SPY");
        volLookback  = intOrDefault(params.get("vol_lookback"),  5);
        volThreshold = dblOrDefault(params.get("vol_threshold"), 0.025);
        timeoutDays  = intOrDefault(params.get("timeout_days"),  20);
        selloffPct   = dblOrDefault(params.get("selloff_pct"),   0.20);
        peakDropPct  = dblOrDefault(params.get("peak_drop_pct"), 0.80);
        rearmPct     = dblOrDefault(params.get("rearm_pct"),     0.80);
        closeOnRearm = boolOrDefault(params.get("close_on_rearm"), false);

     // Synthesise a two-leaf rule tree to load BOTH the closes (for anchor /
        // selloff math) AND the rolling-vol-close parquet (produced upstream
        // by middleware _compute_safety_net_indicators). The frame loader
        // returns both keyed for lookup.
        RuleGroupNodeDto syntheticTree = synthesiseFrames(volTicker, volLookback, initCtx);
        Map<String, ArrowDataFrame> frames = initCtx.getFrameLoader().apply(syntheticTree);

        String closeKey = volTicker.toLowerCase() + "_close_0";
        String volKey   = volTicker.toLowerCase() + "_rolling_vol_close_" + volLookback;
        ArrowDataFrame closeFrame = frames.get(closeKey);
        ArrowDataFrame volFrame   = frames.get(volKey);

        List<LocalDate> allDates = initCtx.getAllDates();
        closeByDate = new HashMap<>(allDates.size());
        volByDate   = new HashMap<>(allDates.size());

        if (closeFrame == null) {
            System.err.println("[spy-vol] no close frame for ticker '" + volTicker
                    + "' (expected key '" + closeKey + "'). Policy will be a no-op.");
            return;
        }
        if (volFrame == null) {
            System.err.println("[spy-vol] no rolling-vol frame for ticker '" + volTicker
                    + "' (expected key '" + volKey + "'). Did you re-save the strategy "
                    + "after the safety-net config? Policy will be a no-op.");
            return;
        }

     // Resolve the actual column name from each frame's own metadata
        // (single-ticker frames). Hardcoding the ticker string fails because
        // upstream CSVs use varying conventions ("spy" vs "SPY" vs "Close").
        String[] closeCols = closeFrame.getTickerArray();
        String[] volCols   = volFrame.getTickerArray();
        if (closeCols == null || closeCols.length == 0
                || volCols == null || volCols.length == 0) {
            System.err.println("[spy-vol] loaded frames have no columns. "
                    + "closeCols=" + java.util.Arrays.toString(closeCols)
                    + " volCols=" + java.util.Arrays.toString(volCols));
            return;
        }
        String closeCol = closeCols[0];
        String volCol   = volCols[0];

        // Read closes and vol from the loaded parquets — no in-policy math
        for (LocalDate d : allDates) {
            try {
                Float c = closeFrame.getValue(d, closeCol);
                if (c != null && !c.isNaN()) closeByDate.put(d, (double) c.floatValue());
            } catch (Exception ignore) { /* gap, skip */ }
            try {
                Float v = volFrame.getValue(d, volCol);
                if (v != null && !v.isNaN()) volByDate.put(d, (double) v.floatValue());
            } catch (Exception ignore) { /* gap, skip */ }
        }

        System.out.println("[spy-vol] initialised: ticker=" + volTicker
                + " lookback=" + volLookback + " threshold=" + volThreshold
                + " timeoutDays=" + timeoutDays + " closeOnRearm=" + closeOnRearm
                + " · closes=" + closeByDate.size() + " · vol points=" + volByDate.size());
    }

    @Override
    public SafetyNetDecision evaluateAtOpen(LocalDate date, LocalDate previousDate) {
        // SPY-vol operates only at close phase (matches Python ordering)
        return SafetyNetDecision.noop();
    }

    @Override
    public SafetyNetDecision evaluateAtClose(LocalDate date) {
        Double currentVol = volByDate.get(date);
        if (currentVol == null) return SafetyNetDecision.noop();   // not enough history yet

        // ── Path A: NORMAL → FROZEN (vol crosses threshold) ──────────────
        if (currentVol >= volThreshold && !safetyHit) {
            safetyHit       = true;
            breakSafety     = false;
            peakVol         = currentVol;
            daysInFreeze    = 0;
            step2VolReduced = false;
            Double anchor = closeByDate.get(date);
            anchorClose   = (anchor == null) ? 0 : anchor;
            return SafetyNetDecision.freeze("SPY vol " + r(currentVol) + " >= " + volThreshold);
        }

        if (safetyHit) {
            // ── Path B1: full reset (vol back below threshold) ──────────
            if (currentVol < volThreshold) {
                safetyHit       = false;
                breakSafety     = false;
                daysInFreeze    = 0;
                step2VolReduced = false;
                return SafetyNetDecision.resume("SPY vol " + r(currentVol) + " back below " + volThreshold);
            }

            if (!breakSafety) {
                // ── Path B2: still hot, check escape routes ─────────────
                daysInFreeze++;
                peakVol = Math.max(peakVol, currentVol);

                // B2a — timeout escape
                if (daysInFreeze >= timeoutDays) {
                    breakSafety = true;
                    return SafetyNetDecision.resume("Timeout " + timeoutDays + " days in freeze");
                }

                // B2b — selloff escape
                Double currentClose = closeByDate.get(date);
                if (currentClose != null && anchorClose > 0) {
                    double selloff = (currentClose - anchorClose) / anchorClose;
                    if (selloff < -selloffPct) {
                        breakSafety = true;
                        return SafetyNetDecision.resume("Selloff " + r(-selloff) + " >= " + selloffPct);
                    }
                }

                // B2c — vol-relief escape
                double safeVol = peakDropPct * peakVol;
                if (currentVol < safeVol) {
                    breakSafety     = true;
                    step2VolReduced = true;
                    return SafetyNetDecision.resume("Vol " + r(currentVol) + " < " + r(safeVol)
                            + " (" + (int)(peakDropPct*100) + "% of peak)");
                }
            } else {
                // ── Path B3: RESUMED, watch for re-arm ──────────────────
                peakVol = Math.max(peakVol, currentVol);
                double safeVol = rearmPct * peakVol;

                if (step2VolReduced) {
                    if (currentVol > safeVol) {
                        // Re-arm
                        breakSafety     = false;
                        peakVol         = currentVol;
                        daysInFreeze    = 0;
                        step2VolReduced = false;
                        String msg = "Re-arm: SPY vol " + r(currentVol) + " above " + r(safeVol);
                        // closeOnRearm controls whether positions get force-closed:
                        //   false (Python parity) → suspend only, positions exit normally
                        //   true  (more aggressive) → freeze + close all positions
                        return closeOnRearm
                                ? SafetyNetDecision.freeze(msg)
                                : SafetyNetDecision.suspend(msg);
                    }
                } else if (currentVol < safeVol * 0.9) {
                    // Hysteresis: mark "step-2 reduced" once vol drops further
                    step2VolReduced = true;
                }
            }
        }

        return SafetyNetDecision.noop();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Build a two-leaf rule tree that triggers the frame loader to load
     *  the given ticker's close prices AND the rolling-vol-close parquet.
     *  Both frames are then keyed for lookup in the returned frame map. */
    private static RuleGroupNodeDto synthesiseFrames(String ticker, int volLookback,
                                                      SafetyNetInitContext ctx) {
        Map<String, Object> closeLeaf = new HashMap<>();
        closeLeaf.put("indicator",     "close");
        closeLeaf.put("lookback",      0);
        closeLeaf.put("operator",      ">=");
        closeLeaf.put("value",         0);
        closeLeaf.put("regime_ticker", ticker);

        Map<String, Object> volLeaf = new HashMap<>();
        volLeaf.put("indicator",     "rolling_vol_close");
        volLeaf.put("lookback",      volLookback);
        volLeaf.put("operator",      ">=");
        volLeaf.put("value",         0);
        volLeaf.put("regime_ticker", ticker);

        Map<String, Object> closeNode = new HashMap<>();
        closeNode.put("type", "rule");
        closeNode.put("rule", closeLeaf);
        Map<String, Object> volNode = new HashMap<>();
        volNode.put("type", "rule");
        volNode.put("rule", volLeaf);

        Map<String, Object> tree = new HashMap<>();
        tree.put("type",     "group");
        tree.put("logic",    "AND");
        tree.put("children", List.of(closeNode, volNode));
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
    private static boolean boolOrDefault(Object o, boolean dflt) {
        if (o == null) return dflt;
        if (o instanceof Boolean) return (Boolean) o;
        String s = o.toString().trim().toLowerCase();
        if (s.equals("true")  || s.equals("1") || s.equals("yes")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no"))  return false;
        return dflt;
    }
}