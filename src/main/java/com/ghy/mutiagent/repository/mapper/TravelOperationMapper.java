package com.ghy.mutiagent.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ghy.mutiagent.repository.entity.TravelOperation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 会话写操作 Mapper（S06-B）。所有状态推进都带 attemptNo/status 守卫：
 * 旧执行者（attemptNo 已过期）写回影响 0 行，不能覆盖新执行者的结果。
 */
public interface TravelOperationMapper extends BaseMapper<TravelOperation> {

    @Select("SELECT * FROM t_travel_operation WHERE user_id=#{userId} AND request_id=#{requestId}")
    TravelOperation findByRequest(@Param("userId") Long userId, @Param("requestId") String requestId);

    @Select("SELECT * FROM t_travel_operation WHERE id=#{id} FOR UPDATE")
    TravelOperation selectForUpdate(@Param("id") String id);

    /** 提供方已被调用（事务外模型调用完成后立刻记录，作为崩溃恢复依据） */
    @Update("UPDATE t_travel_operation SET provider_attempt_at=NOW(3) "
            + "WHERE id=#{id} AND attempt_no=#{attemptNo} AND status='RUNNING'")
    int recordProviderAttempt(@Param("id") String id, @Param("attemptNo") int attemptNo);

    /** 草案通过校验并持久保存（T2 前的恢复点） */
    @Update("UPDATE t_travel_operation SET status='VALIDATED', validated_draft=#{draft}, lease_until=NULL "
            + "WHERE id=#{id} AND attempt_no=#{attemptNo} AND status='RUNNING'")
    int saveValidatedDraft(@Param("id") String id, @Param("attemptNo") int attemptNo,
                           @Param("draft") String draft);

    /** T2 完成 */
    @Update("UPDATE t_travel_operation SET status='COMPLETED', result_json=#{resultJson}, "
            + "itinerary_id=#{itineraryId}, lease_until=NULL "
            + "WHERE id=#{id} AND attempt_no=#{attemptNo} AND status='VALIDATED'")
    int complete(@Param("id") String id, @Param("attemptNo") int attemptNo,
                 @Param("resultJson") String resultJson, @Param("itineraryId") Long itineraryId);

    /** 明确失败（仅当前 attemptNo 的持有者可以标记并触发释放） */
    @Update("UPDATE t_travel_operation SET status='FAILED', error_code=#{errorCode}, "
            + "error_detail=#{errorDetail}, lease_until=NULL "
            + "WHERE id=#{id} AND attempt_no=#{attemptNo} AND status IN ('RUNNING','VALIDATED')")
    int markFailed(@Param("id") String id, @Param("attemptNo") int attemptNo,
                   @Param("errorCode") String errorCode, @Param("errorDetail") String errorDetail);

    /** S08 终止态（NEEDS_CONFIRMATION / DEADLINE_EXCEEDED / BUSY）：仅当前 attemptNo 持有者可标记 */
    @Update("UPDATE t_travel_operation SET status=#{status}, error_code=#{errorCode}, "
            + "error_detail=#{errorDetail}, lease_until=NULL "
            + "WHERE id=#{id} AND attempt_no=#{attemptNo} AND status IN ('RUNNING','VALIDATED')")
    int markTerminal(@Param("id") String id, @Param("attemptNo") int attemptNo,
                     @Param("status") String status, @Param("errorCode") String errorCode,
                     @Param("errorDetail") String errorDetail);

    /** S11 显式取消（SSE 断开同路径）：持久化取消标记；已终态的操作不再改变 */
    @Update("UPDATE t_travel_operation SET status='CANCELLED', lease_until=NULL "
            + "WHERE id=#{id} AND attempt_no=#{attemptNo} AND status IN ('RUNNING','VALIDATED')")
    int cancel(@Param("id") String id, @Param("attemptNo") int attemptNo);

    /** S11 入队失败（有界队列满）：操作刚被本请求创建且尚无执行线程，可直接置 BUSY 终态 */
    @Update("UPDATE t_travel_operation SET status='BUSY', error_code='BUSY', "
            + "error_detail='系统繁忙，请稍后重试（可用原 requestId 重试）', lease_until=NULL "
            + "WHERE id=#{id} AND status IN ('RUNNING','VALIDATED')")
    int markBusy(@Param("id") String id);

    /** 恢复核查：提供方已响应但草案未保存 → UNKNOWN，保留占用、禁止自动重放 */
    @Update("UPDATE t_travel_operation SET status='UNKNOWN', lease_until=NULL "
            + "WHERE id=#{id} AND attempt_no=#{attemptNo} AND status='RUNNING'")
    int markUnknown(@Param("id") String id, @Param("attemptNo") int attemptNo);

    /** 接管：租约过期且提供方未被调用过 → attemptNo+1，新执行者续租（时间戳由 Java 传入，避免与 DB 会话时区不一致） */
    @Update("UPDATE t_travel_operation SET attempt_no=attempt_no+1, lease_until=#{leaseUntil} "
            + "WHERE id=#{id} AND attempt_no=#{attemptNo} AND status='RUNNING' "
            + "AND lease_until IS NOT NULL AND lease_until < #{now}")
    int takeover(@Param("id") String id, @Param("attemptNo") int attemptNo,
                 @Param("leaseUntil") LocalDateTime leaseUntil, @Param("now") LocalDateTime now);
}
