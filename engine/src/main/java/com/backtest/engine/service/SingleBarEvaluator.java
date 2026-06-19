package com.backtest.engine.service;

import com.backtest.engine.dto.request.ExecutionStepRequestDto;
import com.backtest.engine.dto.response.SingleBarSignalsResponseDto;

/**
 * Patch 29: contract for the single-bar execution evaluator.
 *
 * Phase B architecture: stateless evaluation of one bar (the last bar
 * in priceData = the data date). NO day-loop. NO portfolio simulation
 * across history. Uses signalsForTheDayV1 once for the last bar, then
 * shapes the output into the four lists the middleware needs.
 *
 * Implementation: SingleBarEvaluatorImpl.
 */
public interface SingleBarEvaluator {

    SingleBarSignalsResponseDto evaluate(ExecutionStepRequestDto request);
}