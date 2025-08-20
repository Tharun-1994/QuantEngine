package com.backtest.engine.dto.request;

import java.time.LocalDate;
import java.util.List;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class ExitSignalsRequestDto {
	private LocalDate tradeDate;
	private List<String> exits;
	private String reasonForExit;
	private String exitTime;
}
