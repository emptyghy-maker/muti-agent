# 05 API 接口设计

> 前缀 `/api/v1/travel`；鉴权沿用 JWT（Authorization header；SSE 接口 token 走 `?token=` 查询参数，EventSource 不能带 header——旧项目经验）。

## 1. 会话与对话（核心）

### 1.1 创建会话（选目的地）
`POST /api/v1/travel/session`
```json
请求：{ "destinationId": 3 }
响应：{ "code":0, "data": {
  "sessionId": "s_xxx",
  "stage": "PREFERENCE",
  "message": "你好，我是你的旅游攻略智能规划助手，请向我提供一些你的规划想法，让我帮你进行初步规划（如：想去哪里玩、规划几天旅途、预算范围）",
  "preference": { "destinationId": 1, "days": null, "totalBudget": null }
}}
```

### 1.2 对话（同步）
`POST /api/v1/travel/chat/sync`
```json
请求：{ "sessionId": "s_xxx", "message": "3天2晚，预算4000" }
响应：{ "code":0, "data": {
  "stage": "PREFERENCE",                  // 状态机推进后的阶段（未齐备则继续问询）
  "message": "收到！喜欢什么类型的景点？",
  "question": {                           // 下一步要问的问题（前端渲染为按钮）
    "field": "attractionType",
    "text": "喜欢什么类型的景点？",
    "options": ["打卡拍照", "娱乐项目", "两者都要", "按你推荐"]
  },
  "preference": { "days":3, "totalBudget":4000, "attractionType": null }   // 各字段带状态标记
}}
```

### 1.3 对话（SSE 流式，行程生成用）
`GET /api/v1/travel/chat/stream?sessionId=s_xxx&message=确认&token=xxx`

事件类型（data 为 JSON）：
```json
{"type":"stage","stage":"ITINERARY","message":"正在规划行程…"}
{"type":"preference","preference":{...}}
{"type":"candidates","candidateType":"ATTRACTION","items":[{"attractionId":1,"name":"断桥残雪","feature":"西湖经典打卡机位"}]}
{"type":"itinerary_delta","delta":"第一天：启程…"}     // 行程文本逐段推送
{"type":"itinerary","plan":{...}}                       // 结构化每日节点（超链接数据源）
{"type":"done","status":"SUCCESS","itineraryId":9}
{"type":"error","message":"…"}
```

### 1.4 确认候选集
`POST /api/v1/travel/candidates/confirm`
```json
请求：{
  "sessionId": "s_xxx",
  "candidateType": "ATTRACTION",          // ATTRACTION | FOOD | HOTEL
  "selectedIds": [1, 3, 5]
}
响应：{ "code":0, "data": { "stage": "FOODS", "message": "已确认景点！接下来是美食…" } }
```
> 确认景点后由编排器触发美食候选；确认美食后触发酒店候选；确认酒店后触发行程生成。支持 `action:"regenerate"` 换一批。

## 2. 行程与路径

### 2.1 查看行程
`GET /api/v1/travel/itinerary/{sessionId}`
```json
响应 data：{
  "itineraryId": 9, "destinationName": "南京", "days": 3,
  "totalBudget": 4000, "totalCost": 3860,
  "weatherTip": "行程以室外景点为主，如遇雨天建议调整出行日期或替换为室内景点（如 XX 博物馆）",
  "text": "第一天：启程 → 南京南站 → 酒店（放行李）…",   // 前端渲染时把地点转超链接
  "plan": { "days": [ { "dayIndex":1, "nodes":[...] } ] }   // 结构化，见 04 的 plan_json
}
```

