-- ============================================================
-- 知识库景点扩充：苏州 / 南京 街区·夜景·网红打卡类（2026-09 一次性补充）
-- 用途：解决「不同需求返回雷同备选」——原库文化历史类高分景点占比过高，
--       citywalk / 情侣夜景 / 网红打卡类素材不足。
-- 说明：店名/分类/特色为真实公开信息；坐标与评分为近似值，上线前可校准。
-- 执行：mysql -h 192.168.150.105 -u app -papp123 data_agent < seed_attractions_expand.sql
-- 幂等：按（destination_id, name）去重，重复执行不产生重复数据。
-- ============================================================

USE data_agent;

-- ---------- 苏州（destination_id=2） ----------
INSERT INTO t_attraction (destination_id, name, category, features, intensity, suggest_hours, ticket_price, lng, lat, rating, open_time, indoor, status)
SELECT 2, '观前街商圈', '打卡拍照', '商业街区,老字号云集,步行街,免费', 1, 2.5, 0.00, 120.626600, 31.310500, 4.4, '全天', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM t_attraction WHERE destination_id = 2 AND name = '观前街商圈');

INSERT INTO t_attraction (destination_id, name, category, features, intensity, suggest_hours, ticket_price, lng, lat, rating, open_time, indoor, status)
SELECT 2, '十全街', '打卡拍照', '网红咖啡,街拍,文创小店', 1, 2.0, 0.00, 120.631000, 31.303000, 4.4, '全天', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM t_attraction WHERE destination_id = 2 AND name = '十全街');

INSERT INTO t_attraction (destination_id, name, category, features, intensity, suggest_hours, ticket_price, lng, lat, rating, open_time, indoor, status)
SELECT 2, '淮海街', '打卡拍照', '日式街区,夜景,网红餐厅', 1, 1.5, 0.00, 120.596000, 31.300000, 4.3, '10:00-22:00', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM t_attraction WHERE destination_id = 2 AND name = '淮海街');

INSERT INTO t_attraction (destination_id, name, category, features, intensity, suggest_hours, ticket_price, lng, lat, rating, open_time, indoor, status)
SELECT 2, '东方之门(苏州中心)', '打卡拍照', '地标建筑,夜景,商场,湖景', 1, 1.5, 0.00, 120.672000, 31.316000, 4.4, '10:00-22:00', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM t_attraction WHERE destination_id = 2 AND name = '东方之门(苏州中心)');

INSERT INTO t_attraction (destination_id, name, category, features, intensity, suggest_hours, ticket_price, lng, lat, rating, open_time, indoor, status)
SELECT 2, '李公堤', '休闲', '金鸡湖湖景,夜景,湖畔餐厅,骑行', 1, 2.5, 0.00, 120.671000, 31.301000, 4.4, '全天', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM t_attraction WHERE destination_id = 2 AND name = '李公堤');

INSERT INTO t_attraction (destination_id, name, category, features, intensity, suggest_hours, ticket_price, lng, lat, rating, open_time, indoor, status)
SELECT 2, '环古城河健身步道', '休闲', '环城步道,城墙,夜景,免费', 1, 3.0, 0.00, 120.615000, 31.315000, 4.3, '全天', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM t_attraction WHERE destination_id = 2 AND name = '环古城河健身步道');

INSERT INTO t_attraction (destination_id, name, category, features, intensity, suggest_hours, ticket_price, lng, lat, rating, open_time, indoor, status)
SELECT 2, '金鸡湖月光码头', '打卡拍照', '湖边码头,夜景,音乐喷泉,散步', 1, 1.5, 0.00, 120.675000, 31.312000, 4.5, '全天', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM t_attraction WHERE destination_id = 2 AND name = '金鸡湖月光码头');

-- ---------- 南京（destination_id=1） ----------
INSERT INTO t_attraction (destination_id, name, category, features, intensity, suggest_hours, ticket_price, lng, lat, rating, open_time, indoor, status)
SELECT 1, '1912街区', '打卡拍照', '民国风情,酒吧街,夜景', 1, 2.0, 0.00, 118.795000, 32.055000, 4.4, '全天', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM t_attraction WHERE destination_id = 1 AND name = '1912街区');

INSERT INTO t_attraction (destination_id, name, category, features, intensity, suggest_hours, ticket_price, lng, lat, rating, open_time, indoor, status)
SELECT 1, '颐和路民国公馆区', '打卡拍照', '民国建筑,梧桐大道,街拍,免费', 1, 2.0, 0.00, 118.777000, 32.062000, 4.5, '全天', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM t_attraction WHERE destination_id = 1 AND name = '颐和路民国公馆区');

INSERT INTO t_attraction (destination_id, name, category, features, intensity, suggest_hours, ticket_price, lng, lat, rating, open_time, indoor, status)
SELECT 1, '熙南里', '打卡拍照', '历史街区,夜景,文创市集', 1, 1.5, 0.00, 118.781000, 32.032000, 4.3, '全天', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM t_attraction WHERE destination_id = 1 AND name = '熙南里');

INSERT INTO t_attraction (destination_id, name, category, features, intensity, suggest_hours, ticket_price, lng, lat, rating, open_time, indoor, status)
SELECT 1, '鱼嘴湿地公园', '自然风光', '江景,日落,露营,免费', 1, 2.5, 0.00, 118.640000, 31.990000, 4.4, '全天', 0, 1
WHERE NOT EXISTS (SELECT 1 FROM t_attraction WHERE destination_id = 1 AND name = '鱼嘴湿地公园');
