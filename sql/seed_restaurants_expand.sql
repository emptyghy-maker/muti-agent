-- ============================================================
-- 知识库餐厅扩充：苏州 / 南京 平价小吃与老字号（2026-09 一次性补充）
-- 用途：解决「特殊需求只重排原知识库、无新店可选」问题——池=全库，
--       只有扩充 t_restaurant 才能让 FoodAgent 真正搜到新店。
-- 说明：店名/风味/人均为真实公开信息；坐标与评分为近似值（用于顺路
--       距离计算），上线前可按实际位置校准 lng/lat。
-- 执行：mysql -h 192.168.150.105 -u app -papp123 data_agent < seed_restaurants_expand.sql
-- 幂等：按（destination_id, name）去重，重复执行不会产生重复数据。
-- ============================================================

USE data_agent;

-- ---------- 苏州（destination_id=2） ----------
INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 2, '朱鸿兴面馆(观前街店)', '小吃', '焖肉面,爆鱼面', 28.00, 120.628000, 31.312000, 4.5, '06:30-19:30', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 2 AND name = '朱鸿兴面馆(观前街店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 2, '陆稿荐(观前街店)', '小吃', '酱肉,酱鸭', 42.00, 120.627500, 31.311500, 4.4, '07:00-19:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 2 AND name = '陆稿荐(观前街店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 2, '绿杨馄饨(观前街店)', '小吃', '泡泡馄饨,虾仁馄饨', 20.00, 120.626000, 31.311000, 4.3, '06:30-20:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 2 AND name = '绿杨馄饨(观前街店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 2, '采芝斋(观前街店)', '小吃', '枣泥麻饼,松子糖', 35.00, 120.625000, 31.310000, 4.3, '09:00-21:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 2 AND name = '采芝斋(观前街店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 2, '潘玉麟糖粥摊(皮市街店)', '小吃', '糖粥,酒酿圆子', 12.00, 120.628000, 31.316000, 4.7, '10:00-18:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 2 AND name = '潘玉麟糖粥摊(皮市街店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 2, '阊门姚记豆浆(西中市店)', '小吃', '咸豆浆,油条', 15.00, 120.612000, 31.318000, 4.6, '04:00-13:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 2 AND name = '阊门姚记豆浆(西中市店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 2, '双塔市集(定慧寺巷店)', '小吃', '苏式生煎,糖芋苗', 30.00, 120.632000, 31.306000, 4.5, '07:00-19:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 2 AND name = '双塔市集(定慧寺巷店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 2, '伟记奥面馆(白塔东路店)', '小吃', '奥灶面,焖肉面', 25.00, 120.633000, 31.314000, 4.4, '06:00-14:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 2 AND name = '伟记奥面馆(白塔东路店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 2, '长发西饼(观前街店)', '小吃', '鲜肉月饼,糖炒栗子', 20.00, 120.627000, 31.312500, 4.4, '08:00-20:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 2 AND name = '长发西饼(观前街店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 2, '老苏州茶酒楼(平江路店)', '本地菜', '松鼠桂鱼,响油鳝糊', 68.00, 120.633000, 31.310000, 4.4, '11:00-21:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 2 AND name = '老苏州茶酒楼(平江路店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 2, '协和菜馆(凤凰街店)', '本地菜', '苏式酱方,清炒虾仁', 75.00, 120.629000, 31.305000, 4.3, '11:00-20:30', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 2 AND name = '协和菜馆(凤凰街店)');

-- ---------- 南京（destination_id=1） ----------
INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 1, '尹氏汤包(狮子桥店)', '小吃', '鸡汁汤包,鸭血粉丝', 28.00, 118.779900, 32.072000, 4.4, '07:00-21:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 1 AND name = '尹氏汤包(狮子桥店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 1, '刘长兴面馆(逸仙桥店)', '小吃', '皮肚面,鸭油烧饼', 25.00, 118.800000, 32.040000, 4.4, '06:30-20:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 1 AND name = '刘长兴面馆(逸仙桥店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 1, '芳婆糕团店(王府大街店)', '小吃', '麻团,蒸儿糕', 15.00, 118.776000, 32.035000, 4.6, '06:00-14:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 1 AND name = '芳婆糕团店(王府大街店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 1, '易记皮肚面(明瓦廊店)', '小吃', '皮肚面,卤蛋', 20.00, 118.777000, 32.038000, 4.3, '10:00-21:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 1 AND name = '易记皮肚面(明瓦廊店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 1, '小郑酥烧饼(老门东店)', '小吃', '鸭油酥烧饼', 10.00, 118.784000, 32.015000, 4.6, '06:30-18:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 1 AND name = '小郑酥烧饼(老门东店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 1, '莲湖糕团店(贡院西街店)', '小吃', '赤豆元宵,马蹄糕', 18.00, 118.787000, 32.022000, 4.5, '08:00-20:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 1 AND name = '莲湖糕团店(贡院西街店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 1, '小李汤包(山西路店)', '小吃', '蟹黄汤包,小笼包', 35.00, 118.777000, 32.068000, 4.3, '06:30-19:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 1 AND name = '小李汤包(山西路店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 1, '徐建萍汤包(中山北路店)', '小吃', '鸡汁汤包,开洋干丝', 30.00, 118.771000, 32.066000, 4.5, '06:30-14:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 1 AND name = '徐建萍汤包(中山北路店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 1, '韩复兴(湖南路店)', '小吃', '盐水鸭,鸭油烧饼', 45.00, 118.779000, 32.073000, 4.5, '07:30-19:30', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 1 AND name = '韩复兴(湖南路店)');

INSERT INTO t_restaurant (destination_id, name, cuisine, signature_dish, avg_price, lng, lat, rating, business_hours, status)
SELECT 1, '绿柳居(太平南路店)', '素斋', '素菜包,罗汉观斋', 40.00, 118.789000, 32.024000, 4.5, '10:30-20:00', 1
WHERE NOT EXISTS (SELECT 1 FROM t_restaurant WHERE destination_id = 1 AND name = '绿柳居(太平南路店)');
