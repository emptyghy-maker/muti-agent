# Agent 输入输出规范（唯一标准）

> 本文档是 LLM 输入输出格式的**唯一规范**。新增 Agent、修改提示词或解析逻辑时，必须先对照本文档；
> 解析行为的唯一代码实现是 `service/AgentOutputParser.java` + `common/JsonUtils.java`，禁止在业务代码里另写解析。

## 1. 候选类 Agent 统一输出信封（景点 / 美食 / 酒店）

三个候选类 Agent 的输出结构完全一致：

```json
{"items": [...], "advice": "可选文本"}
```

| 元素 | 必填 | 说明 |
|---|---|---|
| items | 是 | 数组，元素按各 Agent 的 items 元素要求（见下） |
| advice | 否 | 推荐方法建议，≤60 字；没有特殊要求时输出 `""` |

**硬性约束（写入公共提示词 prompts/common-format.txt）**：
- 只输出一个 json 对象本体，以 `{` 开头、以 `}` 结尾；禁止解释、思考过程、代码围栏、markdown；
- 整个输出 ≤400 字；
- **禁止任何模板之外的键**（selectedXxx / recommendations / excludedReasons / budgetAnalysis / summary / reason 等），
  禁止输出未要求输出的字段（名称、价格、评分等）。

### items 元素要求

| Agent | 元素结构 | 字段约束 |
|---|---|---|
| 景点 | `{"attractionId":数字,"feature":"一句话特色","why":"推荐理由"}` | why ≤30 字；只输出这三个字段 |
| 美食 | `{"restaurantId":数字}` | 只输出这一个字段（**扁平化**，不做 cuisine 分组——分组由 Java 侧按数据库数据完成） |
| 酒店 | `{"hotelId":数字,"why":"推荐理由"}` | why ≤30 字；只输出这两个字段 |

## 2. 特例 Agent 输出格式

| Agent | 格式 |
|---|---|
| 行程规划 | `{"days":[{"dayIndex":1,"theme":"一句话","nodes":[{"type":"transport","placeId":null,"time":"10:00","note":"抵达"}]}]}`；节点只输出 type/placeId/time/note 四个字段，theme/note ≤15 字 |
| 偏好解析 | `{"updates":{"字段":"值"},"conflict":null}`；updates 只包含用户明确提到的字段 |
| 需求分析 | `{"mode":"workflow"或"agent","focus":["关注点"],"brief":"一句话","needs":{"path":3,"cost":3,"sightseeing":3,"food":3}}`；needs 取值 1~5 |

## 3. 输入格式（Java 生成的候选池 JSON）

候选池由 Java 序列化生成，字段固定如下（数组元素）：

| 类型 | 字段 |
|---|---|
| 景点 | id, name, category, features, intensity, hours, price, rating |
| 美食 | id, name, cuisine, avgPrice, signatureDish, rating |
| 酒店 | id, name, price, rating, level, features, distanceKm |

## 4. 统一解析规则（所有 Agent 输出通用，唯一实现）

解析顺序固定，全部内建于 `JsonUtils` + `AgentOutputParser`：

1. **宽容解析**：未知字段忽略（FAIL_ON_UNKNOWN_PROPERTIES=false）、尾逗号、无引号字段名；
2. **截断修复**：输出被 max_tokens 截断时自动补全字符串/括号后重试；
3. **信封解析**：优先取 `items` 键；元素不像 id 对象时（如旧版分组输出）改为按内容查找；
4. **按内容识别**：找不到 items 时，广度搜索第一个「元素带 id 字段的对象数组」（键名无关，
   模型自创 selectedXxx/recommendedXxx 等键名也能正确接住）；
5. **advice 提取顺序**：`advice` → `budgetAnalysis.note` → `summary.budgetEstimate.note` →
   模型拼错 advice 键（adave/adife 等）时按 `ad` 前缀的字符串字段兜底；
6. **嵌套兜底**：结果被包进别的结构时，自动查找含 `mode`（需求分析）或 `days`（行程）的对象再解析；
7. **id 白名单 + 信息回填**：解析出的 id 必须落在候选池内（防幻觉），名称/价格等完整信息由 Java 按 id 回填；
8. **候选数量**：美食目标数 = `max(15, (早餐 3 + 正餐 6) × 天数)`，上限为候选池大小
   （application-dev.yml 的 `candidate.food` 可调早餐/正餐/保底值）；AI 精挑不足目标数时用规则池补足；
   酒店 AI 精挑不足 6 家时同样用规则池补足。

## 5. 行程时间规范（ScheduleBuilder 确定性重算）

LLM 只负责节点**顺序**；每个节点的到达/出发时间由 `rule/ScheduleBuilder.java` 重算（唯一实现）：

- **通勤分钟** = max(15, 距离 ÷ 速度 × 60 + 10 分钟缓冲)；≤1km 步行 4km/h、1~8km 公交 20km/h、>8km 30km/h；无坐标节点固定 30 分钟（出站/取行李）；
- **停留分钟**：景点 = suggestHours（最少 60）、餐厅 = 90、休息点 = 60、交通/酒店 = 0；
- **饭点锚定**：午餐 11:30-13:30、晚餐 17:30-19:30（note 标注 午餐/晚餐）；到早则顺延出发、准点开饭（出发 = 到达 − 通勤），到晚保留真实时间并告警；
- 时间单调递增（前向推进），相邻节点不会重叠；`PlanNode.departTime / travelMinutes / durationMinutes` 由重算写入并随 plan_json 落库；
- 行程提示词（itinerary.txt）规则 6/7：时间仅作顺序参考、相邻节点就近、restaurant 的 note 必须标注 午餐/晚餐。

## 6. 变更纪律

1. 新增 Agent：复用本文档信封或登记为特例格式；输出解析只允许调用 `AgentOutputParser` / `JsonUtils`；
2. 提示词调整：公共输出规范只改 `prompts/common-format.txt`；各 Agent 专属规则改各自的 txt；
3. 解析逻辑调整：只改 `AgentOutputParser.java` / `JsonUtils.java`，业务代码（CandidateService/Orchestrator/ItineraryService）不得另写解析；
4. 每次改动同步更新本文档与 `AgentOutputParserTest`。
