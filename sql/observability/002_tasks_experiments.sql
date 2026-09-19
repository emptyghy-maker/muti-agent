-- Observability 任务/修订/备注/实验表（DEV-07 / DEV-08）
-- 回退：DROP 本文件所有表即可。任务原文与旅行用户输入分开；不执行 git/模型。

CREATE TABLE IF NOT EXISTS obs_optimization_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(255) NOT NULL,
  goal TEXT NULL,
  hypothesis TEXT NULL,
  scope TEXT NULL,
  acceptance TEXT NULL,
  non_goals TEXT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACCEPTED/ARCHIVED',
  conclusion TEXT NULL,
  version INT NOT NULL DEFAULT 1 COMMENT '乐观版本：更新冲突 409',
  created_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优化任务（人工录入，ACCEPTED 只表示人工审核通过）';

CREATE TABLE IF NOT EXISTS obs_change_revision (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  revision_no INT NOT NULL COMMENT '任务内递增，禁止覆盖历史',
  prompt_version VARCHAR(64) NULL,
  model_version VARCHAR(64) NULL,
  git_hash VARCHAR(64) NULL,
  changes TEXT NULL COMMENT '实际改动（手工录入）',
  rationale TEXT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PROPOSED' COMMENT 'PROPOSED/ACCEPTED/ROLLED_BACK',
  created_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_obs_revision (task_id, revision_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务变更修订（追加，不覆盖历史）';

CREATE TABLE IF NOT EXISTS obs_run_link (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'RELATED' COMMENT 'BASELINE/CANDIDATE/RELATED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_obs_run_link (task_id, run_id, role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务↔run 关联（双向权限校验）';

CREATE TABLE IF NOT EXISTS obs_annotation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL,
  author_id BIGINT NOT NULL,
  author_username VARCHAR(64) NULL,
  content TEXT NOT NULL COMMENT '人工备注追加保留，不覆盖证据',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_obs_annotation_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行人工备注（追加式）';

CREATE TABLE IF NOT EXISTS obs_experiment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dataset_hash VARCHAR(64) NOT NULL,
  dataset_id VARCHAR(128) NULL,
  provider_mode VARCHAR(32) NOT NULL COMMENT 'STUB/LIVE/LIVE_FIXTURE',
  imported_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测结果导入（只导入，不执行模型）';

CREATE TABLE IF NOT EXISTS obs_experiment_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  experiment_id BIGINT NOT NULL,
  dataset_hash VARCHAR(64) NOT NULL,
  sample_id VARCHAR(128) NOT NULL,
  repeat_index INT NOT NULL,
  arm VARCHAR(32) NOT NULL COMMENT 'baseline/candidate',
  prompt_version VARCHAR(64) NULL,
  model_version VARCHAR(64) NULL,
  workflow_version VARCHAR(64) NULL,
  rule_version VARCHAR(64) NULL,
  fact_version VARCHAR(64) NULL,
  score DECIMAL(10,6) NULL,
  hard_violations INT NULL,
  cost_per_success DECIMAL(14,6) NULL,
  missing_flag TINYINT NOT NULL DEFAULT 0 COMMENT '缺失/未配对/费用未知保留不当作通过',
  raw_ref VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_obs_exp_pair (experiment_id, dataset_hash, sample_id, repeat_index, arm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实验单臂结果（配对键 datasetHash+sampleId+repeatIndex+arm）';
