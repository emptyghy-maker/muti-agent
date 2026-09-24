package com.ghy.mutiagent.context.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ghy.mutiagent.context.baseline.ContextSizeEstimator;
import com.ghy.mutiagent.context.optimization.CandidatePromptProjector;
import com.ghy.mutiagent.context.optimization.ContextReductionReport;
import com.ghy.mutiagent.context.optimization.FactContext;
import com.ghy.mutiagent.context.optimization.NormalizedRequirement;
import com.ghy.mutiagent.context.optimization.RequirementContextNormalizer;
import com.ghy.mutiagent.model.CandidateSnapshot;
import com.ghy.mutiagent.model.LocationConstraint;
import com.ghy.mutiagent.model.ResolvedPlanningPolicy;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 第三、第四阶段的统一接线点。Builder 只读 TravelState，不调用模型、数据库、Redis 或外部服务。
 * 默认影子模式；只有显式列入 rollout-roles 的角色才会改变模型入参。
 */
@Component
public class AgentContextRuntime {

    public static final String SCHEMA_VERSION = "agent-context-v1";

    private final ObjectMapper mapper;
    private final AgentContextSerializer serializer;
    private final AgentContextShadowComparator comparator;
    private final AgentContextProperties properties;
    private final RequirementContextNormalizer normalizer;
    private final CandidatePromptProjector candidateProjector;

    public AgentContextRuntime(ObjectMapper mapper, AgentContextSerializer serializer,
                               AgentContextShadowComparator comparator,
                               AgentContextProperties properties,
                               RequirementContextNormalizer normalizer,
                               CandidatePromptProjector candidateProjector) {
        this.mapper = mapper;
        this.serializer = serializer;
        this.comparator = comparator;
        this.properties = properties;
        this.normalizer = normalizer;
        this.candidateProjector = candidateProjector;
    }

    public boolean isActive(AgentContextRole role) {
        return properties.isActive(role);
    }

    public AgentContextInvocation preference(TravelState state, String turnId, String preferenceJson,
                                             String currentField, String currentQuestion, String message) {
        JsonNode preference = read(preferenceJson);
        var payload = new AgentContextPayloads.PreferenceContext(preference, blank(currentField),
                blank(currentQuestion), blank(message));
        Map<String, String> legacy = orderedArgs("preference", preferenceJson,
                "currentField", blank(currentField), "question", blank(currentQuestion), "message", blank(message));
        return finish(state, turnId, null, AgentContextRole.PREFERENCE, null, payload, legacy, legacy, null);
    }

    public AgentContextInvocation requirement(TravelState state, String turnId, String preferenceJson) {
        List<NormalizedRequirement> requirements = normalizer.normalize(state.getRequirementSnapshot());
        TravelPreference preference = state.getPreference();
        var payload = new AgentContextPayloads.RequirementContext(state.getDestinationName(), read(preferenceJson),
                requirements, normalizer.unresolved(state.getRequirementSnapshot()),
                preference == null || preference.getFieldNotes() == null
                        ? Map.of() : java.util.Collections.unmodifiableMap(
                        new LinkedHashMap<>(preference.getFieldNotes())),
                location(state.getLocationConstraint()),
                List.of("workflow", "agentPath", "weights", "needTags"));
        Map<String, String> legacy = orderedArgs("preference", preferenceJson);
        Map<String, String> enriched = orderedArgs("preference", serializer.serialize(payload));
        return finish(state, turnId, null, AgentContextRole.REQUIREMENT, null, payload, legacy, enriched, null);
    }

