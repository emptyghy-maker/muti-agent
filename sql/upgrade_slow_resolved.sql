-- 慢调用「已解决」操控：管理员可把处理完的慢调用标记为已解决（默认列表不再展示，可切换查看/恢复）。
-- 执行方式：在 MySQL（192.168.150.105 上的 da-mysql 容器）对 data_agent 库执行本文件（只执行一次）。

CREATE TABLE IF NOT EXISTS t_slow_resolved (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  usage_record_id BIGINT NOT NULL COMMENT 't_usage_record.id（被标记的慢调用行）',
  resolved_by VARCHAR(64) NULL COMMENT '操作人',
  note VARCHAR(255) NULL COMMENT '解决说明',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_record (usage_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
