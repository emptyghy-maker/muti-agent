package com.ghy.mutiagent.context.turn;

import com.ghy.mutiagent.model.ConstraintEntry;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本轮输入的解释草案；本对象本身不修改 TravelState。 */
@Data
public class TurnInterpretation {
    private Map<String, String> preferenceUpdates = new LinkedHashMap<>();
    private List<ConstraintEntry> requirementChanges = new ArrayList<>();
    private List<String> skipChannels = new ArrayList<>();
    private List<String> unresolvedTexts = new ArrayList<>();
    private String consumedCurrentField;
    private String parserSource;
    private List<String> reasonCodes = new ArrayList<>();
    private boolean needsClarification;
}
