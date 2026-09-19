package com.ghy.mutiagent.model;

import lombok.Data;

import java.util.List;

/**
 * 候选确认请求：selectedIds 为空或 regenerate=true 表示「换一批」。
 */
@Data
public class ConfirmCandidatesRequest {
    private String sessionId;
    /** ATTRACTION / FOOD / HOTEL */
    private String candidateType;
    private List<Long> selectedIds;
    private Boolean regenerate;
}
