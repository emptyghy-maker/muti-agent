package com.ghy.mutiagent.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * S12 在线调优状态：提示词/权重变更必须走显式发布流程并留下变更记录。
 * 用户反馈不得直接触发变更（复核与评测数据只进入离线流程）。
 * 步骤 2 验收：反馈提交后两个计数保持 0。
 */
public class OnlineTuningState {

    private final AtomicInteger promptChanges = new AtomicInteger();
    private final AtomicInteger weightChanges = new AtomicInteger();

    public void recordPromptChange() {
        promptChanges.incrementAndGet();
    }

    public void recordWeightChange() {
        weightChanges.incrementAndGet();
    }

    public int promptChangeCount() {
        return promptChanges.get();
    }

    public int weightChangeCount() {
        return weightChanges.get();
    }
}
