package com.backtest.engine.dto.request;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleLeafNodeDto implements RuleNodeDto {
    private String type; // "rule"
    private String id;
    private RuleDto rule;
}

