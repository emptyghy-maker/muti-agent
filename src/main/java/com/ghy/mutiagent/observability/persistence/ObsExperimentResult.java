package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** obs_experiment_result：实验单臂结果（配对键 datasetHash+sampleId+repeatIndex+arm） */
@Data
@TableName("obs_experiment_result")
public class ObsExperimentResult {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long experimentId;
    private String datasetHash;
    private String sampleId;
    private Integer repeatIndex;
    private String arm;
    private String promptVersion;
    private String modelVersion;
    private String workflowVersion;
    private String ruleVersion;
    private String factVersion;
    private BigDecimal score;
    private Integer hardViolations;
    private BigDecimal costPerSuccess;
    private Integer missingFlag;
    private String rawRef;
    private LocalDateTime createdAt;
}
