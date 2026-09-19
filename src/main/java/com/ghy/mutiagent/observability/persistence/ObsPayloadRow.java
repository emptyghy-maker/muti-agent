package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** obs_payload：载荷行（EXPIRED 后 content 置 NULL，摘要保留） */
@Data
@TableName("obs_payload")
public class ObsPayloadRow {
    @TableId(type = IdType.INPUT)
    private String payloadId;
    private String runId;
    private Long ownerId;
    private String kind;
    private Integer sizeBytes;
    private String status;
    private String content;
    private String digest;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
