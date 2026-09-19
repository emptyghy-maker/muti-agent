package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 行程评价（反馈体系） */
@Data
@TableName("t_itinerary_feedback")
public class ItineraryFeedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long itineraryId;
    private Long userId;
    /** 综合评分 1~5 */
    private Integer rating;
    /** 节奏：1太赶 3刚好 5太松 */
    private Integer paceRating;
    private Integer attractionSatisfy;
    private Integer foodSatisfy;
    private Integer hotelSatisfy;
    /** 超支/刚好/富余 */
    private String budgetFit;
    /** 快捷标签，逗号分隔 */
    private String tags;
    private String comment;
    /** S12：反馈幂等键（同 owner 业务范围内唯一；网络重试返回同条评价） */
    private String feedbackKey;
    /** S12：评价绑定的行程版本（revision），追随后续版本化 */
    private Integer itineraryRevision;
    /** S12：人工复核状态 PENDING/APPROVED/REJECTED——仅 APPROVED 可导入评测集 */
    private String reviewStatus;
    /** S12：复核人标识（不含原始用户信息） */
    private String reviewerRef;
    /** S12：标注版本 */
    private String labelVersion;
    private LocalDateTime createdAt;
}
