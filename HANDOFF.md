# 项目交接文档（HANDOFF）

> 原「企业级多 Agent 数据分析（NL2SQL）」项目已完成清理，方向转为 **多 Agent 旅游攻略规划**。
> 本文档是清理后唯一保留的记忆载体：新的开发从本文档 + 下方「保留骨架」开始。

## 一、虚拟机 / 基础设施环境（保持不变）

> 凭据一律走环境变量（与 `application-dev.yml` 同口径），本文档不记录任何明文密码。

- 宿主：Linux 主机（Docker 宿主，IP 见环境变量 `APP_DB_URL` / `REDIS_HOST`，不在此明文记录）
- **MySQL 8**：Docker 容器 `da-mysql`，端口 3306，库 `data_agent`
  - `root` / `${MYSQL_ROOT_PASSWORD}`（管理员账号）
  - `app` / `${APP_DB_PASSWORD}`（业务账号，可读写）
  - `bi_readonly` / `${BI_READONLY_PASSWORD}`（只读账号）
- **Redis 7**：Docker 容器 `da-redis`，端口 6379（普通版，无向量检索模块）
- 容器启动/维护（在宿主机上执行）：
  ```bash
  docker start da-mysql da-redis
  docker update --restart unless-stopped da-mysql da-redis   # 建议已配置，VM 重启自动拉起
  docker exec -it da-mysql mysql -uroot -p                   # 进 MySQL 命令行（-p 交互输入密码，不回显）
  ```
- 连接串模板（实际值在环境变量 `APP_DB_URL`，形如）：
  `jdbc:mysql://<内网IP>:3306/data_agent?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8`

## 二、开发 / 运行环境（保持不变）

- 开发机：Windows（Git Bash 可用），项目路径 `E:\MutiAgent\muti-agent`
- 后端：Java 21、Spring Boot 3.3.5、Maven（**用 IDEA 构建**；命令行 mvnw 的 `~/.m2` 缺依赖，以 IDEA 的仓库为准）
- 依赖版本：LangChain4j 1.0.0、MyBatis-Plus 3.5.9、JJWT 0.12.6
- 前端：Vue 3 + Vite（端口 5173，`vite.config.js` 已配 `/api` 代理到 `http://localhost:8080`）
- LLM：通义千问 OpenAI 兼容接口
  - base-url：`https://dashscope.aliyuncs.com/compatible-mode/v1`
  - API Key 走环境变量 **`QWEN_API_KEY`**（不要硬编码到代码/yml）
  - 模型（`application-dev.yml` 的 `llm` 段）：default=`qwen3.8-flash`、sql=`qwen3.7-plus`（embedding 已随数据分析能力一并移除）
- JWT：`application-dev.yml` 的 `jwt` 段（secret / expiration）
- 数据源：`application-dev.yml` 的 `spring.datasource.app` 单数据源（readonly 数据源已随 SQL 执行能力一并移除）

## 三、已删除内容（数据分析相关，不可恢复）

- `agent` 包全部（意图/SQL生成/SQL审查/洞察/报告/流式报告 6 个 Agent）+ `config/AgentConfig`
- `service` 包全部（AgentOrchestrator 编排器、SQL 缓存、语义缓存、schema RAG 与语料）
- `tool` 包全部（SQL 执行、脱敏、schema 检索、规则意图解析、查询缓存）
- `repository` 包全部（orders/products/returns/schema_knowledge 实体与 Mapper）
- `security` 中 SQL 审查四件套（SqlReviewer/SqlReviewRuleEngine/SqlLimitEnforcer/SqlReviewerStub）
- `model` 中数据分析模型（Intent/QueryResult/QueryState/ReportOutput/SqlValidationResult/ChatRequest/ChatResponse/PipelineStage/QueryStatus）
- `resources/prompts` 全部提示词、根目录 `seed_more.sql`、`target` 构建产物、对应的 7 个单元测试
- `pom.xml` 中 jsqlparser 依赖（SQL 解析用）、ResultCode 中 SQL/数据权限相关错误码

## 四、保留骨架（新项目直接复用）

| 模块 | 内容 |
|---|---|
| `common` | Result 统一响应、ResultCode 错误码、BizException、GlobalExceptionHandler、RequestLogFilter（全局 HTTP 请求日志） |
| `config` | AppDataSourceConfig（单数据源）、Redis、LlmConfig（default/sql/streaming 三个 ChatModel）、SecurityConfig（JWT 无状态 + ASYNC/ERROR 放行）、AsyncConfig（chatStreamExecutor 线程池）、WebConfig（CORS） |
| `security` | JwtUtil、JwtAuthFilter（支持 Authorization header 与 `?token=` 两种方式）、UserAuthService（内存用户 admin/ADMIN、analyst/ANALYST）、RoleResolver、AuthenticatedUser、UserAuth、Role |
| `controller` | AuthController（POST /api/v1/auth/login）、HealthController、TraceController（GET /api/v1/trace/recent） |
| `trace` | AgentTrace / TraceContext / TraceService —— Agent 调用「耗时 + token」观测框架，新多 Agent 项目可直接复用 |
| `model` | LoginRequest、LoginResponse、enums/Role |
| 测试 | JwtUtilTest、UserAuthServiceTest、MutiAgentApplicationTests（contextLoads 冒烟测试） |
| 前端 | 登录骨架（App.vue）：登录后为占位页，等新需求再开发页面 |

## 五、项目现状（2026-09-10，全部功能开发完成）

旅游攻略规划项目已按阶段 A~H 全部开发完成，代码与文档齐备：

