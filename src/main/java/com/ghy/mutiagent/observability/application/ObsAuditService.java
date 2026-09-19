package com.ghy.mutiagent.observability.application;

import com.ghy.mutiagent.observability.persistence.ObsAuditRow;
import com.ghy.mutiagent.observability.persistence.ObsAuditRowMapper;
import com.ghy.mutiagent.security.AuthenticatedUser;

/** 管理动作审计（ADMIN 跨用户查询/导入/导出）；写入失败不阻断主流程 */
public final class ObsAuditService {

    private final ObsAuditRowMapper mapper;

    public ObsAuditService(ObsAuditRowMapper mapper) {
        this.mapper = mapper;
    }

    public void record(AuthenticatedUser actor, String action, String targetType,
                       String targetId, String detail) {
        if (actor == null) {
            return;
        }
        try {
            ObsAuditRow row = new ObsAuditRow();
            row.setActorId(actor.id());
            row.setActorUsername(actor.username());
            row.setAction(action);
            row.setTargetType(targetType);
            row.setTargetId(targetId);
            row.setDetail(detail == null ? null
                    : detail.length() > 1024 ? detail.substring(0, 1024) : detail);
            mapper.insert(row);
        } catch (RuntimeException ignored) {
        }
    }
}
