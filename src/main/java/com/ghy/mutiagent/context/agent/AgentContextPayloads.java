package com.ghy.mutiagent.context.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghy.mutiagent.context.optimization.FactContext;
import com.ghy.mutiagent.context.optimization.NormalizedRequirement;

import java.util.List;
import java.util.Map;

/** 角色专属 DTO 集中定义；每个 DTO 只包含该角色完成当前任务需要的数据。 */
public final class AgentContextPayloads {
    private AgentContextPayloads() { }

    public record LocationContext(String anchorName, Double lng, Double lat, double radiusKm,
                                  List<String> scopes, String status) { }

    public record PreferenceContext(JsonNode confirmedPreference, String currentField,
                                    String currentQuestion, String currentMessage) { }

    public record RequirementContext(String destinationName, JsonNode confirmedPreference,
                                     List<NormalizedRequirement> activeRequirements,
                                     List<String> unresolvedRequirements,
                                     Map<String, String> fieldNotes,
                                     LocationContext location,
                                     List<String> requestedAnalysisDimensions) { }

    public record CandidateContext(String channel, int targetCount, String userRequest,
                                   JsonNode relevantPreference,
                                   List<NormalizedRequirement> applicableRequirements,
                                   LocationContext location, List<String> lockedPlaceKeys,
                                   String snapshotId, int snapshotConstraintRevision,
                                   List<JsonNode> candidates) { }

    public record PlanningContext(JsonNode preference, List<JsonNode> attractions,
                                  List<JsonNode> restaurants, List<JsonNode> hotels,
                                  String rules, List<NormalizedRequirement> hardRequirements,
                                  String policyVersion, int policyRequirementRevision,
                                  Map<String, FactContext<?>> requiredFacts) { }

    public record RepairContext(int basePlanRevision, JsonNode legacyContext,
                                List<NormalizedRequirement> activeHardRequirements,
                                List<String> lockedNodeIds,
                                List<String> lockMappingReasonCodes) { }

    public record PatchContext(int basePlanRevision, JsonNode legacyContext,
                               List<String> allowedOperations,
                               List<String> lockedNodeIds,
                               List<String> recalledCandidateKeys) { }
}
