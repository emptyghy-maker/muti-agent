package com.ghy.mutiagent.model;

import lombok.Data;

/** 受理生成操作请求（S06-B）：requestId 幂等键 + expectedRevision 并发栅栏 */
@Data
public class GenerateOpRequest {
    private String sessionId;
    private String requestId;
    private Long expectedRevision;
}
