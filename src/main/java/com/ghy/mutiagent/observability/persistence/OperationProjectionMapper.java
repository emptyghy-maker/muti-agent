package com.ghy.mutiagent.observability.persistence;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 业务账本只读投影（对账专用）：只查询已知 operationId 的必要字段，
 * 不加业务行锁、不写业务命令。来源标 RECONCILED。
 */
public interface OperationProjectionMapper {

    record OperationProjection(String operationId, Long userId, String sessionId, String status,
                               String errorCode, Long itineraryId,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    @Select("SELECT id AS operationId, user_id AS userId, session_id AS sessionId, status,"
            + " error_code AS errorCode, itinerary_id AS itineraryId, created_at AS createdAt,"
            + " updated_at AS updatedAt FROM t_travel_operation WHERE id = #{operationId}")
    OperationProjection selectOperation(@Param("operationId") String operationId);

    @Select("SELECT plan_json FROM t_itinerary WHERE id = #{itineraryId} LIMIT 1")
    String selectItineraryPlanJson(@Param("itineraryId") Long itineraryId);
}
