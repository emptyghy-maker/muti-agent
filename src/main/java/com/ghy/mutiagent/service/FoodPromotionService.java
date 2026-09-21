package com.ghy.mutiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.entity.Hotel;
import com.ghy.mutiagent.repository.entity.PoiRecommendEvent;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.repository.entity.WebFoodAudit;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.PoiRecommendEventMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.repository.mapper.WebFoodAuditMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * POI 推荐计数与自动晋升（功能隔离组件）：网搜 POI（美食/景点/酒店）被反复推荐/勾选后
 * 自动晋升为知识库（KB_PROMOTED）。三通道完全对称，仅数据表/列不同。
 * - 事件唯一键 (place_type, restaurant_id, event_type, session_id)：同一会话同一地点同一事件只计一次（防重复计数/防刷）；
 * - 计数口径：RECOMMEND=被纳入候选池展示给用户；SELECT=被用户勾选确认；均按「不同会话数」计；
 * - 晋升：推荐 ≥ recommend-threshold 次 或 勾选 ≥ select-threshold 次，且通过质量门槛
 *   （tags/address 齐全；美食通道额外要求 lng/lat）→ source: WEB_SEARCH → KB_PROMOTED，
 *   写 t_web_food_audit PROMOTE 事件（place_type 区分类型）；
 * - 跨会话复用：同目的地、被推荐过且最近 reuse-freshness-days 天内的网搜 POI 可复用，
 *   避免同需求重复付费搜索。
 * 未装配本组件（手动构造的进程/门禁）时，调用方保持既有行为，完全隔离；
 * 仅装配美食 Mapper（测试/门禁两参构造）时，景点/酒店通道自动降级为不统计。
 */
@Service
public class FoodPromotionService {

    public static final String EVENT_RECOMMEND = "RECOMMEND";
    public static final String EVENT_SELECT = "SELECT";
    public static final String SOURCE_WEB_SEARCH = "WEB_SEARCH";
    public static final String SOURCE_KB_PROMOTED = "KB_PROMOTED";

    public static final String TYPE_FOOD = "FOOD";
    public static final String TYPE_ATTRACTION = "ATTRACTION";
    public static final String TYPE_HOTEL = "HOTEL";

    private static final Logger log = LoggerFactory.getLogger(FoodPromotionService.class);

    private final PoiRecommendEventMapper eventMapper;
    private final RestaurantMapper restaurantMapper;
    private final AttractionMapper attractionMapper;
    private final HotelMapper hotelMapper;

    /** 审计 Mapper（可空：未装配时跳过审计写入，不阻塞主流程） */
    @Autowired(required = false)
    private WebFoodAuditMapper auditMapper;

    @Value("${food-promotion.recommend-threshold:3}")
    private int recommendThreshold;
    @Value("${food-promotion.select-threshold:2}")
    private int selectThreshold;
    @Value("${food-promotion.reuse-freshness-days:90}")
    private int reuseFreshnessDays;

    /** 测试/门禁用装配（仅美食通道；景点/酒店通道降级为不统计，保持既有行为） */
    public FoodPromotionService(PoiRecommendEventMapper eventMapper, RestaurantMapper restaurantMapper) {
        this(eventMapper, restaurantMapper, null, null);
    }

    @Autowired
    public FoodPromotionService(PoiRecommendEventMapper eventMapper, RestaurantMapper restaurantMapper,
                                AttractionMapper attractionMapper, HotelMapper hotelMapper) {
        this.eventMapper = eventMapper;
        this.restaurantMapper = restaurantMapper;
        this.attractionMapper = attractionMapper;
        this.hotelMapper = hotelMapper;
    }

    /** 记录一次美食推荐展示（幂等：同一会话同一店只计一次）。返回 true 表示本次为新事件且计数已 +1。 */
    public boolean recordRecommend(String sessionId, Long restaurantId, Long destinationId) {
        return record(TYPE_FOOD, EVENT_RECOMMEND, sessionId, restaurantId, destinationId);
    }

