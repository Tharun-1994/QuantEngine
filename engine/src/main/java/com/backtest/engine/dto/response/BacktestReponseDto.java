package com.backtest.engine.dto.response;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.backtest.engine.dto.request.ExitSignalsRequestDto;
import com.backtest.engine.entity.EquityLog;
import com.backtest.engine.entity.TradeLog;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class BacktestReponseDto {
	private Map<String, TradeLog> tradeLogger;
	private Map<LocalDate, EquityLog> equityLogger;
}
