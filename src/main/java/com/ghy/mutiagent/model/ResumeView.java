package com.ghy.mutiagent.model;

import com.ghy.mutiagent.model.enums.TravelStage;
import lombok.Data;

/**
 * 可恢复会话视图（断点恢复）：「继续上次规划」卡片与恢复分支的数据源。
 * activeOperationId 非空 = 有生成/调整操作进行中，恢复端应轮询该操作至终态；
 * 已结束（stage=DONE 且无进行中操作）或超出恢复窗口的会话不会返回本视图。
 */
@Data
public class ResumeView {
    private String sessionId;
    private Long destinationId;
    private String destinationName;
    private TravelStage stage;
    /** 进行中的操作（生成/调整）；null = 无进行中操作 */
    private String activeOperationId;
    /** 同步生成进行中（酒店确认触发）：恢复端应轮询会话快照直至 DONE，不重复提交 */
    private boolean generating;
    /** 最近活动时间（epoch 毫秒），用于展示「约 X 分钟前」 */
    private Long updatedAt;
}