    public AgentContextInvocation candidate(TravelState state, AgentContextRole role,
                                            String poolJson, String preferenceJson, int targetCount) {
        if (role != AgentContextRole.ATTRACTION && role != AgentContextRole.FOOD
                && role != AgentContextRole.HOTEL) {
            throw new AgentContextBuildException("INVALID_CANDIDATE_ROLE");
        }
        ArrayNode pool = array(poolJson);
        List<JsonNode> source = new ArrayList<>();
        pool.forEach(source::add);
        Set<String> protectedKeys = protectedKeys(state, role);
        List<NormalizedRequirement> requirements = applicableRequirements(
                normalizer.normalize(state.getRequirementSnapshot()), role);
        int beforeTokens = ContextSizeEstimator.estimateTokens(poolJson);
        CandidatePromptProjector.Projection reduced = candidateProjector.project(role, source,
                Math.max(properties.candidateLimit(role), targetCount), protectedKeys, properties.getPolicyVersion(),
                requirements.stream().map(NormalizedRequirement::requirementId)
                        .filter(id -> id != null && !id.isBlank()).toList(), beforeTokens);
        CandidateSnapshot snapshot = state.getCandidateSnapshots() == null
                ? null : state.getCandidateSnapshots().get(role.name());
        String snapshotId = snapshot == null ? null : snapshot.getSnapshotId();
        int snapshotRevision = snapshot == null ? state.getConstraintRevision() : snapshot.getConstraintRevision();
        var payload = new AgentContextPayloads.CandidateContext(role.name(), targetCount,
                channelRequest(state, role), read(preferenceJson), requirements,
                location(state.getLocationConstraint()), List.copyOf(protectedKeys), snapshotId,
                snapshotRevision, List.copyOf(source));

        String optimizedPool = serializer.serialize(reduced.candidates());
        Map<String, String> legacy = orderedArgs("pool", poolJson, "preference", preferenceJson,
                "target", String.valueOf(targetCount));
        Map<String, String> enriched = orderedArgs("pool", optimizedPool, "preference", preferenceJson,
                "target", String.valueOf(targetCount));
        ContextReductionReport report = new ContextReductionReport(role.name(), properties.getPolicyVersion(),
                beforeTokens, ContextSizeEstimator.estimateTokens(optimizedPool), reduced.beforeCount(),
                reduced.afterCount(), reduced.protectedKeys(),
                reduced.beforeCount() == reduced.afterCount() ? Map.of()
                        : Map.of("candidate", reduced.beforeCount() - reduced.afterCount()),
                reduced.retainedRequirementIds(), reduced.reasonCodes(),
                optimizedPool.getBytes(StandardCharsets.UTF_8).length > properties.getMaxSectionBytes());
        return finish(state, null, null, role, snapshotId, payload, legacy, enriched, report);
    }

    public AgentContextInvocation planning(TravelState state, String operationId,
                                           String preferenceJson, String attractionJson,
                                           String restaurantJson, String hotelJson, String rules,
                                           List<Map<String, Object>> restaurantFacts) {
        verifyPolicyRevision(state);
        List<NormalizedRequirement> hard = normalizer.normalize(state.getRequirementSnapshot()).stream()
                .filter(r -> "HARD".equals(r.hardness())).toList();
        String enrichedRestaurantJson = enrichRestaurants(restaurantJson, restaurantFacts);
        String deduplicatedRules = deduplicateRules(state, rules);
        Map<String, FactContext<?>> facts = planningFacts(restaurantFacts);
        ResolvedPlanningPolicy policy = state.getResolvedPlanningPolicy();
        var payload = new AgentContextPayloads.PlanningContext(read(preferenceJson), values(attractionJson),
                values(enrichedRestaurantJson), values(hotelJson), deduplicatedRules, hard,
                policy == null ? null : policy.getPolicyVersion(),
                policy == null ? 0 : policy.getRequirementSnapshotRevision(), facts);
        Map<String, String> legacy = orderedArgs("preference", preferenceJson, "attractions", attractionJson,
                "restaurants", restaurantJson, "hotel", hotelJson, "rules", rules);
        Map<String, String> enriched = orderedArgs("preference", preferenceJson, "attractions", attractionJson,
                "restaurants", enrichedRestaurantJson, "hotel", hotelJson, "rules", deduplicatedRules);
        int before = estimate(legacy);
        int after = estimate(enriched);
        List<String> reasons = new ArrayList<>();
        if (!restaurantJson.equals(enrichedRestaurantJson)) reasons.add("RESTAURANT_FACTS_ADDED");
        if (!rules.equals(deduplicatedRules)) reasons.add("DUPLICATE_REQUIREMENT_REMOVED");
        ContextReductionReport report = new ContextReductionReport(AgentContextRole.PLANNING.name(),
                properties.getPolicyVersion(), before, after, values(attractionJson).size() + values(restaurantJson).size(),
                values(attractionJson).size() + values(enrichedRestaurantJson).size(), List.of(), Map.of(),
                hard.stream().map(NormalizedRequirement::requirementId).filter(id -> id != null).toList(),
                List.copyOf(reasons), serializer.serialize(enriched).getBytes(StandardCharsets.UTF_8).length
                > properties.getMaxSectionBytes());
        return finish(state, null, operationId, AgentContextRole.PLANNING, null, payload,
                legacy, enriched, report);
    }

    public AgentContextInvocation repair(TravelState state, String operationId, String legacyContext) {
        List<NormalizedRequirement> hard = normalizer.normalize(state.getRequirementSnapshot()).stream()
                .filter(r -> "HARD".equals(r.hardness())).toList();
        JsonNode parsed = read(legacyContext);
        List<String> lockedNodes = stringList(parsed.path("lockedNodeIds"));
        List<String> reasonCodes = lockedNodes.isEmpty() ? List.of("LOCK_MAPPING_UNAVAILABLE") : List.of();
        var payload = new AgentContextPayloads.RepairContext(state.getPlanRevision(), parsed, hard,
                lockedNodes, reasonCodes);
        Map<String, String> args = orderedArgs("repairContext", legacyContext);
        return finish(state, null, operationId, AgentContextRole.REPAIR, null, payload, args, args, null);
    }

