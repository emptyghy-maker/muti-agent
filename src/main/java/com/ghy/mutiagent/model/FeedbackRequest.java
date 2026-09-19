package com.ghy.mutiagent.model;

import lombok.Data;

import java.util.List;

/**
 * 行程评价请求（反馈体系）。
 */
@Data
public class FeedbackRequest {
    /** 综合评分 1~5 */
    private Integer rating;
    /** 节奏：1太赶 3刚好 5太松 */
    private Integer paceRating;
    private Integer attractionSatisfy;
    private Integer foodSatisfy;
    private Integer hotelSatisfy;
    /** 超支/刚好/富余 */
    private String budgetFit;
    private List<String> tags;
    private String comment;
}
