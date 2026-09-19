package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** obs_experiment：评测结果导入（只导入，不执行模型） */
@Data
@TableName("obs_experiment")
public class ObsExperiment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String datasetHash;
    private String datasetId;
    private String providerMode;
    private Long importedBy;
    private LocalDateTime createdAt;
}