| 模块 | 内容 |
|---|---|
| `agent/` | PreferenceAgent（plus）、AttractionAgent / FoodAgent / HotelAgent（flash）、ItineraryAgent（plus），提示词在 `resources/prompts/` |
| `service/` | TravelSessionService（Redis 会话 TTL 2h）、CandidateService（Java 初筛 + LLM 筛选 + id 白名单防幻觉 + trace）、ItineraryService（生成/落库/列表/详情/adjust 版本链/评价）、`orch/TravelOrchestrator`（INIT→PREFERENCE→ATTRACTIONS→FOODS→HOTELS→ITINERARY→DONE 状态机）、`service/route/`（RouteService + RoutePlanner 接口 + SimpleRoutePlanner） |
| `repository/` | `entity/` 6 个表实体 + `mapper/` 6 个 Mapper 接口（继承 BaseMapper，无需实现类，由 `@MapperScan("...repository.mapper")` 扫描） |
| `rule/` | FatigueScorer（劳累度阈值 9，前后天联动）、MealTimeChecker（午餐 11:30-13:30 / 晚餐 17:30-19:30）、BudgetCalculator、DefaultsResolver、ItineraryTextRenderer、RulePreferenceParser（自 tool/ 并入） |
| `controller` | `/api/v1/travel/**`：session、chat/sync、chat/stream、candidates/confirm、itinerary/generate、route、route/itinerary、destinations、itineraries、itinerary/{id}、itinerary/{id}/adjust、itinerary/{id}/feedback |
| 数据 | `sql/schema_travel.sql` + `sql/gen_seed.mjs` 生成的 `sql/seed_travel.sql`（南京/苏州，UNHEX 防乱码） |
| 前端 | `frontend/`（Vue 3 + Vite，无额外依赖）：登录 + hash 路由 + 首页目的地 / 主规划流程（问询→候选→时间线→路径弹层）/ 我的行程 / 详情（调整 + 评价） |
| 文档 | `docs/00~07`，其中 `07_实施计划与测试.md` 各阶段已勾选完成 |

**尚未执行的收尾工作（需用户完成）**：IDEA 构建 + 建库导入种子数据 + 前后端联调测试，见下方「六、设置与测试验证清单」。

## 六、设置与测试验证清单

### 1. 环境准备
1. 启动虚拟机（宿主机，IP 见环境变量）上的 `da-mysql`、`da-redis` 容器（重启虚拟机后需重新启动）。
2. 在 MySQL 中执行 `sql/schema_travel.sql` 建表，再执行 `sql/seed_travel.sql` 导入种子数据（如重新生成：`node sql/gen_seed.mjs > sql/seed_travel.sql`）。
3. 确认环境变量已设置：`QWEN_API_KEY`、`APP_DB_URL`、`APP_DB_USERNAME`、`APP_DB_PASSWORD`、`REDIS_HOST`、`JWT_SECRET`（不要写进代码/提交仓库；如曾泄露请到对应控制台吊销重建）。
4. IDEA 中打开项目，等 Maven 下载依赖后构建（命令行 `mvnw` 因本地仓库缺少依赖不可用，必须在 IDEA 里构建）。

### 2. 启动
1. 后端：IDEA 运行 `MutiAgentApplication`，观察控制台无报错、MyBatis 扫描到 6 个 Mapper。
2. 前端：`frontend` 目录执行 `npm install && npm run dev`，访问 http://localhost:5173。

### 3. 单元测试（IDEA 中运行）
- `GeoUtilsTest`（3 例：距离误差/同点距离 0）
- `RulePreferenceParserTest`（9 例：天数/人数/预算/口味等解析）
- `DefaultsResolverTest`（4 例：默认值计算）
- `JwtUtilTest`、`UserAuthServiceTest`、`MutiAgentApplicationTests`（contextLoads 冒烟）

### 4. 手工全流程验收（前端 5173）
1. admin / admin123 登录 → 首页出现「南京」「苏州」目的地卡片。
2. 选南京 → 会话创建：出开场白 + 第一个问题（天数，带选项按钮）。
3. 依次回答天数/预算/人数/景点偏好/口味/体力/酒店风格（可点按钮或自由输入，试试「随便」走默认值）。
4. 景点候选出现 → 勾选 2~4 个 → 确认；换一批按钮可用（上限 2 次）。
5. 美食候选（按风味分组）勾选 → 确认；酒店候选（显示价格/评分/距已选点中心距离）勾选 → 确认后自动生成行程。
6. 行程时间线：每天有主题/劳累度/预计消费；饭点（11:30-13:30、17:30-19:30）附近应有餐厅节点；点击地点超链接弹出步行/公交/打车方案。
7. 「重新生成」幂等；「我的行程」列表出现该行程；详情页刷新后仍在（DB 持久化）。
8. 详情页「调整行程」输入诉求（如「第二天太赶，删一个景点」）→ 生成 v2 新版本，旧版本归档。
9. 详情页提交评价（星级/节奏/三维满意度/预算执行/标签/备注）→ 查 `t_itinerary_feedback` 有记录。
10. 控制台观察 `[TRACE]` 日志：PreferenceAgent、AttractionAgent、FoodAgent、HotelAgent、ItineraryAgent 各自耗时与 token；`GET /api/v1/trace/recent` 也能查到。

### 5. 关键异常场景验证
- 未登录直接调 `/api/v1/travel/**` → 401。
- 停止 Redis 后发消息 → 降级提示、不崩溃。
- 会话闲置超过 2 小时 → 返回 50003「会话不存在或已过期」。
- LLM 响应超时/失败 → 候选与行程走规则兜底，页面仍可用。

### 6. 遗留说明
- 路径规划为规则估算（步行 5km/h、公交 20km/h+2 元、打车 35km/h+计价），未接高德真实 API（预留 AmapRoutePlanner）。
- 天气因素按设计仅在行程后提示；知识库目前只有南京/苏州两城。
