package com.ghy.mutiagent.model;

/**
 * S09 白名单补丁操作：模型只能提出 REMOVE / REPLACE_PLACE，服务端逐项验证。
 * nodeId 为稳定节点身份（PlanNodeRef 格式，如 d2-a2）；
 * REPLACE_PLACE 的 placeKey 为类型化地点键（ATTRACTION:99），必须是召回候选池中的证据。
 */
public class PatchOperation {

    public static final String OP_REMOVE = "REMOVE";
    public static final String OP_REPLACE_PLACE = "REPLACE_PLACE";

    private String op;
    private String nodeId;
    /** 仅 REPLACE_PLACE：替换目标地点键，如 ATTRACTION:99 */
    private String placeKey;

    public String getOp() {
        return op;
    }

    public void setOp(String op) {
        this.op = op;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getPlaceKey() {
        return placeKey;
    }

    public void setPlaceKey(String placeKey) {
        this.placeKey = placeKey;
    }
}
