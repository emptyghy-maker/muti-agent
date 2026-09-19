# 03 多 Agent 设计

## 1. Agent 总览（模型分层更新）

| Agent | 模型 | 职责 | 输入 | 输出 |
|---|---|---|---|---|
| PreferenceAgent | **plus** | 首轮解析 + 缺口分析 + 问询收集偏好 | 用户回复 + 已有偏好状态 | 更新后的 TravelPreference + 下一个问题（含 options） |
| AttractionAgent | flash | 生成景点候选集 | 目的地 + 偏好 | 景点候选列表（名称+特色） |
| FoodAgent | flash | 生成美食候选集 | 目的地 + 偏好 + 已选景点 | 风味分组的美食候选 |
| HotelAgent | flash | 生成酒店候选集 | 已选景点/美食坐标 + 酒店偏好 | 酒店候选（名称+金额+评分） |
| ItineraryAgent | plus | 按天行程规划 / 局部修改 | 确认全集 + 规则引擎骨架（+ 原行程与调整诉求） | 按天行程文本 + 结构化节点 |

> **为什么 PreferenceAgent 用 plus**：首轮解析和缺口分析是整个体验的「信任起点」——解析不准 → 重复提问 → 用户流失。这是质量敏感环节，值得用更强模型；候选类 Agent 任务简单、容错空间大，flash 足够。
> **升级路径**：跑通后用 trace（耗时）+ 反馈数据（满意度）评估；若 plus 仍不够，再考虑 qwen-plus/max，或给单个 Agent 单独换模型（Agent 独立绑定 ChatModel Bean，改一行配置即可）。

## 2. 编排状态机（TravelOrchestrator）

```
INIT ──选目的地/首轮输入──▶ PREFERENCE ──缺失字段逐批问询──▶ 信息齐备
                                 │
                                 ├── 冲突检测失败 ──▶ CONFLICT ──二次确认──▶ 回 PREFERENCE 或继续
                                 │
                         齐备 ▶ ATTRACTIONS ─确认─▶ FOODS ─确认─▶ HOTELS ─确认─▶ ITINERARY ─▶ DONE
                                 │                  │            │             │
                               regenerate       regenerate   regenerate      │
                                (≤2次)           (≤2次)       (≤2次)          │
                                 │                                        │
                    DONE 态行程 ──用户输入调整诉求──▶ ADJUST ──局部重生成──▶ 新版本 DONE
```

- 会话状态存 Redis：`travel:session:{id}` = { stage, destination, preference（含字段三态）, askedFields, 各级候选集, 已选 ids, itineraryId }。
- 编排器职责：解析用户消息 → 判断阶段 → 调用 Agent/规则 → **校验输出** → 推进状态 → 组织响应。
- **Agent 互不直接通信**，产出全部经编排器交接（白名单校验、清洗、落会话）。
- 每次 Agent 调用接入 `TraceContext`（耗时 + token）。

## 3. PreferenceAgent 详细设计（体验核心）

### 3.1 输入输出契约
- `@SystemMessage("prompts/preference.txt")`，输入 =「已有偏好状态 JSON（含三态标记）+ 缺失字段清单 + 用户本轮回复」。
- 输出 JSON：
```json
{
  "updates": [ {"field":"days","value":3,"source":"USER"} ],
  "nextQuestion": {"field":"budget","text":"预算范围大概多少？","options":["2000以内","2000-4000","4000-6000","6000以上","还没想好"]},
  "conflict": null
}
```

### 3.2 防重复提问的三层机制

| 层 | 机制 |
|---|---|
| 状态层 | `TravelPreference` 每字段三态：MISSING / CONFIRMED / DEFAULTED；会话维护 `askedFields` 集合 |
| 提示词层 | 输入中明确给出「已确认字段、已默认字段、缺失字段清单」，命令 Agent **只可从缺失清单选问题** |
| 规则层（硬兜底） | Java 校验 nextQuestion.field：若不在缺失清单 → 丢弃该问题，按优先级取下一个缺失字段由规则直接生成问题（带固定 options）——LLM 想重复问也会被拦 |

### 3.3 三批优先级与 options 设计（前端渲染为按钮）

| 批次 | 字段 | 典型 options |
|---|---|---|
| 第一批 | 目的地 | 城市卡片/首轮自由输入 |
| 第一批 | 游玩天数 | [2天, 3天, 4天, 5天及以上] |
| 第一批 | 总预算 | [2000以内, 2000-4000, 4000-6000, 6000以上, 还没想好] |
| 第二批 | 人数 | [1人, 2人, 3-4人, 5人以上] |
| 第二批 | 景点偏好 | [打卡拍照, 娱乐项目, 两者都要, 按你推荐] |
| 第二批 | 美食口味 | [清淡, 辣, 本帮/本地菜, 都行] |
| 第三批 | 体力水平 | [体力好, 一般, 偏弱] |
| 第三批 | 酒店细节 | [性价比优先, 体验优先, 没想好] |
| 第三批 | 特殊需求 | 自由输入（必去地点等） |

