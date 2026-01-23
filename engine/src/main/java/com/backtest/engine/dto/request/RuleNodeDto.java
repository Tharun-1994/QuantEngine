package com.backtest.engine.dto.request;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = RuleLeafNodeDto.class, name = "rule"),
        @JsonSubTypes.Type(value = RuleGroupNodeDto.class, name = "group")
})
public interface RuleNodeDto {
    String getType();
    String getId();
}

