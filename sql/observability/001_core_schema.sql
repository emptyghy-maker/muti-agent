-- Observability 模块核心表（DEV-03）
-- 前向迁移：只新增 obs_ 表，不修改任何业务表（t_itinerary/t_travel_operation/schema_travel.sql 保持原样）
-- 回退：DROP 本文件所有表即可，旧代码不依赖这些表；已持久化事件按协议恢复，无法恢复部分显式标缺失。
-- 单位约定：duration_ms 毫秒；occurred_at/received_at epoch 毫秒（BIGINT）；金额 DECIMAL + currency；token 可空（null≠0）。

CREATE TABLE IF NOT EXISTS obs_run (
  run_id VARCHAR(64) PRIMARY KEY COMMENT 'runId：operation 作用域为 operationId，否则 sess-<sessionId>',
  operation_id VARCHAR(64) NULL COMMENT '业务操作键（可空：会话级活动）',
  session_id VARCHAR(64) NULL,
  owner_id BIGINT NULL COMMENT 'null=隔离域，普通用户不可见',
  run_status VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/COMPLETED/ABORTED/UNKNOWN',
  business_status VARCHAR(32) NULL COMMENT '业务结果原样映射',
  data_completeness VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'COMPLETE/PARTIAL/UNKNOWN',
  source_system VARCHAR(16) NOT NULL DEFAULT 'NEW' COMMENT 'NEW/RECONCILED/LEGACY/IMPORTED',
  started_at DATETIME(3) NULL,
  ended_at DATETIME(3) NULL,
  duration_ms BIGINT NULL,
  prompt_version VARCHAR(64) NULL,
  model_version VARCHAR(64) NULL,
  constraint_revision BIGINT NULL,
  snapshot_hash VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_obs_run_operation (operation_id),
  KEY idx_obs_run_owner_started (owner_id, started_at),
  KEY idx_obs_run_status (run_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观测运行（记录执行生命周期，业务状态独立）';

CREATE TABLE IF NOT EXISTS obs_span (
  span_id VARCHAR(128) PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL,
  parent_span_id VARCHAR(128) NULL,
  node_execution_id VARCHAR(128) NULL COMMENT '节点重入产生新值',
  kind VARCHAR(16) NULL COMMENT 'OPERATION/PROVIDER/…',
  agent VARCHAR(64) NULL,
  attempt_no INT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'STARTED' COMMENT 'STARTED/SUCCESS/FAILED/CANCELLED（乱序不回退）',
  started_at DATETIME(3) NULL,
  ended_at DATETIME(3) NULL,
  duration_ms BIGINT NULL,
  summary VARCHAR(1024) NULL,
  payload_ref VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_obs_span_run (run_id, started_at),
  KEY idx_obs_span_parent (parent_span_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观测 span';

CREATE TABLE IF NOT EXISTS obs_event (
  event_id VARCHAR(192) PRIMARY KEY COMMENT '幂等键：spanId|eventType|sequence',
  run_id VARCHAR(64) NOT NULL,
  operation_id VARCHAR(64) NULL,
  span_id VARCHAR(128) NULL,
  parent_span_id VARCHAR(128) NULL,
  node_execution_id VARCHAR(128) NULL,
  event_type VARCHAR(32) NOT NULL,
  occurred_at BIGINT NOT NULL COMMENT 'epoch 毫秒（业务时间）',
  received_at BIGINT NOT NULL,
  owner_id BIGINT NULL,
  schema_version VARCHAR(16) NOT NULL,
  producer_id VARCHAR(64) NOT NULL,
  sequence_no BIGINT NOT NULL,
  digest VARCHAR(64) NULL,
  payload_ref VARCHAR(64) NULL,
  summary_json MEDIUMTEXT NULL COMMENT '脱敏结构化摘要（不含原文）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_obs_event_run_seq (run_id, sequence_no),
  KEY idx_obs_event_span (span_id),
  KEY idx_obs_event_owner (owner_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观测事件（幂等、乱序不回退）';

CREATE TABLE IF NOT EXISTS obs_payload (
  payload_id VARCHAR(64) PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL,
  owner_id BIGINT NULL,
  kind VARCHAR(32) NULL COMMENT 'INPUT/OUTPUT/TOOL_IO/…',
  size_bytes INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'FULL' COMMENT 'FULL/TRUNCATED/REDACTED/EXPIRED',
  content MEDIUMTEXT NULL COMMENT 'EXPIRED 后置 NULL',
  digest VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NULL,
  KEY idx_obs_payload_run (run_id),
  KEY idx_obs_payload_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观测载荷（脱敏/截断/保留期管理）';

CREATE TABLE IF NOT EXISTS obs_usage_attempt (
  attempt_key VARCHAR(160) PRIMARY KEY COMMENT 'operationId|agent|attemptNo|sourceSystem 复合唯一',
  run_id VARCHAR(64) NOT NULL,
  span_id VARCHAR(128) NULL,
  source_system VARCHAR(16) NOT NULL DEFAULT 'NEW' COMMENT 'NEW/RECONCILED/LEGACY/IMPORTED',
  agent VARCHAR(64) NULL,
  model VARCHAR(64) NULL,
  input_tokens INT NULL COMMENT 'UNKNOWN 为 null，不等于 0',
  output_tokens INT NULL,
  usage_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'KNOWN/UNKNOWN/NOT_APPLICABLE',
  duration_ms BIGINT NULL,
  cost_amount DECIMAL(14,6) NULL,
  currency VARCHAR(8) NULL DEFAULT 'CNY',
  amount_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'ESTIMATED/SETTLED/UNKNOWN/LEGACY_ESTIMATE',
  price_snapshot MEDIUMTEXT NULL COMMENT '记录时价格快照（历史不漂移）',
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_obs_attempt_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观测用量尝试（唯一外部尝试口径）';

CREATE TABLE IF NOT EXISTS obs_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor_id BIGINT NOT NULL,
  actor_username VARCHAR(64) NULL,
  action VARCHAR(64) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id VARCHAR(128) NULL,
  detail VARCHAR(1024) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_obs_audit_actor (actor_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观测管理审计（ADMIN 跨用户查询/导入/导出）';
