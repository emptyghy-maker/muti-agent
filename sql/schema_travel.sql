-- =====================================================
-- 多 Agent 旅游攻略规划 · 阶段A 数据底座 DDL
-- 库：data_agent（192.168.150.105 的 da-mysql 容器）
-- 执行方式（在 Docker 宿主上）：
--   docker exec -i da-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 data_agent < schema_travel.sql
-- 本文件为纯 ASCII，无编码问题；种子数据见 seed_travel.sql（UNHEX 十六进制）
-- =====================================================
USE data_agent;

-- 目的地（首期：南京、苏州）
CREATE TABLE IF NOT EXISTS t_destination (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  province VARCHAR(64),
  intro VARCHAR(512),
  tags VARCHAR(255),
  daily_budget_min DECIMAL(10,2),
  daily_budget_max DECIMAL(10,2),
  status TINYINT NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 景点
CREATE TABLE IF NOT EXISTS t_attraction (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  destination_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  category VARCHAR(32) NOT NULL,
  features VARCHAR(255),
  intensity TINYINT NOT NULL DEFAULT 3,
  suggest_hours DECIMAL(3,1) NOT NULL DEFAULT 2.0,
  ticket_price DECIMAL(10,2) NOT NULL DEFAULT 0,
  lng DECIMAL(10,6) NOT NULL,
  lat DECIMAL(10,6) NOT NULL,
  rating DECIMAL(2,1) NOT NULL DEFAULT 4.5,
  open_time VARCHAR(64),
  indoor TINYINT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  KEY idx_dest (destination_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 美食店铺
CREATE TABLE IF NOT EXISTS t_restaurant (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  destination_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  cuisine VARCHAR(32) NOT NULL,
  signature_dish VARCHAR(255),
  avg_price DECIMAL(10,2) NOT NULL,
  lng DECIMAL(10,6) NOT NULL,
  lat DECIMAL(10,6) NOT NULL,
  rating DECIMAL(2,1) NOT NULL DEFAULT 4.5,
  business_hours VARCHAR(64),
  status TINYINT NOT NULL DEFAULT 1,
  KEY idx_dest (destination_id),
  KEY idx_cuisine (cuisine)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 酒店
CREATE TABLE IF NOT EXISTS t_hotel (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  destination_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  price_per_night DECIMAL(10,2) NOT NULL,
  rating DECIMAL(2,1) NOT NULL DEFAULT 4.5,
  level VARCHAR(16) NOT NULL DEFAULT '舒适',
  lng DECIMAL(10,6) NOT NULL,
  lat DECIMAL(10,6) NOT NULL,
  features VARCHAR(255),
  status TINYINT NOT NULL DEFAULT 1,
  KEY idx_dest (destination_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 行程（含版本链，支持 ADJUST 修改模式）
CREATE TABLE IF NOT EXISTS t_itinerary (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  destination_id BIGINT NOT NULL,
  title VARCHAR(128),
  days INT NOT NULL,
  total_budget DECIMAL(10,2),
  total_cost DECIMAL(10,2),
  preference_json TEXT,
  plan_json TEXT,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 1,
  parent_id BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 行程评价（反馈体系）
CREATE TABLE IF NOT EXISTS t_itinerary_feedback (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  itinerary_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  rating TINYINT NOT NULL,
  pace_rating TINYINT,
  attraction_satisfy TINYINT,
  food_satisfy TINYINT,
  hotel_satisfy TINYINT,
  budget_fit VARCHAR(16),
  tags VARCHAR(255),
  feedback_key VARCHAR(64),
  itinerary_revision INT,
  review_status VARCHAR(16) DEFAULT 'PENDING',
  reviewer_ref VARCHAR(64),
  label_version VARCHAR(64),
  comment VARCHAR(512),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_itinerary (itinerary_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用量记录（操作日志 + 各阶段 Agent token 消耗 + Q&A 审计流水，成本核算用）
CREATE TABLE IF NOT EXISTS t_usage_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT,
  username VARCHAR(64),
  session_id VARCHAR(64),
  stage VARCHAR(32),
  action VARCHAR(64),
  agent VARCHAR(64),
  model VARCHAR(64),
  channel VARCHAR(32),
  question VARCHAR(1024),
  answer TEXT,
  input_tokens INT NOT NULL DEFAULT 0,
  output_tokens INT NOT NULL DEFAULT 0,
  total_tokens INT NOT NULL DEFAULT 0,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(16),
  remark VARCHAR(512),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user (user_id),
  KEY idx_stage (stage),
  KEY idx_model (model),
  KEY idx_channel (channel),
  KEY idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 终态事件持久化（手册 §6）：eventId 幂等主键，重启恢复与去重的唯一依据
CREATE TABLE IF NOT EXISTS t_terminal_event (
  event_id VARCHAR(64) PRIMARY KEY,
  operation_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  duration_ms BIGINT NOT NULL,
  payload MEDIUMTEXT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_terminal_operation (operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
