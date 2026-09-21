-- POI 推荐计数与自动晋升（网搜店 → 知识库）：
-- 1) t_restaurant 增加推荐/勾选计数与晋升字段；
-- 2) 新增 POI 推荐事件表（唯一键按 会话×店×事件 去重，防重复计数/防刷，事件可回放对账）。
-- 执行方式：在 MySQL（192.168.150.105 上的 da-mysql 容器）对 data_agent 库执行本文件。
-- 幂等性：重复执行 ALTER 会报 Duplicate column，属预期（迁移只执行一次）。

ALTER TABLE t_restaurant
  ADD COLUMN recommend_count INT NOT NULL DEFAULT 0 COMMENT '被推荐展示的不同会话数（晋升口径）' AFTER source_note,
  ADD COLUMN select_count INT NOT NULL DEFAULT 0 COMMENT '被用户勾选的不同会话数（晋升口径）' AFTER recommend_count,
  ADD COLUMN last_recommended_at DATETIME(3) NULL COMMENT '最近一次被推荐时间（跨会话复用新鲜度锚点）' AFTER select_count,
  ADD COLUMN promoted_at DATETIME(3) NULL COMMENT '晋升 KB_PROMOTED 的时间' AFTER last_recommended_at;

CREATE TABLE IF NOT EXISTS t_poi_recommend_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  restaurant_id BIGINT NOT NULL COMMENT 't_restaurant.id',
  destination_id BIGINT NOT NULL,
  event_type VARCHAR(16) NOT NULL COMMENT 'RECOMMEND=被纳入候选池展示 / SELECT=被用户勾选确认',
  session_id VARCHAR(64) NOT NULL COMMENT '来源会话（同会话同店同事件只计一次）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_event_dedup (restaurant_id, event_type, session_id),
  KEY idx_dest (destination_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
