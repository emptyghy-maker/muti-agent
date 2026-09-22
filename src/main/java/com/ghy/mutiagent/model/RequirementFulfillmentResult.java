package com.ghy.mutiagent.model;

import com.ghy.mutiagent.model.requirement.FulfillmentStatus;
import com.ghy.mutiagent.model.requirement.RequirementOperator;
import com.ghy.mutiagent.model.requirement.RequirementScope;
import com.ghy.mutiagent.model.requirement.RequirementSubject;
import com.ghy.mutiagent.model.requirement.RequirementUnit;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** O2：单条需求的期望、实际结果与证据。 */
@Data
public class RequirementFulfillmentResult {
    private String requirementId;
    private int requirementRevision;
    private String hardness;
    private FulfillmentStatus status;
    private RequirementSubject subject;
    private RequirementOperator operator;
    private Integer expectedCount;
    private RequirementUnit unit;
    private RequirementScope scope;
    private Map<String, Integer> actual = new LinkedHashMap<>();
    private List<String> nodeIds = new ArrayList<>();
    private List<String> factIds = new ArrayList<>();
    private String reasonCode;
}
