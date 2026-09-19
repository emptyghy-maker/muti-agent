package com.ghy.mutiagent.observability.security;

/**
 * 查询作用域：普通用户只读本人运行；ADMIN 可跨用户（动作入 obs_audit）。
 * owner=null 的记录属于隔离域，任何查询都不可见。
 */
public record ObsScope(Long ownerId, boolean admin) {

    public static ObsScope of(Long actorId, String role) {
        return new ObsScope(actorId, "ADMIN".equals(role));
    }

    /** 是否可查看 ownerId 的 run：本人可见；ADMIN 可见任意非隔离域 run；隔离域一律不可见 */
    public boolean canSee(Long runOwnerId) {
        if (runOwnerId == null) {
            return false;
        }
        return admin || runOwnerId.equals(ownerId);
    }

    /** 是否可管理（写入/导出）：第一版仅 ADMIN */
    public boolean canManage() {
        return admin;
    }
}
