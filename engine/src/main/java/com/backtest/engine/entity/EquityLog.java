package com.backtest.engine.entity;

import java.util.Date;

import lombok.Data;

@Data
public class EquityLog {

	private float equityValue;
	private int dayEndUtility;
	private float dayEndUtilityValue;
	private float dailyDrawdown;
}
