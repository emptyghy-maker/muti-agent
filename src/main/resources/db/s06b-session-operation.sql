-- S06-B 迁移脚本：会话权威快照 + 操作表 + 行程 operation_id（手册§10.2 草案的落地版本）
-- 执行前核查实例版本与历史数据；脚本须记录迁移版本，不要反复执行含 ALTER 的整段文本。
-- 旧会话在首次保存时自动补建快照行（revision=0）；旧行程 operation_id 保持 NULL。

CREATE TABLE IF NOT EXISTS t_travel_session_state (
  session_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  user_id BIGINT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  schema_version INT NOT NULL DEFAULT 2,
  stage VARCHAR(32) NOT NULL,
  state_json MEDIUMTEXT NOT NULL,
  active_operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  expires_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_session_owner_expiry (user_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_travel_operation (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  user_id BIGINT NOT NULL,
  session_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(32) NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  base_revision BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  attempt_no INT NOT NULL DEFAULT 1,
  lease_until DATETIME(3) NULL,
  provider_attempt_at DATETIME(3) NULL,
  input_snapshot MEDIUMTEXT NOT NULL,
  validated_draft MEDIUMTEXT NULL,
  result_json MEDIUMTEXT NULL,
  itinerary_id BIGINT NULL,
  error_code VARCHAR(64) NULL,
  error_detail MEDIUMTEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_actor_request (user_id, request_id),
  KEY idx_operation_session (session_id, created_at),
  KEY idx_operation_recovery (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE t_itinerary
  ADD COLUMN operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD UNIQUE KEY uk_itinerary_operation (operation_id);
