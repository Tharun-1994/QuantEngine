package com.backtest.engine.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.backtest.engine.entity.RuleCondition;

public class RuleParser {
	public static List<RuleCondition> parseEntryRules(String entryRules) {
		List<RuleCondition> conditions = new ArrayList<>();
		int pos = 0, len = entryRules.length();

		while (pos < len) {
			// 1) Skip any leading whitespace
			while (pos < len && Character.isWhitespace(entryRules.charAt(pos))) {
				pos++;
			}
			if (pos >= len)
				break;

			// 2) Read the indicator_lookback token (e.g. "rsi_14")
			int start = pos;
			while (pos < len && !Character.isWhitespace(entryRules.charAt(pos))) {
				pos++;
			}
			String indToken = entryRules.substring(start, pos);
			// split on the last underscore
			int us = indToken.lastIndexOf('_');
			String indicator = indToken.substring(0, us);
			String indicatorLookBack = indToken.substring(us + 1);

			// 3) Skip spaces, then read the operator (e.g. "<", ">=", "!=")
			while (pos < len && Character.isWhitespace(entryRules.charAt(pos)))
				pos++;
			start = pos;
			while (pos < len && !Character.isWhitespace(entryRules.charAt(pos)))
				pos++;
			String operator = entryRules.substring(start, pos);

			// 4) Skip spaces, then read the value (e.g. "10", "54")
			while (pos < len && Character.isWhitespace(entryRules.charAt(pos)))
				pos++;
			start = pos;
			while (pos < len && !Character.isWhitespace(entryRules.charAt(pos)))
				pos++;
			String value = entryRules.substring(start, pos);

			// 5) Skip spaces, then (if present) read the connector (e.g. "&&", "||")
			while (pos < len && Character.isWhitespace(entryRules.charAt(pos)))
				pos++;
			String connectOp = null;
			if (pos < len) {
				start = pos;
				while (pos < len && !Character.isWhitespace(entryRules.charAt(pos)))
					pos++;
				connectOp = entryRules.substring(start, pos);
			}

			// 6) Build and add the RuleCondition
			conditions.add(RuleCondition.builder().indicator(indicator).indicatorLookBack(Integer.valueOf(indicatorLookBack))
					.operator(operator).value(Double.valueOf(value)).connectOperator(connectOp).build());

		}

		return conditions;
	}

	public static String buildParquetFileName(String indicator, int i) {
		return String.format("%s_%s.parquet", indicator, i);
	}
	
	
	
}
