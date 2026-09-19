package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 景点 */
@Data
@TableName("t_attraction")
public class Attraction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long destinationId;
    private String name;
    /** 打卡拍照 / 娱乐项目 / 自然风光 / 文化历史 / 休闲 */
    private String category;
    private String features;
    /** 结构化标签（受控词表，逗号分隔）：需求匹配与冲突识别用 */
    private String tags;
    /** 游玩强度 1~5（5 最累） */
    private Integer intensity;
    /** 建议游玩时长（小时） */
    private Double suggestHours;
    private BigDecimal ticketPrice;
    private Double lng;
    private Double lat;
    private Double rating;
    private String openTime;
    /** 1=室内 0=室外（雨天建议引用） */
    private Integer indoor;
    private Integer status;
}