    public AgentContextInvocation patch(TravelState state, String legacyContext) {
        JsonNode parsed = read(legacyContext);
        var payload = new AgentContextPayloads.PatchContext(parsed.path("baseRevision").asInt(state.getPlanRevision()),
                parsed, List.of("REMOVE", "REPLACE_PLACE"),
                stringList(parsed.path("lockedNodeIds")), stringList(parsed.path("recalledCandidateKeys")));
        Map<String, String> args = orderedArgs("patchContext", legacyContext);
        return finish(state, null, null, AgentContextRole.PATCH, null, payload, args, args, null);
    }

    private AgentContextInvocation finish(TravelState state, String turnId, String operationId,
                                          AgentContextRole role, String snapshotId, Object payload,
                                          Map<String, String> legacy, Map<String, String> enriched,
                                          ContextReductionReport report) {
        AgentContextMode mode = properties.modeFor(role);
        Map<String, String> actual = mode == AgentContextMode.ENRICHED_ROLLOUT ? enriched : legacy;
        String prompt = serializer.serialize(actual);
        if (mode == AgentContextMode.ENRICHED_ROLLOUT
                && prompt.getBytes(StandardCharsets.UTF_8).length > properties.getMaxSectionBytes()) {
            throw new AgentContextBuildException("CONTEXT_REQUIRED_SECTION_OVER_BUDGET");
        }
        int requirementRevision = state.getRequirementSnapshot() == null ? 0
                : state.getRequirementSnapshot().getRevision();
        AgentContextEnvelope<Object> envelope = new AgentContextEnvelope<>(UUID.randomUUID().toString(), role,
                SCHEMA_VERSION, state.getSessionId(), turnId, operationId, requirementRevision,
                state.getConstraintRevision(), state.getPlanRevision(), snapshotId, payload);
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("role", role);
        semantic.put("schemaVersion", SCHEMA_VERSION);
        semantic.put("requirementRevision", requirementRevision);
        semantic.put("constraintRevision", state.getConstraintRevision());
        semantic.put("planRevision", state.getPlanRevision());
        semantic.put("candidateSnapshotId", snapshotId);
        semantic.put("payload", payload);
        String legacyText = serializer.serialize(legacy);
        String enrichedText = serializer.serialize(enriched);
        return new AgentContextInvocation(role, mode, SCHEMA_VERSION, envelope, actual,
                serializer.semanticHash(semantic), serializer.hashSerialized(prompt),
                comparator.compare(legacyText, enrichedText), report);
    }

    private void verifyPolicyRevision(TravelState state) {
        if (state.getRequirementSnapshot() == null || state.getResolvedPlanningPolicy() == null) return;
        if (state.getRequirementSnapshot().getRevision()
                != state.getResolvedPlanningPolicy().getRequirementSnapshotRevision()) {
            throw new AgentContextBuildException("REVISION_CONFLICT");
        }
    }

