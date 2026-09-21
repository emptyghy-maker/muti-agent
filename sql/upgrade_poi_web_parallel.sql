-- 三通道对称扩展（并行预热 · 景点/酒店网搜 · POI 晋升泛化）：
-- 1) t_attraction / t_hotel 增加来源与位置字段（网搜行无坐标，lng/lat 放宽为空），
--    并同步 t_restaurant 已有的推荐/勾选计数与晋升字段（晋升口径三类型一致）；
-- 2) t_web_food_audit 升级为通用 POI 网搜审计（place_type/place_id，历史数据默认 FOOD）；
-- 3) t_poi_recommend_event 增加 place_type，唯一键升级为 (place_type, restaurant_id, event_type, session_id)
--    （历史数据默认 FOOD，与旧唯一键语义完全兼容）。
-- 执行方式：在 MySQL（192.168.150.105 上的 da-mysql 容器）对 data_agent 库执行本文件。
-- 幂等性：重复执行 ALTER 会报 Duplicate column，属预期（迁移只执行一次）。

-- 景点：来源/位置 + 计数晋升（网搜景点无坐标，lng/lat 放宽）
ALTER TABLE t_attraction
  MODIFY lng DECIMAL(10,6) NULL COMMENT '经度（网搜景点允许为空）',
  MODIFY lat DECIMAL(10,6) NULL COMMENT '纬度（网搜景点允许为空）',
  ADD COLUMN address VARCHAR(128) NULL COMMENT '街道/商圈级位置（网搜景点）' AFTER ticket_price,
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'KB' COMMENT 'KB=知识库种子 / WEB_SEARCH=联网检索扩充' AFTER status,
  ADD COLUMN source_ref VARCHAR(64) NULL COMMENT '来源会话标识' AFTER source,
  ADD COLUMN source_note VARCHAR(255) NULL COMMENT '来源说明（检索匹配理由）' AFTER source_ref,
  ADD COLUMN recommend_count INT NOT NULL DEFAULT 0 COMMENT '被推荐展示的不同会话数（晋升口径）' AFTER source_note,
  ADD COLUMN select_count INT NOT NULL DEFAULT 0 COMMENT '被用户勾选的不同会话数（晋升口径）' AFTER recommend_count,
  ADD COLUMN last_recommended_at DATETIME(3) NULL COMMENT '最近一次被推荐时间（跨会话复用新鲜度锚点）' AFTER select_count,
  ADD COLUMN promoted_at DATETIME(3) NULL COMMENT '晋升 KB_PROMOTED 的时间' AFTER last_recommended_at;

-- 酒店：来源/位置 + 计数晋升（网搜酒店无坐标，lng/lat 放宽）
ALTER TABLE t_hotel
  MODIFY lng DECIMAL(10,6) NULL COMMENT '经度（网搜酒店允许为空）',
  MODIFY lat DECIMAL(10,6) NULL COMMENT '纬度（网搜酒店允许为空）',
  ADD COLUMN address VARCHAR(128) NULL COMMENT '街道/商圈级位置（网搜酒店）' AFTER price_per_night,
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'KB' COMMENT 'KB=知识库种子 / WEB_SEARCH=联网检索扩充' AFTER status,
  ADD COLUMN source_ref VARCHAR(64) NULL COMMENT '来源会话标识' AFTER source,
  ADD COLUMN source_note VARCHAR(255) NULL COMMENT '来源说明（检索匹配理由）' AFTER source_ref,
  ADD COLUMN recommend_count INT NOT NULL DEFAULT 0 COMMENT '被推荐展示的不同会话数（晋升口径）' AFTER source_note,
  ADD COLUMN select_count INT NOT NULL DEFAULT 0 COMMENT '被用户勾选的不同会话数（晋升口径）' AFTER recommend_count,
  ADD COLUMN last_recommended_at DATETIME(3) NULL COMMENT '最近一次被推荐时间（跨会话复用新鲜度锚点）' AFTER select_count,
  ADD COLUMN promoted_at DATETIME(3) NULL COMMENT '晋升 KB_PROMOTED 的时间' AFTER last_recommended_at;

-- 网搜审计表升级为通用 POI 审计（表名沿用历史名，历史数据默认 FOOD）
ALTER TABLE t_web_food_audit
  ADD COLUMN place_type VARCHAR(16) NOT NULL DEFAULT 'FOOD' COMMENT 'FOOD / ATTRACTION / HOTEL' AFTER user_id,
  ADD COLUMN place_id BIGINT NULL COMMENT '入库后的 POI 主键（ACCEPT 时回填；REJECT 为 null）' AFTER place_type;

-- POI 推荐事件表升级：place_type 参与唯一键（历史数据默认 FOOD，语义与旧键一致）
ALTER TABLE t_poi_recommend_event
  ADD COLUMN place_type VARCHAR(16) NOT NULL DEFAULT 'FOOD' COMMENT 'FOOD / ATTRACTION / HOTEL' AFTER restaurant_id,
  DROP INDEX uk_event_dedup,
  ADD UNIQUE KEY uk_event_dedup (place_type, restaurant_id, event_type, session_id);
