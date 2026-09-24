package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.repository.entity.Hotel;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 ItineraryAgent 的“分天 + 顺序 + 餐次”决策补成可排程骨架。
 * Agent 不再生成交通、酒店、时间和展示文案；这里确定性补齐，避免把格式劳动交给模型。
 */
public final class ItineraryDecisionSkeleton {

    private ItineraryDecisionSkeleton() {
    }

    public static void complete(ItineraryPlan plan, List<Hotel> hotels, boolean noHotel) {
        if (plan == null || plan.getDays() == null) {
            return;
        }
        Hotel hotel = noHotel || hotels == null || hotels.isEmpty() ? null : hotels.get(0);
        int lastDay = plan.getDays().size() - 1;
        for (int i = 0; i < plan.getDays().size(); i++) {
            DailyPlan day = plan.getDays().get(i);
            if (day == null) {
                continue;
            }
            List<PlanNode> nodes = day.getNodes() == null
                    ? new ArrayList<>() : new ArrayList<>(day.getNodes());
            fillDecisionNotes(nodes);
            fillTheme(day, nodes);

            if (hotel == null) {
                prependTransportIfMissing(nodes, i == 0 ? "抵达" : "启程");
                appendTransportIfMissing(nodes, "返程");
            } else {
                if (i == 0) {
                    prependTransportIfMissing(nodes, "抵达");
                }
                addHotelStartIfMissing(nodes, hotel);
                if (i == lastDay) {
                    appendTransportIfMissing(nodes, "返程");
                } else {
                    addHotelEndIfMissing(nodes, hotel);
                }
            }
            day.setNodes(nodes);
        }
    }

    private static void fillDecisionNotes(List<PlanNode> nodes) {
        for (PlanNode node : nodes) {
            if (node == null) {
                continue;
            }
            if ("attraction".equals(node.getType()) && (node.getNote() == null || node.getNote().isBlank())) {
                node.setNote("游览");
            }
        }
    }

    private static void fillTheme(DailyPlan day, List<PlanNode> nodes) {
        if (day.getTheme() != null && !day.getTheme().isBlank()) {
            return;
        }
        List<String> names = nodes.stream()
                .filter(n -> n != null && "attraction".equals(n.getType()))
                .map(PlanNode::getName)
                .filter(n -> n != null && !n.isBlank())
                .limit(2)
                .toList();
        day.setTheme(names.isEmpty() ? "第" + day.getDayIndex() + "天行程" : String.join("·", names));
    }

    private static void prependTransportIfMissing(List<PlanNode> nodes, String note) {
        if (!nodes.isEmpty() && "transport".equals(nodes.get(0).getType())) {
            return;
        }
        nodes.add(0, node("transport", null, "交通节点", note));
    }

    private static void appendTransportIfMissing(List<PlanNode> nodes, String note) {
        if (!nodes.isEmpty() && "transport".equals(nodes.get(nodes.size() - 1).getType())) {
            return;
        }
        nodes.add(node("transport", null, "交通节点", note));
    }

    private static void addHotelStartIfMissing(List<PlanNode> nodes, Hotel hotel) {
        int index = !nodes.isEmpty() && "transport".equals(nodes.get(0).getType()) ? 1 : 0;
        if (index < nodes.size() && "hotel".equals(nodes.get(index).getType())) {
            return;
        }
        nodes.add(index, node("hotel", hotel.getId(), hotel.getName(), "从酒店出发"));
    }

    private static void addHotelEndIfMissing(List<PlanNode> nodes, Hotel hotel) {
        int index = !nodes.isEmpty() && "transport".equals(nodes.get(nodes.size() - 1).getType())
                ? nodes.size() - 1 : nodes.size();
        if (index > 0 && "hotel".equals(nodes.get(index - 1).getType())) {
            return;
        }
        nodes.add(index, node("hotel", hotel.getId(), hotel.getName(), "返回酒店"));
    }

    private static PlanNode node(String type, Long placeId, String name, String note) {
        PlanNode node = new PlanNode();
        node.setType(type);
        node.setPlaceId(placeId);
        node.setName(name);
        node.setNote(note);
        return node;
    }
}
