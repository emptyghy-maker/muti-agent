package com.ghy.mutiagent.service;

import com.ghy.mutiagent.common.JsonUtils;
import com.ghy.mutiagent.model.AttractionCandidate;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.RequirementAnalysis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 统一解析层测试（docs/agent-io-spec.md 规范的实现验证）：
 * 覆盖标准信封、模型自创键名、旧版分组、advice 优先级、嵌套兜底。
 */
class AgentOutputParserTest {

    @Test
    void 标准信封items按景点字段提取() {
        JsonNode node = JsonUtils.readTree(
                "{\"items\":[{\"attractionId\":24,\"feature\":\"小桥流水\",\"why\":\"适合拍照\"},"
                        + "{\"attractionId\":25,\"why\":\"夜景迷人\"}],\"advice\":\"建议夜游\"}");
        ArrayNode items = AgentOutputParser.extractItems(node, AgentOutputParser::attractionItem, "attractionId", "id");
        List<AttractionCandidate> list = JsonUtils.parseList(items.toString(), AttractionCandidate.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getAttractionId()).isEqualTo(24L);
        assertThat(list.get(0).getWhy()).isEqualTo("适合拍照");
        assertThat(list.get(1).getAttractionId()).isEqualTo(25L);
        assertThat(AgentOutputParser.adviceOf(node)).isEqualTo("建议夜游");
    }

    @Test
    void 模型自创selectedXxx键名也能按内容识别() {
        JsonNode node = JsonUtils.readTree(
                "{\"selectedAttractions\":[{\"id\":24,\"reason\":\"核心推荐\"},{\"id\":26,\"reason\":\"夜景浪漫\"}],"
                        + "\"summary\":{\"budgetEstimate\":{\"note\":\"剩余预算充足\"}}}");
        ArrayNode items = AgentOutputParser.extractItems(node, AgentOutputParser::attractionItem, "attractionId", "id");
        List<AttractionCandidate> list = JsonUtils.parseList(items.toString(), AttractionCandidate.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getAttractionId()).isEqualTo(24L);
        assertThat(list.get(0).getWhy()).isEqualTo("核心推荐");
        assertThat(AgentOutputParser.adviceOf(node)).isEqualTo("剩余预算充足");
    }

    @Test
    void 美食recommendedRestaurants键名按内容识别() {
        JsonNode node = JsonUtils.readTree("{\"recommendedRestaurants\":[{\"id\":23},{\"id\":18}]}");
        ArrayNode items = AgentOutputParser.extractItems(node, AgentOutputParser::foodItem, "restaurantId", "id");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("restaurantId").asLong()).isEqualTo(23L);
        assertThat(items.get(1).path("restaurantId").asLong()).isEqualTo(18L);
    }

    @Test
    void 旧版分组美食输出仍能定位到内层restaurants() {
        JsonNode node = JsonUtils.readTree(
                "{\"items\":[{\"cuisine\":\"本地菜\",\"restaurants\":[{\"restaurantId\":23},{\"restaurantId\":16}]}]}");
        ArrayNode items = AgentOutputParser.extractItems(node, AgentOutputParser::foodItem, "restaurantId", "id");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("restaurantId").asLong()).isEqualTo(23L);
        assertThat(items.get(1).path("restaurantId").asLong()).isEqualTo(16L);
    }

    @Test
    void advice提取优先级固定() {
        JsonNode node = JsonUtils.readTree("{\"advice\":\"直接建议\",\"budgetAnalysis\":{\"note\":\"预算说明\"}}");
        assertThat(AgentOutputParser.adviceOf(node)).isEqualTo("直接建议");
        JsonNode node2 = JsonUtils.readTree("{\"budgetAnalysis\":{\"note\":\"预算说明\"}}");
        assertThat(AgentOutputParser.adviceOf(node2)).isEqualTo("预算说明");
        assertThat(AgentOutputParser.adviceOf(JsonUtils.readTree("{\"items\":[]}"))).isNull();
    }

    @Test
    void advice键被模型拼错时按ad前缀兜底() {
        JsonNode node = JsonUtils.readTree("{\"items\":[{\"restaurantId\":23}],\"adife\":\"建议文本\"}");
        assertThat(AgentOutputParser.adviceOf(node)).isEqualTo("建议文本");
    }

    @Test
    void 需求分析被包一层仍能映射() {
        JsonNode node = JsonUtils.readTree(
                "{\"result\":{\"mode\":\"agent\",\"focus\":[\"美食与氛围\"],\"brief\":\"围绕情侣约会挑选\","
                        + "\"needs\":{\"path\":2,\"cost\":3,\"sightseeing\":2,\"food\":5}}}");
        RequirementAnalysis a = AgentOutputParser.nestedRequirement(node);
        assertThat(a).isNotNull();
        assertThat(a.getMode()).isEqualTo("agent");
        assertThat(a.getFocus()).containsExactly("美食与氛围");
        assertThat(a.getBrief()).isEqualTo("围绕情侣约会挑选");
        assertThat(a.getNeeds().get("food")).isEqualTo(5);
    }

    @Test
    void 行程被包一层仍能解析days() {
        JsonNode node = JsonUtils.readTree(
                "{\"result\":{\"days\":[{\"dayIndex\":1,\"theme\":\"抵达\",\"nodes\":[]}]}}");
        ItineraryPlan plan = AgentOutputParser.nestedItinerary(node);
        assertThat(plan).isNotNull();
        assertThat(plan.getDays()).hasSize(1);
        assertThat(plan.getDays().get(0).getDayIndex()).isEqualTo(1);
    }

    // ==================== S03 严格 ID 契约 ====================

    @Test
    void 小数ID被拒绝不截断() {
        assertThat(AgentOutputParser.attractionItem(JsonUtils.readTree("{\"attractionId\":1.9}"))
                .path("attractionId").asLong()).isZero();
    }

    @Test
    void 浮点形式ID被拒绝() {
        assertThat(AgentOutputParser.attractionItem(JsonUtils.readTree("{\"attractionId\":1.0}"))
                .path("attractionId").asLong()).isZero();
    }

    @Test
    void 溢出ID被拒绝不映射到真实地点() {
        assertThat(AgentOutputParser.attractionItem(JsonUtils.readTree("{\"attractionId\":18446744073709551617}"))
                .path("attractionId").asLong()).isZero();
    }

    @Test
    void 字符串ID被拒绝() {
        assertThat(AgentOutputParser.attractionItem(JsonUtils.readTree("{\"attractionId\":\"1\"}"))
                .path("attractionId").asLong()).isZero();
    }

    @Test
    void 冲突别名明确拒绝() {
        assertThatThrownBy(() -> AgentOutputParser.attractionItem(
                JsonUtils.readTree("{\"attractionId\":1,\"id\":2}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
