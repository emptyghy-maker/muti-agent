package com.ghy.mutiagent.service.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.GeoUtils;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.RouteResult;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.UsageChannel;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.entity.Itinerary;
import com.ghy.mutiagent.repository.mapper.ItineraryMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.rule.PlaceKeyResolver;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.service.TravelSessionService;
import com.ghy.mutiagent.service.UsageService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 路径查询服务：行程中某两个节点之间的具体路径方案。
 * 支持两种来源：会话内行程（规划中）与已落库行程（详情页刷新后仍可查）。
 */
@Service
public class RouteService {

    private final TravelSessionService sessionService;
    private final AttractionMapper attractionMapper;
    private final RestaurantMapper restaurantMapper;
    private final HotelMapper hotelMapper;
    private final ItineraryMapper itineraryMapper;
    private final RoutePlanner routePlanner;
    private final ObjectMapper objectMapper;
    private final UsageService usageService;
    /** S10 路线事实服务（可选装配）：装配后详情与排程/计费共享同一路线事实快照 */
    private RouteFactService routeFactService;

    public void setRouteFactService(RouteFactService routeFactService) {
        this.routeFactService = routeFactService;
    }

    public RouteService(TravelSessionService sessionService,
                        AttractionMapper attractionMapper,
                        RestaurantMapper restaurantMapper,
                        HotelMapper hotelMapper,
                        ItineraryMapper itineraryMapper,
                        RoutePlanner routePlanner,
                        ObjectMapper objectMapper,
                        UsageService usageService) {
        this.sessionService = sessionService;
        this.attractionMapper = attractionMapper;
        this.restaurantMapper = restaurantMapper;
        this.hotelMapper = hotelMapper;
        this.itineraryMapper = itineraryMapper;
        this.routePlanner = routePlanner;
        this.objectMapper = objectMapper;
        this.usageService = usageService;
    }

    public RouteResult route(AuthenticatedUser actor, String sessionId, int dayIndex, int fromSeq, int toSeq) {
        TravelState state = sessionService.loadOwned(sessionId, actor.id());
        if (state.getPlan() == null || state.getPlan().getDays() == null) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        RouteResult result = planRoute(state.getPlan(), dayIndex, fromSeq, toSeq);
        usageService.recordOp(state.getSessionId(), state.getUserId(), state.getUsername(),
                "ROUTE", "路径推荐", "SUCCESS", routeRemark(result), UsageChannel.KB);
        return result;
    }

    /** 基于已落库行程的路径查询（详情页超链接，刷新后依然可用）；仅行程所有者可查 */
    public RouteResult routeByItinerary(AuthenticatedUser actor, Long itineraryId, int dayIndex,
                                        int fromSeq, int toSeq) {
        Itinerary it = itineraryMapper.selectById(itineraryId);
        if (it == null || it.getUserId() == null || !it.getUserId().equals(actor.id())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        if (it.getPlanJson() == null) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        try {
            ItineraryPlan plan = objectMapper.readValue(it.getPlanJson(), ItineraryPlan.class);
            RouteResult result = planRoute(plan, dayIndex, fromSeq, toSeq);
            usageService.recordOp("itin-" + itineraryId, actor.id(), actor.username(),
                    "ROUTE", "路径推荐", "SUCCESS", routeRemark(result), UsageChannel.KB);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
    }

    private String routeRemark(RouteResult result) {
        int n = result.getOptions() == null ? 0 : result.getOptions().size();
        return result.getFromName() + " → " + result.getToName() + "（" + n + " 种方案）";
    }

    private RouteResult planRoute(ItineraryPlan plan, int dayIndex, int fromSeq, int toSeq) {
        RouteFactSnapshot facts = routeFactService == null ? null
                : new RouteFactSnapshot(routeFactService, java.time.LocalDate.now().toString(), 0);
        return planRoute(plan, dayIndex, fromSeq, toSeq, facts);
    }

    /**
     * S10：携带共享事实快照的路径规划——详情、排程、计费消费同一快照，
     * 同一段行程的 durationMin/cost/factId 完全一致。
     */
    public RouteResult planRoute(ItineraryPlan plan, int dayIndex, int fromSeq, int toSeq,
                                 RouteFactSnapshot facts) {
        DailyPlan day = plan.getDays().stream()
                .filter(d -> d.getDayIndex() == dayIndex)
                .findFirst()
                .orElseThrow(() -> new BizException(ResultCode.PARAM_ERROR));
        PlanNode from = node(day, fromSeq);
        PlanNode to = node(day, toSeq);
        double[] fc = coordsOf(from);
        double[] tc = coordsOf(to);
        if (fc == null || tc == null) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }

        RouteResult r = new RouteResult();
        r.setFromName(from.getName());
        r.setToName(to.getName());
        double km = GeoUtils.distanceKm(fc[0], fc[1], tc[0], tc[1]);
        r.setDistanceKm(Math.round(km * 10) / 10.0);
        if (facts != null) {
            Optional<PlaceKey> a = PlaceKeyResolver.fromNode(from);
            Optional<PlaceKey> b = PlaceKeyResolver.fromNode(to);
            if (a.isPresent() && b.isPresent()) {
                com.ghy.mutiagent.model.RouteFact fact = facts.factFor(a.get(), b.get(), fc, tc);
                if (fact != null) {
                    r.setFactId(fact.factId());
                    r.setDurationMin(fact.durationMin());
                    r.setTransportCost(fact.cost());
                    r.setFactKind(fact.kind());
                    r.setFactSource(fact.source());
                    r.setFactConfidence(fact.confidence());
                }
            }
        }
        r.setOptions(routePlanner.plan(fc[0], fc[1], tc[0], tc[1]));
        return r;
    }

    private PlanNode node(DailyPlan day, int seq) {
        return day.getNodes().stream()
                .filter(n -> n.getSeq() != null && n.getSeq() == seq)
                .findFirst()
                .orElseThrow(() -> new BizException(ResultCode.PARAM_ERROR));
    }

    private double[] coordsOf(PlanNode n) {
        // 唯一类型转换规则：PlaceKeyResolver；再按实体类型查对应表
        Optional<PlaceKey> key = PlaceKeyResolver.fromNode(n);
        if (key.isEmpty()) {
            return null;
        }
        return switch (key.get().type()) {
            case ATTRACTION -> {
                var a = attractionMapper.selectById(key.get().id());
                yield a == null ? null : new double[]{a.getLng(), a.getLat()};
            }
            case RESTAURANT -> {
                var r = restaurantMapper.selectById(key.get().id());
                yield r == null ? null : new double[]{r.getLng(), r.getLat()};
            }
            case HOTEL -> {
                var h = hotelMapper.selectById(key.get().id());
                yield h == null ? null : new double[]{h.getLng(), h.getLat()};
            }
        };
    }
}
