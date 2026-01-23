package com.backtest.engine.dto.request;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleGroupNodeDto implements RuleNodeDto {
    private String type; // "group"
    private String id;
    private Logic logic; // AND | OR
    private List<RuleNodeDto> children;

    public enum Logic { AND, OR }
}

