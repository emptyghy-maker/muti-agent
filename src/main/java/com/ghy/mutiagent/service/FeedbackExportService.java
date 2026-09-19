package com.ghy.mutiagent.service;

import com.ghy.mutiagent.repository.entity.ItineraryFeedback;
import com.ghy.mutiagent.repository.mapper.ItineraryFeedbackMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S12 反馈人工复核导出：仅 APPROVED 反馈进入评测集；
 * 用户标识替换为不可反推的逻辑标识（哈希化），不输出原始 userId/username。
 * 待审核与驳回数据不参与提示词或权重更新。
 */
@Service
public class FeedbackExportService {

    private final ItineraryFeedbackMapper feedbackMapper;

    public FeedbackExportService(ItineraryFeedbackMapper feedbackMapper) {
        this.feedbackMapper = feedbackMapper;
    }

    /** 导出 APPROVED 反馈（匿名化 + 仅人工复核通过的样本） */
    public List<Map<String, Object>> exportApproved() {
        List<ItineraryFeedback> approved = feedbackMapper.selectList(
                new LambdaQueryWrapper<ItineraryFeedback>()
                        .eq(ItineraryFeedback::getReviewStatus, "APPROVED")
                        .orderByAsc(ItineraryFeedback::getFeedbackKey));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ItineraryFeedback f : approved) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("feedbackKey", f.getFeedbackKey());
            item.put("itineraryId", f.getItineraryId());
            item.put("itineraryRevision", f.getItineraryRevision());
            item.put("rating", f.getRating());
            item.put("tags", f.getTags() == null ? List.of() : List.of(f.getTags().split(",")));
            item.put("reviewStatus", f.getReviewStatus());
            item.put("reviewerRef", f.getReviewerRef());
            item.put("labelVersion", f.getLabelVersion());
            item.put("userRef", pseudonymOf(f.getUserId()));
            out.add(item);
        }
        return out;
    }

    /** 不可反推用户的逻辑标识（导出/评测集用，不携带原始 userId） */
    private String pseudonymOf(Long userId) {
        if (userId == null) {
            return "anon-unknown";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(("p2-feedback-salt:" + userId).getBytes(StandardCharsets.UTF_8));
            return "anon-" + HexFormat.of().formatHex(md.digest()).substring(0, 12);
        } catch (Exception e) {
            return "anon-unknown";
        }
    }
}
