package com.ghy.mutiagent.model;

import java.util.List;

/**
 * S09 调整意图（模型提出、服务端验证）：范围（targetDays）+ 白名单操作列表。
 * baseRevision 为模型看到的基础版本，提交前由服务端与当前版本核对（并发时由条件归档仲裁）。
 * 模型不得操作 ownerId、版本号、operation 状态或任意 JSON 路径。
 */
public class AdjustPatchIntent {

    private int baseRevision;
    private List<Integer> targetDays;
    private List<PatchOperation> operations;

    public int getBaseRevision() {
        return baseRevision;
    }

    public void setBaseRevision(int baseRevision) {
        this.baseRevision = baseRevision;
    }

    public List<Integer> getTargetDays() {
        return targetDays;
    }

    public void setTargetDays(List<Integer> targetDays) {
        this.targetDays = targetDays;
    }

    public List<PatchOperation> getOperations() {
        return operations;
    }

    public void setOperations(List<PatchOperation> operations) {
        this.operations = operations;
    }
}