每步最多问 2 个字段；「还没想好/都行」选项 → 置 DEFAULTED，由 DefaultsResolver 填默认值，**不再追问**。

### 3.4 默认值规则（DefaultsResolver，纯 Java）
- 预算未定：`(t_destination.daily_budget_min + max)/2 × 天数 × 人数`，明示「先按 X 元规划」。
- 景点类型未定：「打卡 60% + 娱乐 20% + 休闲 20%」。
- 天数未定：3 天 2 晚；体力未定：一般；酒店未定：性价比优先。

### 3.5 冲突检测与二次确认
- PreferenceAgent 输出 `conflict`（如预算与酒店要求矛盾）；编排器进入 CONFLICT 状态，把冲突选项化：
  「① 提高预算到 X；② 降低酒店档次；③ 按原预算由我推荐最优」——用户点选后回写偏好，继续流程。

## 4. 候选 Agent 设计（Attraction / Food / Hotel）

统一模式：**Java 初筛（SQL）→ LLM 筛选排序 → Java 白名单校验**。

- AttractionAgent：按 destination_id + category 初筛；LLM 依据「打卡/娱乐占比」排序，输出 `[{attractionId,name,feature,why}]`，数量 ≈ 天数×1.5~2。
- FoodAgent：按 cuisine 分组初筛 + 人均预算过滤；LLM 每组挑代表店铺，输出按风味分组的候选。
- HotelAgent：Java 计算已选地点几何中心，按距离取前 N；LLM 依据「性价比=评分/价格、体验=评分」排序，输出 `[{hotelId,name,pricePerNight,rating,distanceToCenter,why}]`。
- 校验：输出 id 必须 ∈ 初筛集；非法 id 剔除。每级 regenerate ≤2 次。

## 5. ItineraryAgent 设计（规划 + 修改双模式）

### 5.1 规划模式
- 输入：确认全集 + 规则引擎骨架（每天劳累分、饭点缺口、待插入休息点、交通与消费估算）。
- 输出：按天行程文本 + 严格 JSON 节点数组（type=transport/hotel/attraction/restaurant/rest、placeId、名称、时段、说明）。
- 硬约束在提示词中列为「必须遵守」，Java 解析后二次校验（餐点存在性、休息点位置）。

### 5.2 修改模式（ADJUST）
- 触发：DONE 态行程 + 用户调整诉求（「第二天太赶，删一个景点」）。
- 输入：原 plan_json + 调整诉求 + 原偏好。
- 输出：新版本行程（只改受影响部分，整体风格一致）；落库 version+1、parent_id 指向上版。
- 不重新问询、不重新确认候选。

## 6. 规则引擎（rule 包，纯 Java 可单测）

### FatigueScorer（操劳程度，参数可配）
```
某天基础分 = Σ(景点强度1~5 × 建议时长) × 体力系数 + 交通时长 × 1.5
  体力系数：好=0.8 / 一般=1.0 / 偏弱=1.3（来自用户自报）
综合分 = 当天基础分 + 0.5×前一天基础分 + 0.3×后一天基础分
阈值 > 9 → 插入休息点
```
- 权重（0.5/0.3）为初始经验值，配置化；**用户真实体感通过反馈进入系统**（节奏评分「太赶」→ 迭代期调阈值/强度表）。

### MealTimeChecker
午餐 11:30–13:30、晚餐 17:30–19:30 窗口内必须有 restaurant 节点；缺失 → 规则选最近顺路饭店插入候选位置。

### BudgetCalculator
单日 = Σ门票 + Σ餐费(人均×人数) + 酒店 + 交通；全程 vs 预算 → 超支额 + 降级建议。

### DefaultsResolver
见 3.4。

## 7. 反馈闭环（评价体系的数据流）

```
用户提交评分/标签/评论 + 隐式信号(换一批次数/修改次数)
  → t_itinerary_feedback 落库
  → 统计查询（按维度聚合：节奏满意度、预算偏差率、regenerate 频率）
  → 迭代期人工分析 → 调整：疲劳阈值/饭点窗口(yml 参数)、提示词、候选排序权重、种子数据(补类型/城市)
```

## 8. 提示词文件规划

| 文件 | 要点 |
|---|---|
| `preference.txt` | 字段清单与三态、只问缺失字段、每次≤2问、options 输出、冲突检测、JSON 格式 |
| `attraction.txt` / `food.txt` / `hotel.txt` | 只可用给定 id、排序口径、JSON 格式 |
| `itinerary.txt` | 按天组织、饭点硬约束、休息点、消费汇总、天气提醒句、JSON 格式 |
| `itinerary_adjust.txt` | 修改模式：基于原行程做局部调整、保持风格一致、JSON 格式 |
