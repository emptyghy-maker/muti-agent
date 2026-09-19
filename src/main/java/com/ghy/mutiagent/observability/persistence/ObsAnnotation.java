package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** obs_annotation：运行人工备注（追加式，不覆盖证据） */
@Data
@TableName("obs_annotation")
public class ObsAnnotation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private Long authorId;
    private String authorUsername;
    private String content;
    private LocalDateTime createdAt;
}