    private String enrichRestaurants(String legacyJson, List<Map<String, Object>> facts) {
        if (facts == null || facts.isEmpty()) return legacyJson;
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> fact : facts) {
            Object id = fact.get("id");
            if (id != null) byId.put(String.valueOf(id), fact);
        }
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : values(legacyJson)) {
            ObjectNode copy = item.isObject() ? ((ObjectNode) item).deepCopy() : mapper.createObjectNode();
            Map<String, Object> fact = byId.get(item.path("id").asText());
            if (fact != null) {
                putFact(copy, fact, "avgPrice");
                putFact(copy, fact, "businessHours");
                putFact(copy, fact, "address");
                putFact(copy, fact, "lng");
                putFact(copy, fact, "lat");
                putFact(copy, fact, "source");
                copy.put("businessHoursStatus", fact.get("businessHours") == null ? "UNKNOWN" : "VERIFIED");
            }
            result.add(copy);
        }
        return serializer.serialize(result);
    }

    private void putFact(ObjectNode target, Map<String, Object> source, String key) {
        if (source.containsKey(key) && source.get(key) != null) target.set(key, mapper.valueToTree(source.get(key)));
    }

    private Map<String, FactContext<?>> planningFacts(List<Map<String, Object>> restaurants) {
        if (restaurants == null || restaurants.isEmpty()) return Map.of();
        Map<String, FactContext<?>> out = new LinkedHashMap<>();
        for (Map<String, Object> restaurant : restaurants) {
            String id = String.valueOf(restaurant.get("id"));
            Object hours = restaurant.get("businessHours");
            out.put("FOOD:" + id + ":businessHours", hours == null
                    ? FactContext.unknown("businessHours", "BUSINESS_HOURS_MISSING")
                    : FactContext.known("businessHours", hours,
                    restaurant.get("source") == null ? "UNKNOWN_SOURCE"
                            : String.valueOf(restaurant.get("source"))));
        }
        return Map.copyOf(out);
    }

    private String deduplicateRules(TravelState state, String rules) {
        if (rules == null || rules.isBlank()) return blank(rules);
        String special = state.getPreference() == null ? null : state.getPreference().getSpecialRequests();
        String extra = state.getExtraRequest();
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        for (String line : rules.split("\\R")) {
            String normalized = line.trim().replaceAll("\\s+", " ");
            if (normalized.isEmpty()) continue;
            if (special != null && !special.isBlank() && special.trim().equals(extra == null ? null : extra.trim())
                    && normalized.startsWith("- 选点补充要求：")) {
                continue;
            }
            if (seen.add(normalized)) out.append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private Set<String> protectedKeys(TravelState state, AgentContextRole role) {
        Set<String> out = new LinkedHashSet<>();
        if (state.getLockedSelection() != null && state.getLockedSelection().getOrderedKeys() != null) {
            for (String key : state.getLockedSelection().getOrderedKeys()) {
                if (key != null && key.startsWith(role.name() + ":")) out.add(key);
            }
        }
        List<Long> selected = switch (role) {
            case ATTRACTION -> state.getSelectedAttractionIds();
            case FOOD -> state.getSelectedFoodIds();
            case HOTEL -> state.getSelectedHotelIds();
            default -> List.of();
        };
        if (selected != null) for (Long id : selected) if (id != null) out.add(role.name() + ":" + id);
        return out;
    }

    private List<NormalizedRequirement> applicableRequirements(List<NormalizedRequirement> all,
                                                               AgentContextRole role) {
        if (all.isEmpty()) return all;
        return all.stream().filter(r -> {
            String subject = r.subject() == null ? "" : r.subject().toUpperCase();
            return switch (role) {
                case FOOD -> subject.contains("FOOD") || subject.contains("MEAL")
                        || subject.contains("LUNCH") || subject.contains("DINNER")
                        || subject.contains("SNACK") || subject.contains("DISH") || subject.contains("BUDGET")
                        || subject.contains("LOCATION") || subject.isBlank();
                case HOTEL -> subject.contains("HOTEL") || subject.contains("BUDGET")
                        || subject.contains("LOCATION") || subject.isBlank();
                case ATTRACTION -> !subject.contains("FOOD") && !subject.contains("MEAL")
                        && !subject.contains("LUNCH") && !subject.contains("DINNER")
                        && !subject.contains("SNACK") && !subject.contains("HOTEL");
                default -> true;
            };
        }).toList();
    }

    private static String channelRequest(TravelState state, AgentContextRole role) {
        String channel = role.name();
        String own = state.channelRequestOf(channel);
        return own == null || own.isBlank() ? state.getExtraRequest() : own;
    }

    private static AgentContextPayloads.LocationContext location(LocationConstraint value) {
        if (value == null) return null;
        return new AgentContextPayloads.LocationContext(value.getAnchorName(), value.getLng(), value.getLat(),
                value.getRadiusKm(), value.getScopes() == null ? List.of() : List.copyOf(value.getScopes()),
                value.getStatus());
    }

    private JsonNode read(String json) {
        if (json == null || json.isBlank()) return mapper.nullNode();
        try { return mapper.readTree(json); }
        catch (Exception e) { throw new AgentContextBuildException("CONTEXT_JSON_INVALID", e); }
    }

    private ArrayNode array(String json) {
        JsonNode node = read(json);
        if (!node.isArray()) throw new AgentContextBuildException("CONTEXT_CANDIDATE_POOL_NOT_ARRAY");
        return (ArrayNode) node;
    }

    private List<JsonNode> values(String json) {
        JsonNode node = read(json);
        if (!node.isArray()) return List.of();
        List<JsonNode> out = new ArrayList<>();
        node.forEach(out::add);
        return List.copyOf(out);
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        node.forEach(v -> out.add(v.asText()));
        return List.copyOf(out);
    }

    private static int estimate(Map<String, String> args) {
        int total = 0;
        for (String value : args.values()) total += ContextSizeEstimator.estimateTokens(value);
        return total;
    }

    private static Map<String, String> orderedArgs(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) out.put(pairs[i], blank(pairs[i + 1]));
        return out;
    }

    private static String blank(String value) { return value == null ? "" : value; }
}
