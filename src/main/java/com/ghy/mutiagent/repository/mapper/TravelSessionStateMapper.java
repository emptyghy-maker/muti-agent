package com.ghy.mutiagent.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ghy.mutiagent.repository.entity.TravelSessionState;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 会话权威快照 Mapper（S06-B）。条件更新全部带 revision/占用守卫：
 * 过期执行者或并发修改影响 0 行即失败，由调用方回滚。
 */
public interface TravelSessionStateMapper extends BaseMapper<TravelSessionState> {

    /** 锁定会话行：同一会话的所有操作领取串行化 */
    @Select("SELECT * FROM t_travel_session_state WHERE session_id=#{sessionId} FOR UPDATE")
    TravelSessionState selectForUpdate(@Param("sessionId") String sessionId);

    /** T1：占用会话（仅在无占用时成功） */
    @Update("UPDATE t_travel_session_state SET active_operation_id=#{operationId} "
            + "WHERE session_id=#{sessionId} AND active_operation_id IS NULL")
    int claimOperation(@Param("sessionId") String sessionId, @Param("operationId") String operationId);

    /** T2：提交新快照（revision+1、stage、清除占用），旧 revision 不符则 0 行 */
    @Update("UPDATE t_travel_session_state SET revision=#{newRevision}, stage=#{stage}, "
            + "state_json=#{stateJson}, active_operation_id=NULL, expires_at=#{expiresAt} "
            + "WHERE session_id=#{sessionId} AND user_id=#{userId} AND revision=#{expectedRevision}")
    int commitSnapshot(@Param("sessionId") String sessionId,
                       @Param("userId") Long userId,
                       @Param("expectedRevision") Long expectedRevision,
                       @Param("newRevision") Long newRevision,
                       @Param("stage") String stage,
                       @Param("stateJson") String stateJson,
                       @Param("expiresAt") LocalDateTime expiresAt);

    /** 释放占用：必须仍是同一 attemptNo 的持有者，防止旧执行者清掉新执行者的占用 */
    @Update("UPDATE t_travel_session_state SET active_operation_id=NULL "
            + "WHERE session_id=#{sessionId} AND active_operation_id=#{operationId} "
            + "AND EXISTS(SELECT 1 FROM t_travel_operation WHERE id=#{operationId} AND attempt_no=#{attemptNo})")
    int releaseOperation(@Param("sessionId") String sessionId,
                         @Param("operationId") String operationId,
                         @Param("attemptNo") int attemptNo);

    /**
     * 断点恢复：某用户最近一条「未结束」且最近活动在窗口内的会话。
     * 未结束 = stage 非 DONE，或存在进行中操作（重新生成/调整中）。
     * updated_at 由 DB 时钟维护（CURRENT_TIMESTAMP），窗口过滤必须用 DB 端 NOW() 保持同一时钟域。
     */
    @Select("SELECT * FROM t_travel_session_state WHERE user_id=#{userId} "
            + "AND (stage<>'DONE' OR active_operation_id IS NOT NULL) "
            + "AND updated_at >= NOW() - INTERVAL #{ttlMinutes} MINUTE "
            + "ORDER BY updated_at DESC LIMIT 1")
    TravelSessionState selectLatestUnfinished(@Param("userId") Long userId,
                                              @Param("ttlMinutes") int ttlMinutes);

    /** 断点恢复（按 sessionId）：同一恢复口径，归属不匹配/超出窗口返回 null（不抛错） */
    @Select("SELECT * FROM t_travel_session_state WHERE session_id=#{sessionId} "
            + "AND user_id=#{userId} "
            + "AND (stage<>'DONE' OR active_operation_id IS NOT NULL) "
            + "AND updated_at >= NOW() - INTERVAL #{ttlMinutes} MINUTE LIMIT 1")
    TravelSessionState selectResumableById(@Param("userId") Long userId,
                                           @Param("sessionId") String sessionId,
                                           @Param("ttlMinutes") int ttlMinutes);
}
