package com.ghy.mutiagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 推荐与真实候选项之间的结构化引用。
 * 只由服务端根据已通过白名单与硬约束校验的候选快照生成。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CandidateAdviceRef {
    private String type;
    private Long id;
    private String name;
}
