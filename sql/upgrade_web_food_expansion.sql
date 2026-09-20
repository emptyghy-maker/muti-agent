-- 联网美食扩充（阶段2）：
-- 1) t_restaurant 增加来源与位置字段（网搜店无坐标，lng/lat 放宽为空）；
-- 2) 新增网搜入库审计表（追加式，防恶意写入与事后可追溯）。
-- 执行方式：在 MySQL（192.168.150.105 上的 da-mysql 容器）对 data_agent 库执行本文件。
-- 幂等性：重复执行 ALTER 会报 Duplicate column，属预期（迁移只执行一次）。

ALTER TABLE t_restaurant
  MODIFY lng DECIMAL(10,6) NULL COMMENT '经度（网搜店允许为空）',
  MODIFY lat DECIMAL(10,6) NULL COMMENT '纬度（网搜店允许为空）',
  ADD COLUMN address VARCHAR(128) NULL COMMENT '街道/商圈级位置（网搜店）' AFTER avg_price,
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'KB' COMMENT 'KB=知识库种子 / WEB_SEARCH=联网检索扩充' AFTER status,
  ADD COLUMN source_ref VARCHAR(64) NULL COMMENT '来源会话标识' AFTER source,
  ADD COLUMN source_note VARCHAR(255) NULL COMMENT '来源说明（检索匹配理由）' AFTER source_ref;

CREATE TABLE IF NOT EXISTS t_web_food_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL,
  destination_id BIGINT NOT NULL,
  user_id BIGINT NULL,
  name VARCHAR(128) NOT NULL,
  cuisine VARCHAR(32) NULL,
  avg_price DECIMAL(10,2) NULL,
  address VARCHAR(128) NULL,
  action VARCHAR(16) NOT NULL COMMENT 'ACCEPT=已校验入库 / REJECT=拒绝',
  reject_reason VARCHAR(255) NULL COMMENT '拒绝原因（校验码，逗号分隔）',
  raw_payload VARCHAR(500) NULL COMMENT '原始返回摘要（脱敏后）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '审计时间（毫秒精度）',
  KEY idx_session (session_id),
  KEY idx_dest (destination_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