> 详情页按 `itineraryId` 从 MySQL 加载（不依赖 Redis 会话），刷新/重进不丢。
```

### 2.2 地点间路径（超链接点击）
`GET /api/v1/travel/route?sessionId=s_xxx&fromNodeSeq=3&toNodeSeq=4`
```json
响应 data：{
  "from": {"name":"xx餐厅","lng":120.15,"lat":30.25},
  "to":   {"name":"断桥残雪","lng":120.16,"lat":30.26},
  "distanceKm": 1.8,
  "options": [
    {"mode":"步行","durationMin":22,"cost":0,"steps":["从 xx餐厅 出发…","沿 xx路 步行 1.8km 到达"]},
    {"mode":"打车","durationMin":8,"cost":13,"steps":["网约车约 8 分钟"]}
  ]
}
```
> 后端实现：`RoutePlanner.plan(from, to)`（SimpleRoutePlanner 默认 / AmapRoutePlanner 预留，见 02 选型 D2）。

**详情页路径查询**（行程已落库、Redis 会话可能过期，超链接依然可用）：
`GET /api/v1/travel/route/itinerary?itineraryId=9&day=1&fromSeq=2&toSeq=3`
> 响应结构与 2.2 相同；后端按 `itineraryId` 从 MySQL 读 `plan_json` 定位节点，不依赖会话。

### 2.3 我的行程列表
`GET /api/v1/travel/itineraries`
```json
响应 data：[ { "itineraryId":9, "destinationName":"南京", "title":"南京3天2晚攻略",
               "days":3, "totalCost":3860, "version":1, "createdAt":"2026-09-10 14:00" } ]
```
> 按当前用户过滤；前端「我的行程」入口由此加载。

### 2.4 修改行程（局部调整，ADJUST 模式）
`POST /api/v1/travel/itinerary/{id}/adjust`
```json
请求：{ "message": "第二天太赶，删掉一个景点" }
响应：{ "code":0, "data": { "itineraryId":10, "version":2, "parentId":9,
         "text":"…", "plan":{...} } }   // 新版本行程，不重新走问询/候选
```

### 2.5 提交评价（反馈体系）
`POST /api/v1/travel/itinerary/{id}/feedback`
```json
请求：{ "rating":4, "paceRating":2, "attractionSatisfy":5, "foodSatisfy":4,
       "hotelSatisfy":3, "budgetFit":"刚好", "tags":["太赶了"], "comment":"第二天行程偏满" }
响应：{ "code":0, "data": null }
```
> 隐式反馈（换一批次数、修改次数）由后端在候选确认与 adjust 接口内自动记录到反馈统计。

## 3. 基础查询

| 接口 | 说明 |
|---|---|
| `GET /api/v1/travel/destinations` | 目的地列表（名称/简介/标签） |
| `GET /api/v1/travel/attractions?destinationId=` | 景点全量查询（调试/管理用，可带 category 过滤） |
| `GET /api/v1/travel/restaurants?destinationId=&cuisine=` | 美食查询 |
| `GET /api/v1/travel/hotels?destinationId=` | 酒店查询 |
| `GET /api/v1/travel/itineraries` | 当前用户的行程历史列表 |

## 4. 鉴权与权限

- 所有 `/api/v1/travel/**` 需要登录（沿用 SecurityConfig `.anyRequest().authenticated()`）。
- 行程按 `user_id` 隔离：查看行程时校验归属（当前内存用户 admin/analyst，后续升级 DB 用户表）。
- 每个规划会话绑定 userId，创建会话时从 SecurityContext 取当前用户（沿用 `AuthenticatedUser`）。

## 5. 错误码约定（复用 ResultCode，按需新增）

| code | 含义 |
|---|---|
| 40001 | 参数错误（如 destinationId 不存在） |
| 40100 | 未授权 |
| 50002 | LLM 调用失败（降级提示） |
| 50003 | 会话不存在或已过期（Redis 中无 sessionId） |
| 50004 | 候选集为空（提示调整偏好） |

## 6. 流式与并发的处理要点

- SSE 走 `chatStreamExecutor` 线程池（沿用 AsyncConfig），控制器返回 SseEmitter 立即返回。
- 行程生成阶段：ItineraryAgent 用 StreamingChatModel 逐段推送 `itinerary_delta`，完成后推结构化 `plan` + 落库。
- 同一会话并发对话：Redis 会话加简单乐观锁（版本号）或按 sessionId 串行化（阶段开发时先串行，避免状态错乱）。
- 复用 `RequestLogFilter` 与 `TraceController` 做全链路观测。