    /** 记录一次用户勾选美食（幂等：同一会话同一店只计一次）。返回 true 表示本次为新事件且计数已 +1。 */
    public boolean recordSelect(String sessionId, Long restaurantId, Long destinationId) {
        return record(TYPE_FOOD, EVENT_SELECT, sessionId, restaurantId, destinationId);
    }

    /** 景点/酒店通道：记录一次推荐展示（placeType = ATTRACTION / HOTEL；三通道对称） */
    public boolean recordRecommend(String placeType, String sessionId, Long poiId, Long destinationId) {
        return record(normalizeType(placeType), EVENT_RECOMMEND, sessionId, poiId, destinationId);
    }

    /** 景点/酒店通道：记录一次用户勾选（placeType = ATTRACTION / HOTEL；三通道对称） */
    public boolean recordSelect(String placeType, String sessionId, Long poiId, Long destinationId) {
        return record(normalizeType(placeType), EVENT_SELECT, sessionId, poiId, destinationId);
    }

    /** 美食通道晋升判定（阈值 + 质量门槛）；接口名保留既有语义 */
    public void maybePromote(String sessionId, Long restaurantId) {
        maybePromote(TYPE_FOOD, sessionId, restaurantId);
    }

    /** 阈值 + 质量门槛 → 晋升；门槛不满足或字段不齐时保留 WEB_SEARCH 继续计数 */
    public void maybePromote(String placeType, String sessionId, Long poiId) {
        String type = normalizeType(placeType);
        PoiView poi = loadPoi(type, poiId);
        if (poi == null || !SOURCE_WEB_SEARCH.equals(poi.source())) {
            return;
        }
        int rec = poi.recommendCount() == null ? 0 : poi.recommendCount();
        int sel = poi.selectCount() == null ? 0 : poi.selectCount();
        if (rec < recommendThreshold && sel < selectThreshold) {
            return;
        }
        String skip = qualityGateReason(poi, type);
        if (skip != null) {
            audit(poi, type, sessionId, "PROMOTE_SKIPPED", skip,
                    "recommend=" + rec + ";select=" + sel);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String note = (poi.sourceNote() == null ? "" : poi.sourceNote())
                + " 网搜验证入库（推荐" + rec + "次/勾选" + sel + "次）";
        if (updatePromoted(type, poiId, now, note) > 0) {
            log.info("[Promotion] {} {}（{}）网搜验证入库：推荐 {} 次 / 勾选 {} 次",
                    type, poi.name(), poi.id(), rec, sel);
            audit(poi, type, sessionId, "PROMOTE", null,
                    "recommend=" + rec + ";select=" + sel);
        }
    }

    /**
     * 跨会话可复用网搜店（美食）：同目的地、WEB_SEARCH、已被推荐过且最近 reuse-freshness-days 天内。
     * 未被推荐过的网搜店（其他会话搜索但未进候选池）不跨会话复用，保持会话内语义。
     */
    public List<Restaurant> reusableWebRestaurants(Long destinationId, LocalDateTime now) {
        if (restaurantMapper == null || destinationId == null) {
            return List.of();
        }
        return restaurantMapper.selectList(new QueryWrapper<Restaurant>()
                .eq("destination_id", destinationId)
                .eq("source", SOURCE_WEB_SEARCH)
                .eq("status", 1)
                .isNotNull("last_recommended_at")
                .ge("last_recommended_at", cutoff(now))
                .last("LIMIT 20"));
    }

    /** 跨会话可复用网搜景点（与美食同口径；未装配景点 Mapper 时返回空，功能隔离） */
    public List<Attraction> reusableWebAttractions(Long destinationId, LocalDateTime now) {
        if (attractionMapper == null || destinationId == null) {
            return List.of();
        }
        return attractionMapper.selectList(new QueryWrapper<Attraction>()
                .eq("destination_id", destinationId)
                .eq("source", SOURCE_WEB_SEARCH)
                .eq("status", 1)
                .isNotNull("last_recommended_at")
                .ge("last_recommended_at", cutoff(now))
                .last("LIMIT 20"));
    }

    /** 跨会话可复用网搜酒店（与美食同口径；未装配酒店 Mapper 时返回空，功能隔离） */
    public List<Hotel> reusableWebHotels(Long destinationId, LocalDateTime now) {
        if (hotelMapper == null || destinationId == null) {
            return List.of();
        }
        return hotelMapper.selectList(new QueryWrapper<Hotel>()
                .eq("destination_id", destinationId)
                .eq("source", SOURCE_WEB_SEARCH)
                .eq("status", 1)
                .isNotNull("last_recommended_at")
                .ge("last_recommended_at", cutoff(now))
                .last("LIMIT 20"));
    }

    // ==================== 内部：三类型共用核心 ====================

    /** 三类型 POI 的统一视图（列名不同，语义相同） */
    private record PoiView(Long id, Long destinationId, String name, String secondary, BigDecimal price,
                           String address, String tags, String source, String sourceNote,
                           Integer recommendCount, Integer selectCount, Double lng, Double lat) {
    }

    private static String normalizeType(String placeType) {
        return placeType == null || placeType.isBlank() ? TYPE_FOOD : placeType;
    }

    private boolean record(String placeType, String eventType, String sessionId, Long poiId, Long destinationId) {
        if (eventMapper == null || poiId == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        PoiView poi = loadPoi(placeType, poiId);
        if (poi == null || !SOURCE_WEB_SEARCH.equals(poi.source())) {
            // 只统计网搜行：KB 种子无需晋升，事件表不记噪声
            return false;
        }
        PoiRecommendEvent e = new PoiRecommendEvent();
        e.setRestaurantId(poiId);
        e.setPlaceType(placeType);
        e.setDestinationId(destinationId);
        e.setEventType(eventType);
        e.setSessionId(sessionId);
        e.setCreatedAt(LocalDateTime.now());
        try {
            if (eventMapper.insert(e) <= 0) {
                return false;
            }
        } catch (DuplicateKeyException dup) {
            // 唯一键去重：同会话同地点同事件已计过，静默忽略
            return false;
        }
        incrementCounter(placeType, poiId, eventType);
        maybePromote(placeType, sessionId, poiId);
        return true;
    }

    private void incrementCounter(String placeType, Long poiId, String eventType) {
        String sql = EVENT_RECOMMEND.equals(eventType)
                ? "recommend_count = recommend_count + 1, last_recommended_at = NOW(3)"
                : "select_count = select_count + 1";
        // 字符串列名（不依赖 lambda 缓存）：手动装配的测试进程同样可用
        if (TYPE_ATTRACTION.equals(placeType) && attractionMapper != null) {
            attractionMapper.update(null, new UpdateWrapper<Attraction>().eq("id", poiId).setSql(sql));
        } else if (TYPE_HOTEL.equals(placeType) && hotelMapper != null) {
            hotelMapper.update(null, new UpdateWrapper<Hotel>().eq("id", poiId).setSql(sql));
        } else if (restaurantMapper != null) {
            restaurantMapper.update(null, new UpdateWrapper<Restaurant>().eq("id", poiId).setSql(sql));
        }
    }

    private int updatePromoted(String placeType, Long poiId, LocalDateTime now, String note) {
        String clipped = UsageService.clip(note, 500);
        if (TYPE_ATTRACTION.equals(placeType) && attractionMapper != null) {
            return attractionMapper.update(null, new UpdateWrapper<Attraction>()
                    .eq("id", poiId)
                    .eq("source", SOURCE_WEB_SEARCH) // 并发下晋升只生效一次
                    .set("source", SOURCE_KB_PROMOTED)
                    .set("promoted_at", now)
                    .set("source_note", clipped));
        }
        if (TYPE_HOTEL.equals(placeType) && hotelMapper != null) {
            return hotelMapper.update(null, new UpdateWrapper<Hotel>()
                    .eq("id", poiId)
                    .eq("source", SOURCE_WEB_SEARCH)
                    .set("source", SOURCE_KB_PROMOTED)
                    .set("promoted_at", now)
                    .set("source_note", clipped));
        }
        if (restaurantMapper != null) {
            return restaurantMapper.update(null, new UpdateWrapper<Restaurant>()
                    .eq("id", poiId)
                    .eq("source", SOURCE_WEB_SEARCH)
                    .set("source", SOURCE_KB_PROMOTED)
                    .set("promoted_at", now)
                    .set("source_note", clipped));
        }
        return 0;
    }

    /** 晋升质量门槛：网搜数据常缺 tags，字段不齐不晋升（继续计数，等待补全）。
     *  网搜景点/酒店不采集坐标：坐标门槛仅美食通道保留（既有口径不变），其余类型 tags/address 齐备即可。 */
    private String qualityGateReason(PoiView poi, String placeType) {
        if (poi.tags() == null || poi.tags().isBlank()) {
            return "MISSING_TAGS";
        }
        if (poi.address() == null || poi.address().isBlank()) {
            return "MISSING_ADDRESS";
        }
        if (TYPE_FOOD.equals(placeType) && (poi.lng() == null || poi.lat() == null)) {
            return "MISSING_COORDS";
        }
        return null;
    }

    private PoiView loadPoi(String placeType, Long poiId) {
        if (TYPE_ATTRACTION.equals(placeType)) {
            if (attractionMapper == null) {
                return null;
            }
            Attraction a = attractionMapper.selectById(poiId);
            return a == null ? null : new PoiView(a.getId(), a.getDestinationId(), a.getName(), a.getCategory(),
                    a.getTicketPrice(), a.getAddress(), a.getTags(), a.getSource(), a.getSourceNote(),
                    a.getRecommendCount(), a.getSelectCount(), a.getLng(), a.getLat());
        }
        if (TYPE_HOTEL.equals(placeType)) {
            if (hotelMapper == null) {
                return null;
            }
            Hotel h = hotelMapper.selectById(poiId);
            return h == null ? null : new PoiView(h.getId(), h.getDestinationId(), h.getName(), h.getLevel(),
                    h.getPricePerNight(), h.getAddress(), h.getTags(), h.getSource(), h.getSourceNote(),
                    h.getRecommendCount(), h.getSelectCount(), h.getLng(), h.getLat());
        }
        if (restaurantMapper == null) {
            return null;
        }
        Restaurant r = restaurantMapper.selectById(poiId);
        return r == null ? null : new PoiView(r.getId(), r.getDestinationId(), r.getName(), r.getCuisine(),
                r.getAvgPrice(), r.getAddress(), r.getTags(), r.getSource(), r.getSourceNote(),
                r.getRecommendCount(), r.getSelectCount(), r.getLng(), r.getLat());
    }

    private LocalDateTime cutoff(LocalDateTime now) {
        return now.minusDays(Math.max(1, reuseFreshnessDays));
    }

    private void audit(PoiView poi, String placeType, String sessionId, String action, String reason, String raw) {
        if (auditMapper == null) {
            return;
        }
        try {
            WebFoodAudit a = new WebFoodAudit();
            a.setSessionId(sessionId);
            a.setDestinationId(poi.destinationId());
            a.setPlaceType(placeType);
            a.setPlaceId(poi.id());
            a.setName(UsageService.clip(poi.name(), 128));
            a.setCuisine(UsageService.clip(poi.secondary(), 32));
            a.setAvgPrice(poi.price());
            a.setAddress(UsageService.clip(poi.address(), 128));
            a.setAction(action);
            a.setRejectReason(UsageService.clip(reason, 255));
            a.setRawPayload(UsageService.clip(raw, 500));
            a.setCreatedAt(LocalDateTime.now());
            auditMapper.insert(a);
        } catch (Exception e) {
            log.warn("晋升审计写入失败（{}）：{}", poi.name(), e.getClass().getSimpleName());
        }
    }
}
