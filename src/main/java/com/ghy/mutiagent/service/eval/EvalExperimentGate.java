package com.ghy.mutiagent.service.eval;

import java.util.List;

/**
 * 实验启动门（手册 §3.2/§3.3）：数据集切分存在泄漏（同一 sourceGroup 跨集合）时，
 * 实验不得启动。正式 holdout 仅限发布验收阶段；反复据其调提示词后应新建后续 holdout。
 */
public final class EvalExperimentGate {

    private EvalExperimentGate() {
    }

    /** 切分一致性通过才允许启动实验（对照实验引擎启动前调用） */
    public static boolean canStart(List<DatasetSplits.SplitRow> rows) {
        return DatasetSplits.leakageCheck(rows) == null;
    }
}
