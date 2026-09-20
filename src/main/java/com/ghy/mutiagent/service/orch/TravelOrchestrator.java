package com.ghy.mutiagent.service.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.PreferenceAgent;
import com.ghy.mutiagent.agent.RequirementAgent;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.JsonUtils;
import com.ghy.mutiagent.common.OpAbortException;
import com.ghy.mutiagent.common.PatchRejectException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.model.AttractionCandidate;
import com.ghy.mutiagent.model.ChatStepResult;
import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.FoodCandidate;
import com.ghy.mutiagent.model.HotelCandidate;
import com.ghy.mutiagent.model.HistoryItem;
import com.ghy.mutiagent.model.ItineraryDetail;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.LockedSelection;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.PlanQuiz;
import com.ghy.mutiagent.model.PlanQuizRequest;
import com.ghy.mutiagent.model.PreferenceResult;
import com.ghy.mutiagent.model.Question;
import com.ghy.mutiagent.model.ResumeView;
import com.ghy.mutiagent.model.RequirementAnalysis;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.UsageChannel;
import com.ghy.mutiagent.model.WebFoodCandidate;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.entity.Destination;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.rule.DefaultsResolver;
import com.ghy.mutiagent.rule.AhpWeightCalculator;
import com.ghy.mutiagent.rule.NightScorer;
import com.ghy.mutiagent.rule.PreferenceUpdater;
import com.ghy.mutiagent.rule.RequirementApplier;
import com.ghy.mutiagent.rule.RequirementMerger;
import com.ghy.mutiagent.rule.RuleParseResult;
import com.ghy.mutiagent.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Autowired;
import com.ghy.mutiagent.service.AgentOutputParser;
import com.ghy.mutiagent.service.CancelRegistry;
import com.ghy.mutiagent.service.CandidateService;
import com.ghy.mutiagent.service.GenerationRegistry;
import com.ghy.mutiagent.service.ItineraryService;
import com.ghy.mutiagent.service.validation.ItineraryValidator;
import com.ghy.mutiagent.service.TravelOperationService;
import com.ghy.mutiagent.service.TravelSessionService;
import com.ghy.mutiagent.service.UsageService;
import com.ghy.mutiagent.rule.RulePreferenceParser;
import com.ghy.mutiagent.trace.AgentTrace;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceService;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 旅行规划编排器（状态机）：阶段 B 实现 PREFERENCE（偏好问询），后续阶段扩展候选与行程。
 *
 * 偏好问询流程：
 * 1. 规则解析用户回复（快、免费），命中不了回退 PreferenceAgent（LLM，带 trace）；
 * 2. 应用更新（类型转换 + 字段三态标记）；
 * 3. 缺口检查：还有缺失字段 → 按优先级模板问下一个（问题由 Java 模板生成，保证不重复问）；
 * 4. 全部齐备 → 填默认值 → 推进到 ATTRACTIONS 阶段。
 */
@Service
public class TravelOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TravelOrchestrator.class);

    /** 字段询问优先级（目的地除外——创建会话时已确定）：第一批硬约束，第二批偏好，第三批可默认 */
    private static final List<String> FIELD_PRIORITY = List.of(
            "days", "totalBudget", "peopleCount", "attractionType",
            "foodTaste", "energyLevel", "hotelStyle", "specialRequests");

    private static final String GREETING = "你好，我是你的旅游攻略智能规划助手，请向我提供一些你的规划想法，"
            + "让我帮你进行初步规划（如：想去哪里玩、规划几天旅途、预算范围）。";

    /** 同步生成中标记租约（与操作预算 deadline 180s 对齐）：到期未清除视为生成已失效 */
    private static final long GENERATING_LEASE_MS = 180_000L;

    /** 问卷时间格式（HH:mm） */
    private static final String TIME_PATTERN = "^([01]?\\d|2[0-3]):[0-5]\\d$";

    /** 候选阶段口语「翻页」触发词：直接换一批，不调 AI */
    private static final List<String> NO_MORE_WORDS = List.of(
            "没有想要的", "都不喜欢", "都不满意", "还有别的", "换一批", "再看看", "都不行", "换一换");

    /** 候选阶段联网检索触发词（阶段2）：用户明示知识库没找到合适的 / 主动要求搜索 */
    private static final List<String> WEB_SEARCH_WORDS = List.of(
            "没有合适", "没有想要", "找不到", "没有满意", "没有看中", "没有想吃的",
            "网上", "搜索", "搜一下", "其他店", "别的店", "还有别的", "更多");

    /** 需求关键词 → 候选匹配标签（确定性兜底；RequirementAgent 输出的 tags 与之合并；受控词表） */
    private static final Map<String, List<String>> NEED_TAG_RULES = Map.ofEntries(
            Map.entry("情侣", List.of("情侣", "夜景", "游船", "浪漫", "氛围")),
            Map.entry("约会", List.of("情侣", "夜景", "游船", "浪漫", "氛围")),
            Map.entry("citywalk", List.of("citywalk", "街区", "步行", "老街", "评弹", "小桥流水")),
            Map.entry("步行", List.of("步行", "步道", "citywalk")),
            Map.entry("美女", List.of("网红", "打卡", "潮流", "街区")),
            Map.entry("网红", List.of("网红", "打卡", "文创")),
            Map.entry("打卡", List.of("网红", "打卡", "文创", "书店")),
            Map.entry("省钱", List.of("免费", "低价", "平价")),
            Map.entry("花费", List.of("免费", "低价", "平价")),
            Map.entry("便宜", List.of("免费", "低价", "平价")),
            Map.entry("免费", List.of("免费")),
            Map.entry("夜景", List.of("夜景", "游船")),
            Map.entry("风景", List.of("自然风光", "湖景", "江景", "水乡")),
            Map.entry("水乡", List.of("水乡", "游船", "古镇")),
            Map.entry("拍照", List.of("网红", "打卡", "出片")),
            Map.entry("博物馆", List.of("博物馆", "文化")),
            Map.entry("历史", List.of("历史", "文化", "博物馆")),
            Map.entry("轻松", List.of("轻松", "舒适", "强度低")),
            Map.entry("不累", List.of("舒适", "轻松", "强度低")),
            Map.entry("老人", List.of("舒适", "轻松", "强度低")),
            Map.entry("亲子", List.of("亲子", "乐园", "动物园")),
            Map.entry("学生", List.of("免费", "博物馆")),
            Map.entry("安静", List.of("安静", "小众")),
            Map.entry("热闹", List.of("热闹")),
            Map.entry("舒适", List.of("舒适", "轻松")),
            Map.entry("刺激", List.of("刺激", "强度高")),
            Map.entry("娱乐项目", List.of("刺激", "乐园", "演出", "摩天轮")),
            Map.entry("打卡拍照", List.of("网红", "打卡", "出片")),
            Map.entry("本地特色菜", List.of("本地菜", "老字号")),
            Map.entry("清淡", List.of("清淡", "养生")),
            Map.entry("辣", List.of("辣", "火锅")));

    /** 偏好问询阶段示意「信息够了」：剩余字段按默认补齐，直接交接候选阶段（智能问答的灵活出口） */
    private static final List<String> HANDOFF_WORDS = List.of(
            "开始规划", "先开始", "先规划", "直接开始", "开始吧", "差不多", "就这样", "可以了");

    /** 偏好问询阶段示意「重新开始」：清空已收集偏好与需求快照，回到第一个问题 */
    private static final List<String> RESTART_WORDS = List.of(
            "重新来", "重新开始", "重来", "从头再来", "从头开始", "重新规划", "重新问", "再来一遍", "重新收集", "重填");

    /** 偏好问询阶段示意「上一步」：回到最近一个已答字段，清空该字段回答后重新询问 */
    private static final List<String> STEP_BACK_WORDS = List.of(
            "上一步", "回到上一步", "返回上一步", "上一个问题", "回到上一个", "回退", "退回", "上一题");

    /** 正在询问的字段被直接否定（如问酒店偏好答「不要酒店」）：字段置 UNSURE，原文并入特殊请求。
     *  仅否定词紧接主题词才算否定；「不要3星酒店」「不要太贵的酒店」是带修饰的偏好表达，不算否定 */
    private static final Map<String, Pattern> FIELD_NEGATION_PATTERNS = Map.of(
            "hotelStyle", Pattern.compile("(不要|不需要|不考虑|不用)\\s*酒店"),
            "attractionType", Pattern.compile("(不要|不需要|不考虑|不用)\\s*景点"));

    /** 字段级特殊需求抽取（命中原文从消息中移除，转成约束+字段备注，不进总体特殊请求）：
     *  美食人均上限 / 酒店近+便宜 / 景点风景美 */
    private static final Pattern FOOD_PRICE_NEED = Pattern.compile(
            "(?:美食|吃的|吃饭|餐厅|饭店|餐馆|午饭|晚餐|三餐)[^，。;；!！?？\\s]{0,10}?(?:人均|不超过|最多|不超|上限)\\s*(\\d+(?:\\.\\d+)?)\\s*元?\\s*(?:以内|以下|左右)?");
    private static final Pattern FOOD_PRICE_NEED_TAIL = Pattern.compile(
            "(?:美食|吃的|吃饭|餐厅|饭店|餐馆|午饭|晚餐|三餐)[^，。;；!！?？\\s]{0,6}?(\\d+(?:\\.\\d+)?)\\s*元?\\s*(?:以内|以下|左右|上下)");
    private static final Pattern HOTEL_NEED = Pattern.compile(
            "(?:酒店[^，。;；!！?？\\s]{0,8}(?:近|附近|便宜|实惠|性价比|不贵|不要太贵)"
            + "|(?:离|距)[^，。;；!！?？\\s]{0,6}(?:近|附近)[^，。;；!！?？\\s]{0,4}的?酒店"
            + "|(?:近|便宜|实惠|性价比|不贵|不要太贵)[^，。;；!！?？\\s]{0,4}的?酒店)");
    private static final Pattern SCENERY_NEED = Pattern.compile(
            "(?:景点|景区)?[^，。;；!！?？\\s]{0,6}(?:风景美|风景好|风景优美|景色美|景色好|自然风光)");
    /** 用户明确不需要酒店/美食/景点（跳过对应挑选环节；命中原文从消息移除，不进总体特殊请求） */
    private static final Pattern NO_HOTEL_NEED = Pattern.compile(
            "(?:不需要|不要|不用|不考虑|免了)\\s*(?:酒店|住宿|宾馆|旅馆)");
    private static final Pattern NO_FOOD_NEED = Pattern.compile(
            "(?:不需要|不要|不用|不考虑|免了)\\s*(?:美食|吃的|吃饭|餐厅|餐馆|饭店)");
    private static final Pattern NO_ATTRACTION_NEED = Pattern.compile(
            "(?:不需要|不要|不用|不考虑|免了|跳过|不去|不逛)\\s*(?:景点|景区|游玩|观光|游览)");

    /** 修正/衔接语前缀：规则抽取后的残余若只剩这类口水话，不视为有效残余（不再为此调 LLM，即时反应） */
    private static final Pattern RESIDUAL_SCAFFOLDING = Pattern.compile(
            "^(?:我|我们|你|是|这|那|的|了|还|还有|而且|另外|以及|同时|刚才|刚刚|之前|上面|前面|"
            + "说错|搞错|看错|写错|打错|错了|不对|不是|重新说|改一下|改成|改为|修改|更正|纠正|重来|再来|"
            + "只要|只|就|吧|啊|哦|呀|嗯|天数)*[，,。.、；;！!？?\\s]*");

    /** 用户明确表达「没想好/随你」的措辞（规则解析器 UNSURE_WORDS + 常见变体，用于校验 Agent 输出） */
    private static final List<String> EXPLICIT_UNSURE_WORDS = List.of(
            "随便", "都行", "随意", "还没想好", "没想好", "还没定", "没定", "再想想", "不知道",
            "你定", "你决定", "无所谓", "没有", "都听你的", "按你推荐");

    private static final Map<String, Question> QUESTION_TEMPLATES = Map.of(
            "days", q("days", "计划玩几天呢？", List.of("1天", "2天", "3天", "4天", "5天及以上", "还没想好")),
            "totalBudget", q("totalBudget", "预算大概多少？", List.of("1500以内", "1500-3000", "3000-5000", "5000以上", "还没想好")),
            "peopleCount", q("peopleCount", "几个人一起出行？", List.of("1人", "2人", "3-4人", "5人以上", "还没想好")),
            "attractionType", q("attractionType", "喜欢什么类型的景点？", List.of("打卡拍照", "娱乐项目", "两者都要", "按你推荐")),
            "foodTaste", q("foodTaste", "对吃的有什么偏好？", List.of("清淡", "辣", "本地特色菜", "都行")),
            "energyLevel", q("energyLevel", "体力水平如何？", List.of("体力好", "一般", "偏弱")),
            "hotelStyle", q("hotelStyle", "酒店更看重什么？", List.of("性价比优先", "体验优先", "位置/交通优先", "没想好")),
            "specialRequests", q("specialRequests", "最后，有什么特别想去的地方或特殊要求吗？"
                    + "（没有就点「没有」，也可以直接打字，比如：想看夜景、带老人、避开人流）", List.of("没有"))
    );

    private final TravelSessionService sessionService;
    private final DestinationMapper destinationMapper;
    private final RulePreferenceParser rulePreferenceParser;
    private final PreferenceAgent preferenceAgent;
    private final RequirementAgent requirementAgent;
    private final CandidateService candidateService;
    private final ItineraryService itineraryService;
    private final UsageService usageService;
    private final TraceService traceService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;
    /** S12 追踪版本标识：操作启动时绑定（提示词/模型版本随配置）；可直接装配的测试/网关环境用 setter 注入 */
    @Value("${travel.trace.prompt-version:prompt-v1}")
    private String tracePromptVersion = "prompt-v1";
    @Value("${travel.trace.model-version:unknown}")
    private String traceModelVersion = "unknown";

    public void setTracePromptVersion(String tracePromptVersion) {
        this.tracePromptVersion = tracePromptVersion;
    }

    public void setTraceModelVersion(String traceModelVersion) {
        this.traceModelVersion = traceModelVersion;
    }
    private final TravelOperationService operationService;

    /** Observability 薄埋点（可空：手动装配的测试进程为 null；模块关闭时内部 noop） */
    @Autowired(required = false)
    private com.ghy.mutiagent.observability.collection.ObsInstrumentation obsInstrumentation;

    /** 同步生成进行中注册表（可空：手动装配的测试进程为 null 时守卫不生效，行为与旧版一致） */
    @Autowired(required = false)
    GenerationRegistry generationRegistry;

    /** 偏好解析/需求分析类 Agent 现走 defaultChatModel（快模型），行程规划走 sqlChatModel；记录用量时注明模型 */
    @Value("${llm.models.default}")
    private String defaultModel;

    @Value("${llm.models.sql}")
    private String sqlModel;

    public TravelOrchestrator(TravelSessionService sessionService,
                              DestinationMapper destinationMapper,
                              RulePreferenceParser rulePreferenceParser,
                              PreferenceAgent preferenceAgent,
                              RequirementAgent requirementAgent,
                              CandidateService candidateService,
                              ItineraryService itineraryService,
                              UsageService usageService,
                              TraceService traceService,
                              ObjectMapper objectMapper,
                              @Qualifier("chatStreamExecutor") TaskExecutor taskExecutor,
                              TravelOperationService operationService) {
        this.sessionService = sessionService;
        this.destinationMapper = destinationMapper;
        this.rulePreferenceParser = rulePreferenceParser;
        this.preferenceAgent = preferenceAgent;
        this.requirementAgent = requirementAgent;
        this.candidateService = candidateService;
        this.itineraryService = itineraryService;
        this.usageService = usageService;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.operationService = operationService;
    }

    /** 创建会话：选定目的地，返回开场白 + 第一个问题（会话归属当前登录用户） */
    public ChatStepResult createSession(AuthenticatedUser actor, Long destinationId) {
        Destination dest = destinationMapper.selectById(destinationId);
        if (dest == null || dest.getStatus() == null || dest.getStatus() != 1) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }

        TravelState state = new TravelState();
        state.setSessionId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        state.setUserId(actor.id());
        state.setUsername(actor.username());
        state.setDestinationId(destinationId);
        state.setDestinationName(dest.getName());
        state.setStage(TravelStage.PREFERENCE);
        state.setCreatedAt(LocalDateTime.now());
        state.getPreference().setDestinationId(destinationId);

        Question first = nextQuestion(state);
        state.setCurrentField(first.getField());
        sessionService.save(state);
        usageService.recordOp(state.getSessionId(), state.getUserId(), state.getUsername(),
                "PREFERENCE", "创建会话", "SUCCESS", "目的地：" + dest.getName(), UsageChannel.OP);
        log.info("[TravelAgent][stage=PREFERENCE][sessionId={}] 会话创建，目的地={}", state.getSessionId(), dest.getName());
        return buildResult(state, GREETING + "\n\n" + first.getText(), first);
    }

    /** 对话步进：偏好阶段问询；候选阶段自由表达触发重筛；DONE 阶段口语化调整行程 */
    public ChatStepResult chat(AuthenticatedUser actor, String sessionId, String message) {
        // 归属校验必须先于一切读取与写入（P0-S01）：不存在/不属于当前用户统一 404
        TravelState state = sessionService.loadOwned(sessionId, actor.id());

        if (message == null || message.isBlank()) {
            Question q = state.getStage() == TravelStage.PREFERENCE ? nextQuestion(state) : null;
            ChatStepResult r = buildResult(state, "请告诉我您的想法（可以直接打字，也可以点击选项）", q);
            usageService.recordQa(state.getSessionId(), state.getUserId(), state.getUsername(),
                    state.getStage().name(), "对话", "SUCCESS", UsageChannel.OP, null,
                    "", "（空消息，重新询问）", 0, 0, 0);
            return r;
        }

        // 本轮用量归集：各步骤把 channel/模型/token 累积到 state，最后落一条 Q&A 审计记录
        state.resetTurnUsage();
        long turnStart = System.currentTimeMillis();
        String entryStage = state.getStage().name();
        ChatStepResult result = switch (state.getStage()) {
            case PREFERENCE -> chatPreference(state, message);
            case ATTRACTIONS, FOODS, HOTELS -> chatCandidateRefine(state, message);
            case DONE -> chatDone(actor, state, message);
            default -> {
                sessionService.save(state);
                yield buildResult(state, "当前步骤请先完成页面上的选择，稍后我会为你继续规划。", null);
            }
        };
        usageService.recordQa(state.getSessionId(), state.getUserId(), state.getUsername(),
                entryStage, "对话", "SUCCESS",
                state.getTurnChannel() == null ? UsageChannel.KB : state.getTurnChannel(),
                state.getTurnModels().isEmpty() ? null : String.join(",", state.getTurnModels()),
                message, result.getMessage() == null ? "" : result.getMessage(),
                state.getTurnInputTokens(), state.getTurnOutputTokens(),
                System.currentTimeMillis() - turnStart);
        return result;
    }

    /** 偏好问询：规则解析 → 残余交 LLM 客服式消解 → 完成时需求分析交接；回复回显理解内容 */
    private ChatStepResult chatPreference(TravelState state, String message) {
        String trimmed = message == null ? "" : message.trim();
        // 用户示意「重新开始」：清空已收集偏好，从头再问（先于一切解析，秒回）
        if (trimmed.length() <= 12 && RESTART_WORDS.stream().anyMatch(trimmed::contains)) {
            return restartPreference(state);
        }
        // 用户示意「上一步」：回到上一个已答字段重新确认（其余字段不受影响）
        if (trimmed.length() <= 12 && STEP_BACK_WORDS.stream().anyMatch(trimmed::contains)) {
            return stepBack(state);
        }
        // 用户示意信息已足够：剩余字段按默认补齐，直接交接候选阶段（智能问答的灵活出口）
        if (trimmed.length() <= 12 && HANDOFF_WORDS.stream().anyMatch(trimmed::contains)) {
            return completePreference(state);
        }
        // 字段级特殊需求先抽取（命中原文从消息移除，防规则误解析：如「美食要人均50以内」不得当总预算）
        FieldNeedsResult fieldNeeds = extractFieldNeeds(trimmed);
        String currentField = state.getCurrentField();
        RuleParseResult parsed = rulePreferenceParser.parseResult(fieldNeeds.cleaned(), currentField, state.getPreference());
        // 修正/衔接语不进需求快照：避免「错了、是」这类口水话混入 extraRequest
        parsed.setUnresolvedText(stripResidualScaffolding(parsed.getUnresolvedText()));
        Map<String, String> updates = new LinkedHashMap<>(parsed.getUpdates());
        // 字段级需求并入快照与字段备注；针对当前字段时该字段置 UNSURE（不再追问，
        // 总体特殊要求最后仍会单独询问一遍，两个口径互不污染）；noHotel/noFood 置跳过标记
        applyFieldNeeds(state, parsed, fieldNeeds, updates, currentField);
        // 每轮把约束/预算口径/残余并入同一份快照（S02）
        RequirementMerger.mergeInto(state, parsed);
        // S07：约束合并后推进约束版本，并评估锁定项与新约束的冲突（锁定为空时无操作）
        state.bumpConstraintRevision();
        candidateService.evaluateLockedConflicts(state);
        // 修正/衔接语不计为有效残余：规则已抽到信息时不再为口水话调 LLM（如「错了，我只要1天」即时生效）
        String residual = parsed.getUnresolvedText();
        boolean hasResidual = residual != null && !residual.isBlank();
        if (hasResidual && isFieldNegated(currentField, residual)) {
            // 正在问的字段被直接否定（如问酒店偏好答「不要酒店」）：该字段置 UNSURE（不再追问），
            // 原文记为该字段的特殊需求备注——与总体特殊请求分开，互不污染
            state.getPreference().appendFieldNote(currentField, residual.trim());
            updates.putIfAbsent(currentField, "UNSURE");
        } else if ("specialRequests".equals(currentField) && hasResidual) {
            // 正在收集特殊要求：整句原文即特殊要求（"没有"→UNSURE），无需模型消解
            String extra = "没有".equals(residual.trim()) ? "UNSURE" : residual.trim();
            String existing = updates.get("specialRequests");
            updates.put("specialRequests", existing == null || "UNSURE".equals(existing) || existing.contains(extra)
                    ? extra : existing + "；" + extra);
        } else if (hasResidual) {
            // 规则失手：把残余内容交给偏好解析 Agent 做客服式消解——可多字段抽取、
            // 否定当前字段（UNSURE=置空不再追问）、原文追加特殊请求；
            // 规则已命中的字段保持规则值（确定性优先），Agent 结果只补缺
            Map<String, String> llmUpdates = llmParse(state, message, currentField);
            Map<String, String> merged = new LinkedHashMap<>(llmUpdates);
            merged.putAll(updates);
            // 保护正在询问的字段：用户没表达「没想好/不要」时，不接受 Agent 对其置 UNSURE，
            // 避免当前问题被误跳过（如问预算时用户改天数，Agent 误判预算没想好）
            if (currentField != null && "UNSURE".equalsIgnoreCase(merged.get(currentField))
                    && updates.get(currentField) == null
                    && !explicitlyUnsure(message, currentField)) {
                merged.remove(currentField);
            }
            if (llmUpdates.isEmpty() && hasResidual) {
                // Agent 失败兜底：原文记入特殊请求，不丢信息
                merged.put("specialRequests", residual.trim());
            }
            updates = merged;
        }
        PreferenceUpdater.apply(state, updates);

        // 信息差不多的灵活出口：任何一轮用户示意「够了」都直接交接（长句先解析再交接，不丢信息）
        if (HANDOFF_WORDS.stream().anyMatch(trimmed::endsWith)) {
            return completePreference(state);
        }

        Question next = nextQuestion(state);
        if (next == null) {
            return completePreference(state);
        }

        state.setCurrentField(next.getField());
        sessionService.save(state);
        String echo = echoUpdates(state, updates);
        List<String> needEcho = new ArrayList<>();
        for (FieldNeed n : fieldNeeds.needs()) {
            if (!needEcho.contains(n.text())) {
                needEcho.add(n.text());
            }
        }
        if (!needEcho.isEmpty()) {
            echo = (echo.isEmpty() ? "" : echo + "；") + String.join("；", needEcho);
        }
        String reply;
        if (!echo.isEmpty()) {
            reply = "好的，我记下了——" + echo + "。";
        } else {
            reply = "抱歉，我刚才没能理解这部分信息。你可以换个说法（比如直接说数字），"
                    + "或点击上面的选项，我会继续为你整理。";
        }
        return buildResult(state, reply + "\n\n" + next.getText(), next);
    }

    /** 偏好收集完成：默认补齐剩余字段 → 需求分析路由 → 生成景点备选池，交接给候选阶段 */
    private ChatStepResult completePreference(TravelState state) {
        // 需求分析为同步长任务：占用生成租约，恢复端据此显示「分析中」并轮询快照，
        // 避免用户中途离开后回来页面无反馈（前端只能靠 resumable.generating 感知进行中）
        GenerationRegistry registry = generationRegistry;
        boolean lease = registry == null || registry.begin(state.getSessionId(), GENERATING_LEASE_MS);
        try {
            return doCompletePreference(state);
        } finally {
            if (registry != null && lease) {
                registry.end(state.getSessionId());
            }
        }
    }

    private ChatStepResult doCompletePreference(TravelState state) {
        fillDefaults(state);
        state.setStage(TravelStage.ATTRACTIONS);
        state.setCurrentField(null);
        // 需求分析路由：Agent 判断走标准流程还是深度分析，并提炼贯穿全流程的关注点
        RequirementAnalysis analysis = analyzeRequirement(state);
        reconcileAnalysisWithEnergy(state, analysis);
        Map<String, Double> weights = AhpWeightCalculator.adjust(analysis.getNeeds());
        state.setWeights(weights);
        state.setNeedTags(deriveNeedTags(state, analysis));
        String analysisNote;
        if ("agent".equalsIgnoreCase(analysis.getMode())) {
            state.setExtraRequest("关键关注点：" + focusOf(analysis) + "。" + briefOf(analysis));
            analysisNote = "\n\n我对你的需求做了一次整体分析：" + briefOf(analysis)
                    + "\n识别到的关键关注点：" + focusOf(analysis)
                    + "。接下来的景点、美食、酒店都会围绕这些为你挑选；你可以随时打字补充或修改。";
        } else {
            analysisNote = "\n\n你的需求比较常规，我按标准流程高效挑选。";
        }
        analysisNote += "\n" + weightNote(analysis, weights);
        if (Boolean.TRUE.equals(state.getNoAttractionNeeded())) {
            // 用户不需要景点：跳过景点挑选，按其余跳过标记直接进入美食/酒店环节或生成行程
            state.getSelectedAttractionIds().clear();
            state.getPickedAttractionIds().clear();
            if (Boolean.TRUE.equals(state.getNoFoodNeeded())) {
                if (Boolean.TRUE.equals(state.getNoHotelNeeded())) {
                    return finishWithItineraryCore(state,
                            "已了解你的需求！按你的要求跳过景点、美食和酒店挑选，直接为你生成行程：");
                }
                state.setStage(TravelStage.HOTELS);
                boolean hotelOk = candidateService.generateHotels(state);
                sessionService.save(state);
                return buildResult(state, summary(state) + analysisNote
                        + "\n\n按你的要求跳过景点与美食挑选，酒店备选池已生成"
                        + "（每批 5 家，换一批秒出且不重复）：" + llmNote(hotelOk), null);
            }
            state.setStage(TravelStage.FOODS);
            boolean foodOk = candidateService.generateFoods(state);
            sessionService.save(state);
            return buildResult(state, summary(state) + analysisNote
                    + "\n\n按你的要求跳过景点挑选，美食备选池已按你的口味与预算生成"
                    + "（每批 8 家，换一批秒出且不重复）：" + llmNote(foodOk) + foodWebNote(state), null);
        }
        boolean aiOk = candidateService.generateAttractions(state);
        sessionService.save(state);
        log.info("[TravelAgent][stage=PREFERENCE][sessionId={}] 偏好收集完成：{}", state.getSessionId(), state.getPreference());
        String conflictNote = state.getConflict() == null || state.getConflict().isBlank()
                ? "" : "\n\n（注意：检测到可能的冲突——" + state.getConflict() + "）";
        return buildResult(state, summary(state)
                + conflictNote
                + analysisNote
                + llmNote(aiOk)
                + "\n\n我已从" + state.getDestinationName() + "的知识库中筛出景点备选池"
                + "（每批展示 8 个，换一批即翻页、秒出且不重复）。请勾选感兴趣的景点；"
                + "也可以直接打字提出想法（如「想看夜景、不要太累」），我会带你的要求重新分析。", null);
    }

    /** 偏好问询重置：清空已收集的偏好与需求快照，回到第一个问题（重新来） */
    private ChatStepResult restartPreference(TravelState state) {
        TravelPreference p = state.getPreference();
        p.setDays(null);
        p.setTotalBudget(null);
        p.setPeopleCount(null);
        p.setAttractionType(null);
        p.setFoodTaste(null);
        p.setEnergyLevel(null);
        p.setHotelStyle(null);
        p.setSpecialRequests(null);
        p.setMealPlan(null);
        p.getFieldStates().clear();
        state.getAskedFields().clear();
        state.setConflict(null);
        state.setExtraRequest(null);
        state.setRequirementSnapshot(null);
        state.setWeights(null);
        Question first = nextQuestion(state);
        state.setCurrentField(first.getField());
        sessionService.save(state);
        log.info("[TravelAgent][stage=PREFERENCE][sessionId={}] 用户要求重新开始，偏好已清空", state.getSessionId());
        return buildResult(state, "好的，我们重新开始——刚才记下的偏好都已清空，"
                + "请重新告诉我你的想法。\n\n" + first.getText(), first);
    }

    /** 残余原文是否直接否定了正在询问的字段（否定词紧接主题词才命中，如「不要酒店」） */
    private static boolean isFieldNegated(String currentField, String residual) {
        Pattern p = currentField == null ? null : FIELD_NEGATION_PATTERNS.get(currentField);
        return p != null && residual != null && p.matcher(residual).find();
    }

    /** 剥离残余中的修正/衔接语前缀（「错了、改成、我只要」等），返回真正值得消解的内容 */
    private static String stripResidualScaffolding(String residual) {
        if (residual == null) {
            return null;
        }
        String s = residual.trim();
        String prev;
        do {
            prev = s;
            s = RESIDUAL_SCAFFOLDING.matcher(s).replaceFirst("").trim();
            s = s.replaceFirst("^(我)?(要|想要|想|希望|请|帮我|麻烦)", "").trim();
        } while (!s.equals(prev));
        return s;
    }

    /** 用户是否明确表达了对当前字段的「没想好」或直接否定（决定是否接受 Agent 给出的 UNSURE） */
    private static boolean explicitlyUnsure(String message, String currentField) {
        String m = message == null ? "" : message.trim();
        return EXPLICIT_UNSURE_WORDS.stream().anyMatch(m::contains) || isFieldNegated(currentField, m);
    }

    /** 是否触发美食联网检索：美食阶段 + 已有特殊需求 + 触发词命中 + 同一句话不重复检索 */
    private static boolean shouldWebSearch(TravelState state, String message) {        if (state == null || state.getStage() != TravelStage.FOODS || message == null) {
            return false;
        }
        String extra = state.getExtraRequest();
        if (extra == null || extra.isBlank()) {
            return false;
        }
        String m = message.trim();
        if (WEB_SEARCH_WORDS.stream().noneMatch(m::contains)) {
            return false;
        }
        return !m.equals(state.getWebSearchKey());
    }

    /** 美食联网候选存在时的补充说明（确认景点时池内无匹配触发联网检索的场景） */
    private static String foodWebNote(TravelState state) {
        if (state.getWebFoodCandidates() == null || state.getWebFoodCandidates().isEmpty()) {
            return "";
        }
        return "\n\n知识库中没有完全符合的店，我另外在网上搜到 " + state.getWebFoodCandidates().size()
                + " 家，已通过校验并入候选池（可直接勾选；信息来自网络，价格仅供参考）。";
    }

    /** 需求关键字：确定性规则 + Agent 输出合并（会话内累积；额外要求变化时重算并保留已有） */
    private List<String> deriveNeedTags(TravelState state, RequirementAnalysis analysis) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (state.getNeedTags() != null) {
            tags.addAll(state.getNeedTags());
        }
        StringBuilder text = new StringBuilder();
        TravelPreference p = state.getPreference();
        if (p != null) {
            if (p.getAttractionType() != null) {
                text.append(p.getAttractionType()).append(' ');
            }
            if (p.getSpecialRequests() != null) {
                text.append(p.getSpecialRequests()).append(' ');
            }
            p.fieldNotes().values().forEach(v -> text.append(v).append(' '));
        }
        if (state.getExtraRequest() != null) {
            text.append(state.getExtraRequest());
        }
        String t = text.toString().toLowerCase();
        for (Map.Entry<String, List<String>> e : NEED_TAG_RULES.entrySet()) {
            if (t.contains(e.getKey())) {
                tags.addAll(e.getValue());
            }
        }
        if (analysis != null && analysis.getTags() != null) {
            tags.addAll(analysis.getTags());
        }
        return new ArrayList<>(tags);
    }

    /** 字段级特殊需求（约束 key/value、归属字段、原文） */
    private record FieldNeed(String key, String value, String hardness, String field, String text) {
    }

    /** 字段级需求抽取结果：清理后的消息 + 需求列表 */
    private record FieldNeedsResult(String cleaned, List<FieldNeed> needs) {
    }

    /**
     * 抽取字段级特殊需求：命中原文从消息中移除（防规则误解析、防 LLM 冗余调用），
     * 转成约束与字段备注——美食人均上限 / 酒店近+便宜 / 景点风景美。
     */
    private static FieldNeedsResult extractFieldNeeds(String message) {
        if (message == null || message.isBlank()) {
            return new FieldNeedsResult(message, List.of());
        }
        List<FieldNeed> needs = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        Matcher m1 = FOOD_PRICE_NEED.matcher(message);
        while (m1.find()) {
            // SOFT：上限已在候选池生成时硬过滤（池内店铺必然合规），快照侧不做二次硬校验
            needs.add(new FieldNeed("foodMaxPricePerPerson", m1.group(1), "SOFT", "foodTaste", m1.group().trim()));
            spans.add(new int[]{m1.start(), m1.end()});
        }
        Matcher m2 = FOOD_PRICE_NEED_TAIL.matcher(message);
        while (m2.find()) {
            if (overlapsAny(spans, m2.start(), m2.end())) {
                continue;
            }
            needs.add(new FieldNeed("foodMaxPricePerPerson", m2.group(1), "SOFT", "foodTaste", m2.group().trim()));
            spans.add(new int[]{m2.start(), m2.end()});
        }
        Matcher mh = HOTEL_NEED.matcher(message);
        while (mh.find()) {
            if (overlapsAny(spans, mh.start(), mh.end())) {
                continue;
            }
            String t = mh.group().trim();
            if (t.contains("近") || t.contains("附近")) {
                needs.add(new FieldNeed("hotelNear", "TRUE", "SOFT", "hotelStyle", t));
            }
            if (t.contains("便宜") || t.contains("实惠") || t.contains("性价比") || t.contains("不贵") || t.contains("太贵")) {
                needs.add(new FieldNeed("hotelCheap", "TRUE", "SOFT", "hotelStyle", t));
            }
            spans.add(new int[]{mh.start(), mh.end()});
        }
        Matcher ms = SCENERY_NEED.matcher(message);
        while (ms.find()) {
            if (overlapsAny(spans, ms.start(), ms.end())) {
                continue;
            }
            needs.add(new FieldNeed("sceneryNice", "TRUE", "SOFT", "attractionType", ms.group().trim()));
            spans.add(new int[]{ms.start(), ms.end()});
        }
        Matcher mnh = NO_HOTEL_NEED.matcher(message);
        while (mnh.find()) {
            if (overlapsAny(spans, mnh.start(), mnh.end())) {
                continue;
            }
            needs.add(new FieldNeed("noHotel", "TRUE", "HARD", "hotelStyle", mnh.group().trim()));
            spans.add(new int[]{mnh.start(), mnh.end()});
        }
        Matcher mnf = NO_FOOD_NEED.matcher(message);
        while (mnf.find()) {
            if (overlapsAny(spans, mnf.start(), mnf.end())) {
                continue;
            }
            needs.add(new FieldNeed("noFood", "TRUE", "HARD", "foodTaste", mnf.group().trim()));
            spans.add(new int[]{mnf.start(), mnf.end()});
        }
        Matcher mna = NO_ATTRACTION_NEED.matcher(message);
        while (mna.find()) {
            if (overlapsAny(spans, mna.start(), mna.end())) {
                continue;
            }
            needs.add(new FieldNeed("noAttraction", "TRUE", "HARD", "attractionType", mna.group().trim()));
            spans.add(new int[]{mna.start(), mna.end()});
        }
        if (needs.isEmpty()) {
            return new FieldNeedsResult(message, List.of());
        }
        // 去掉命中区间，重建给规则解析的消息（重叠区间合并）
        List<int[]> merged = new ArrayList<>(spans);
        merged.sort(Comparator.comparingInt(a -> a[0]));
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        int end = 0;
        for (int[] s : merged) {
            if (s[0] < end) {
                continue;
            }
            sb.append(message, pos, s[0]);
            end = s[1];
            pos = s[1];
        }
        sb.append(message.substring(pos));
        return new FieldNeedsResult(sb.toString().trim(), needs);
    }

    private static boolean overlapsAny(List<int[]> spans, int start, int end) {
        for (int[] s : spans) {
            if (start < s[1] && end > s[0]) {
                return true;
            }
        }
        return false;
    }

    private static ConstraintEntry constraintEntry(FieldNeed n) {
        ConstraintEntry c = new ConstraintEntry();
        c.setKey(n.key());
        c.setValue(n.value());
        c.setHardness(n.hardness());
        c.setStatus("ACTIVE");
        c.setSource("USER");
        c.setOriginalText(n.text());
        return c;
    }

    /** 应用字段级需求：noHotel/noFood 置跳过标记，约束入快照、备注入字段；针对当前字段时置 UNSURE */
    private void applyFieldNeeds(TravelState state, RuleParseResult parsed, FieldNeedsResult fieldNeeds,
                                 Map<String, String> updates, String currentField) {
        for (FieldNeed n : fieldNeeds.needs()) {
            if ("noHotel".equals(n.key())) {
                state.setNoHotelNeeded(true);
            } else if ("noFood".equals(n.key())) {
                state.setNoFoodNeeded(true);
            } else if ("noAttraction".equals(n.key())) {
                state.setNoAttractionNeeded(true);
            }
            state.getPreference().appendFieldNote(n.field(), n.text());
            parsed.getConstraints().add(constraintEntry(n));
            if (updates != null && currentField != null && n.field().equals(currentField)) {
                updates.putIfAbsent(currentField, "UNSURE");
            }
        }
    }

    /** 偏好问询回退：回到最近一个已答字段，清空其值与三态后重新询问（其余字段不受影响） */
    private ChatStepResult stepBack(TravelState state) {
        TravelPreference p = state.getPreference();
        int idx = FIELD_PRIORITY.indexOf(state.getCurrentField());
        int back = -1;
        for (int i = (idx < 0 ? FIELD_PRIORITY.size() : idx) - 1; i >= 0; i--) {
            if (p.getFieldStates().containsKey(FIELD_PRIORITY.get(i))) {
                back = i;
                break;
            }
        }
        if (back < 0) {
            sessionService.save(state);
            Question first = QUESTION_TEMPLATES.get(FIELD_PRIORITY.get(0));
            return buildResult(state, "当前还没有已回答的问题可以回退，我们从第一个问题开始：\n\n" + first.getText(), first);
        }
        String field = FIELD_PRIORITY.get(back);
        clearPreferenceField(p, field);
        state.setCurrentField(field);
        sessionService.save(state);
        Question q = QUESTION_TEMPLATES.get(field);
        log.info("[TravelAgent][stage=PREFERENCE][sessionId={}] 回退到字段 {}", state.getSessionId(), field);
        return buildResult(state, "好的，我们回到上一个问题（之前的回答已清空）。\n\n" + q.getText(), q);
    }

    /** 清空指定偏好字段的值与三态（回退用） */
    private static void clearPreferenceField(TravelPreference p, String field) {
        switch (field) {
            case "days" -> p.setDays(null);
            case "totalBudget" -> p.setTotalBudget(null);
            case "peopleCount" -> p.setPeopleCount(null);
            case "attractionType" -> p.setAttractionType(null);
            case "foodTaste" -> p.setFoodTaste(null);
            case "energyLevel" -> p.setEnergyLevel(null);
            case "hotelStyle" -> p.setHotelStyle(null);
            case "specialRequests" -> p.setSpecialRequests(null);
            default -> { }
        }
        p.getFieldStates().remove(field);
    }

    /** 候选阶段自由表达：把用户想法交给当前阶段的 Agent 重新筛选备选池 */
    private ChatStepResult chatCandidateRefine(TravelState state, String message) {
        // 字段级特殊需求抽取（候选阶段同样生效：「美食要人均50以内」直接硬过滤，不进总体特殊请求）
        FieldNeedsResult fieldNeeds = extractFieldNeeds(message);
        // 意图拆分：口语「翻页」与「附带条件」可并存（如「换一批，不要爬山」）
        RuleParseResult parsed = rulePreferenceParser.parseResult(fieldNeeds.cleaned(), null, state.getPreference());
        // 修正/衔接语不进需求快照：避免「错了、是」这类口水话混入 extraRequest 影响重筛
        parsed.setUnresolvedText(stripResidualScaffolding(parsed.getUnresolvedText()));
        // 字段级需求（含 noHotel/noFood 跳过标记）并入快照与字段备注
        applyFieldNeeds(state, parsed, fieldNeeds, null, null);
        if (state.getStage() == TravelStage.PLAN_QUIZ) {
            // 问卷阶段自由文本答题：能解析的问卷字段即时生效，答完自动生成
            if (!parsed.getUpdates().isEmpty()) {
                PreferenceUpdater.apply(state, parsed.getUpdates());
            }
            if (quizResolved(state)) {
                state.setPlanQuizAnswered(true);
                return finishWithItinerary(state, "已收到你的安排偏好，正在生成行程：");
            }
            sessionService.save(state);
            ChatStepResult quizReply = buildResult(state, "好的，已记录「" + message + "」。请继续回答下面的问题：", null);
            quizReply.setPlanQuiz(buildPlanQuiz(state));
            return quizReply;
        }
        boolean paging = parsed.getIntents().contains("PAGING")
                || NO_MORE_WORDS.stream().anyMatch(message::contains);
        boolean hasCondition = !parsed.getConstraints().isEmpty() || !parsed.getUpdates().isEmpty()
                || parsed.getBudget() != null
                || !fieldNeeds.needs().isEmpty()
                || (parsed.getUnresolvedText() != null && !parsed.getUnresolvedText().isBlank());

        int wrapped = 0;
        if (paging) {
            wrapped = switch (state.getStage()) {
                case ATTRACTIONS -> candidateService.nextAttractionBatch(state);
                case FOODS -> candidateService.nextFoodBatch(state);
                default -> candidateService.nextHotelBatch(state);
            };
            if (!hasCondition) {
                sessionService.save(state);
                state.addTurnUsage(null, 0, 0, UsageChannel.CACHE);
                return buildResult(state, wrapped == 0
                        ? "好的，为你从备选池再翻一批（不重复、秒出，已勾选的保留置顶）："
                        : "备选池已展示完一轮，为你从头再翻一页（已勾选的保留置顶）：", null);
            }
        }
        // 条件并入快照后，带全部有效要求重筛；extraRequest 为快照渲染摘要（累积，不覆盖）
        RequirementMerger.mergeInto(state, parsed);
        // S07：约束变化推进版本并评估锁定项冲突（锁定项与新要求冲突将阻止后续确认提交）
        state.bumpConstraintRevision();
        candidateService.evaluateLockedConflicts(state);
        if (!parsed.getUpdates().isEmpty()) {
            PreferenceUpdater.apply(state, parsed.getUpdates());
        }
        state.setExtraRequest(RequirementMerger.renderExtra(state));
        // 额外要求变化时重算需求关键字（保留已有），供候选池标签匹配加权
        state.setNeedTags(deriveNeedTags(state, null));
        // 跳过信号：用户明确不需要当前环节的产物 → 清掉已选，直接进入下一环节或生成行程
        boolean noFood = Boolean.TRUE.equals(state.getNoFoodNeeded());
        boolean noHotel = Boolean.TRUE.equals(state.getNoHotelNeeded());
        boolean noAttraction = Boolean.TRUE.equals(state.getNoAttractionNeeded());
        if (state.getStage() == TravelStage.ATTRACTIONS && noAttraction) {
            state.getSelectedAttractionIds().clear();
            state.getPickedAttractionIds().clear();
            sessionService.save(state);
            if (noFood) {
                if (noHotel) {
                    return finishWithItinerary(state, "好的，按你的要求跳过景点、美食和酒店，直接为你生成行程：");
                }
                state.setStage(TravelStage.HOTELS);
                boolean hotelOk = candidateService.generateHotels(state);
                sessionService.save(state);
                return buildResult(state, "好的，按你的要求跳过景点与美食挑选。"
                        + "酒店备选池已生成（每批 5 家，换一批秒出且不重复）：" + llmNote(hotelOk), null);
            }
            state.setStage(TravelStage.FOODS);
            boolean foodOk = candidateService.generateFoods(state);
            sessionService.save(state);
            return buildResult(state, "好的，按你的要求跳过景点挑选。"
                    + "美食备选池已按你的口味与预算生成（每批 8 家，换一批秒出且不重复）："
                    + llmNote(foodOk) + foodWebNote(state), null);
        }
        if (state.getStage() == TravelStage.FOODS && noFood) {
            state.getSelectedFoodIds().clear();
            state.getPickedFoodIds().clear();
            sessionService.save(state);
            if (noHotel) {
                state.getSelectedHotelIds().clear();
                return finishWithItinerary(state, "好的，按你的要求跳过美食和酒店，直接为你生成行程：");
            }
            state.setStage(TravelStage.HOTELS);
            boolean hotelOk = candidateService.generateHotels(state);
            sessionService.save(state);
            return buildResult(state, "好的，按你的要求跳过美食挑选。"
                    + "酒店备选池以你选的景点为中心生成（每批 5 家，换一批秒出且不重复）：" + llmNote(hotelOk), null);
        }
        if (state.getStage() == TravelStage.HOTELS && noHotel) {
            state.getSelectedHotelIds().clear();
            state.getPickedHotelIds().clear();
            return finishWithItinerary(state, "好的，按你的要求跳过酒店，直接为你生成行程：");
        }

        boolean aiOk = switch (state.getStage()) {
            case ATTRACTIONS -> candidateService.generateAttractions(state);
            case FOODS -> candidateService.generateFoods(state);
            default -> candidateService.generateHotels(state);
        };
        // 阶段2：知识库无法满足（明示没找到合适的/要求搜索）时联网检索，整理为参考候选
        boolean webSearched = false;
        int webFound = 0;
        if (state.getStage() == TravelStage.FOODS && shouldWebSearch(state, message)) {
            List<WebFoodCandidate> foundList = candidateService.searchFoodsOnline(state);
            state.setWebSearchKey(message.trim());
            if (foundList != null && !foundList.isEmpty()) {
                state.setWebFoodCandidates(foundList);
                webSearched = true;
                webFound = foundList.size();
            }
        }
        sessionService.save(state);
        String stageName = switch (state.getStage()) {
            case ATTRACTIONS -> "景点"; case FOODS -> "美食"; default -> "酒店";
        };
        String agentName = switch (state.getStage()) {
            case ATTRACTIONS -> "AttractionAgent"; case FOODS -> "FoodAgent"; default -> "HotelAgent";
        };
        String prefix = paging
                ? "已翻页并把你的条件「" + message + "」一起考虑："
                : "明白了！我把你的想法「" + message + "」交给了 ";
        String webNote = webSearched
                ? "\n\n同时我在网上为你搜了搜（信息来自网络，仅供参考，未经审核暂不加入行程）：找到 "
                + webFound + " 家可能符合「" + message + "」的店，展示在下方「联网推荐」区。"
                : "";
        return buildResult(state, prefix + (paging ? "" : agentName) + (paging ? "" : "，它带着这个要求重新分析了")
                + stageName + "备选池（已勾选的会保留置顶）："
                + llmNote(aiOk) + webNote + "\n\n请勾选心仪的选项，或继续说你的想法。", null);
    }

    /** DONE 阶段对话：上游条件变化先作废旧行程，其余视为行程调整诉求 */
    private ChatStepResult chatDone(AuthenticatedUser actor, TravelState state, String message) {
        RuleParseResult parsed = rulePreferenceParser.parseResult(message, "specialRequests", state.getPreference());
        if (RequirementApplier.isUpstreamChange(parsed)) {
            PreferenceUpdater.apply(state, parsed.getUpdates());
            RequirementMerger.mergeInto(state, parsed);
            // S07：约束变化推进版本并评估锁定项冲突
            state.bumpConstraintRevision();
            candidateService.evaluateLockedConflicts(state);
            RequirementApplier.invalidateDone(state);
            state.setExtraRequest(RequirementMerger.renderExtra(state));
            sessionService.save(state);
            return buildResult(state, "好的，我已记下你的新条件。由于关键条件（天数/预算/人数）发生变化，"
                    + "原行程已归档为历史版本，需要重新确认并重新生成行程后再展示。", null);
        }
        return chatAdjust(actor, state, message);
    }

    /** DONE 阶段对话：视为对行程的调整诉求，直接生成新版本（行程归属在 adjust 内校验） */
    private ChatStepResult chatAdjust(AuthenticatedUser actor, TravelState state, String message) {
        if (state.getItineraryId() == null) {
            sessionService.save(state);
            return buildResult(state, "行程正在准备中，请稍等片刻再告诉我你的想法。", null);
        }
        try {
            ItineraryDetail d = itineraryService.adjust(actor, state.getItineraryId(), message,
                    state.getSessionId());
            state.setItineraryId(d.getId());
            state.setPlan(d.getPlan());
            state.setItineraryText(d.getText());
            sessionService.save(state);
            state.addTurnUsage(sqlModel, 0, 0, UsageChannel.AGENT);
            return buildResult(state, "我理解了你的新想法，ItineraryAgent 已在原行程基础上做了局部调整，"
                    + "生成 v" + d.getVersion() + " 版本（原行程已归档为历史版本），请查看下方时间线：", null);
        } catch (BizException e) {
            // 归属失败/状态冲突是协议错误（404/409）：直接透出，不降级成普通聊天回复
            if (e.getCode() == ResultCode.RESOURCE_NOT_FOUND.getCode()
                    || e.getCode() == ResultCode.STATE_CONFLICT.getCode()) {
                throw e;
            }
            sessionService.save(state);
            state.addTurnUsage(null, 0, 0, UsageChannel.RULE_FALLBACK);
            return buildResult(state, "抱歉，行程调整没有成功：" + e.getMessage()
                    + "。这通常是 AI 服务暂时不可用，请稍后重试。", null);
        }
    }

    /** AI 重筛失败时附在回复后的说明（区别于「环境未配置」，避免误导） */
    private String llmNote(boolean aiFailed) {
        return aiFailed ? "" : "\n\n（这次 AI 分析没有成功，具体原因见后端日志；"
                + "我先按知识库给你推荐，稍后可以再说一次你的想法重试）";
    }

    /** 回显本轮理解到的偏好（让回复像 Agent 而不是流程机） */
    private String echoUpdates(TravelState state, Map<String, String> updates) {
        TravelPreference p = state.getPreference();
        List<String> parts = new ArrayList<>();
        if (updates.containsKey("days")) {
            parts.add(p.getDays() == null ? "天数先按默认" : p.getDays() + " 天");
        }
        if (updates.containsKey("totalBudget")) {
            parts.add(p.getTotalBudget() == null ? "预算先按默认"
                    : "预算 " + p.getTotalBudget().stripTrailingZeros().toPlainString() + " 元");
        }
        if (updates.containsKey("peopleCount")) {
            parts.add(p.getPeopleCount() == null ? "人数先按默认" : p.getPeopleCount() + " 人");
        }
        if (updates.containsKey("attractionType")) {
            parts.add("景点偏好「" + (p.getAttractionType() == null ? "按推荐" : p.getAttractionType()) + "」");
        }
        if (updates.containsKey("foodTaste")) {
            parts.add("口味偏「" + (p.getFoodTaste() == null ? "按推荐" : p.getFoodTaste()) + "」");
        }
        if (updates.containsKey("energyLevel")) {
            parts.add("体力「" + (p.getEnergyLevel() == null ? "按常规" : p.getEnergyLevel()) + "」");
        }
        if (updates.containsKey("hotelStyle")) {
            parts.add("酒店「" + (p.getHotelStyle() == null ? "按推荐" : p.getHotelStyle()) + "」");
        }
        if (updates.containsKey("specialRequests")) {
            parts.add("特殊要求已记下，规划行程时会优先考虑");
        }
        return String.join("；", parts);
    }

    /** 确认候选集：selectedIds 为当前已勾选集合；regenerate=true 表示「换一批」（备选池翻页，不调 AI） */
    public ChatStepResult confirmCandidates(AuthenticatedUser actor, String sessionId, String candidateType,
                                            List<Long> selectedIds, Boolean regenerate) {
        TravelState state = sessionService.loadOwned(sessionId, actor.id());
        boolean regen = Boolean.TRUE.equals(regenerate);
        List<Long> ids = selectedIds == null ? List.of() : selectedIds;
        // 用户选择也要留痕：用量记录带具体店名/景点名，后台会话审计可查
        usageService.recordOp(state.getSessionId(), state.getUserId(), state.getUsername(),
                state.getStage().name(), (regen ? "换一批" : "确认候选") + candidateType,
                "SUCCESS", "已勾选 " + ids.size() + " 项" + selectedNamesSuffix(state, candidateType, ids),
                regen ? UsageChannel.CACHE : UsageChannel.KB);

        switch (candidateType == null ? "" : candidateType.toUpperCase()) {
            case "ATTRACTION" -> {
                requireStage(state, TravelStage.ATTRACTIONS);
                state.getPickedAttractionIds().addAll(ids);
                if (regen) {
                    int wrapped = candidateService.nextAttractionBatch(state);
                    sessionService.save(state);
                    return buildResult(state, wrapped == 0
                            ? "已从备选池为你翻出新的一批景点（不重复、秒出，已勾选的保留置顶）："
                            : "备选池已展示完一轮，为你从头再翻一页（已勾选的保留置顶）：", null);
                }
                List<Long> validIds = state.getAttractionPool() == null ? List.of()
                        : state.getAttractionPool().stream()
                                .map(AttractionCandidate::getAttractionId).toList();
                List<Long> ordered = orderedDistinct(ids);
                validateSelected(state, ordered, validIds);
                ChatStepResult blocked = lockAndCheckConflicts(state, "ATTRACTION", ordered);
                if (blocked != null) {
                    return blocked;
                }
                state.setSelectedAttractionIds(new ArrayList<>(ordered));
                obsUserConfirmed(null, state, actor, "ATTRACTION", ordered.size(), 0);
                if (Boolean.TRUE.equals(state.getNoFoodNeeded())) {
                    // 用户不需要美食：跳过美食挑选，直接进入酒店环节（酒店也不需要则直接生成行程）
                    if (Boolean.TRUE.equals(state.getNoHotelNeeded())) {
                        return finishWithItinerary(state, "已确认景点！按你的要求跳过美食与酒店，直接生成行程：");
                    }
                    state.setStage(TravelStage.HOTELS);
                    boolean hotelOk = candidateService.generateHotels(state);
                    sessionService.save(state);
                    return buildResult(state, "已确认景点！按你的要求跳过美食挑选，"
                            + "酒店备选池以你选的景点为中心生成（每批 5 家，换一批秒出且不重复）：" + llmNote(hotelOk), null);
                }
                state.setStage(TravelStage.FOODS);
                obsUserConfirmed(null, state, actor, "ATTRACTION", ordered.size(), 0);
                boolean aiOk = candidateService.generateFoods(state);
                sessionService.save(state);
                return buildResult(state, "已确认景点！美食备选池已按你的口味与预算生成"
                        + "（每批 8 家，换一批秒出且不重复）：" + llmNote(aiOk) + foodWebNote(state), null);
            }
            case "FOOD" -> {
                requireStage(state, TravelStage.FOODS);
                state.getPickedFoodIds().addAll(ids);
                if (regen) {
                    int wrapped = candidateService.nextFoodBatch(state);
                    sessionService.save(state);
                    return buildResult(state, wrapped == 0
                            ? "已从备选池为你翻出新的一批餐厅（不重复、秒出，已勾选的保留置顶）："
                            : "备选池已展示完一轮，为你从头再翻一页（已勾选的保留置顶）：", null);
                }
                List<Long> validIds = state.getFoodPool() == null ? List.of()
                        : state.getFoodPool().stream().flatMap(c -> c.getRestaurants().stream())
                                .map(FoodCandidate.FoodItem::getRestaurantId).toList();
                List<Long> ordered = orderedDistinct(ids);
                validateSelected(state, ordered, validIds);
                ChatStepResult blocked = lockAndCheckConflicts(state, "FOOD", ordered);
                if (blocked != null) {
                    return blocked;
                }
                state.setSelectedFoodIds(new ArrayList<>(ordered));
                obsUserConfirmed(null, state, actor, "FOOD", ordered.size(), 0);
                if (Boolean.TRUE.equals(state.getNoHotelNeeded())) {
                    // 用户不需要酒店：跳过酒店挑选，直接生成行程
                    state.getSelectedHotelIds().clear();
                    return finishWithItinerary(state, "已确认美食！按你的要求跳过酒店挑选，直接生成行程：");
                }
                state.setStage(TravelStage.HOTELS);
                obsUserConfirmed(null, state, actor, "FOOD", ordered.size(), 0);
                boolean aiOk = candidateService.generateHotels(state);
                sessionService.save(state);
                return buildResult(state, "已确认美食！酒店备选池以你选的景点和餐厅为中心生成"
                        + "（每批 5 家，换一批秒出且不重复）：" + llmNote(aiOk), null);
            }
            case "HOTEL" -> {
                // 生成中守卫（进程内注册表，不落库、不改快照）：上一轮生成仍在进行时，
                // 重复确认/切回再提交不得再触发一次生成；S06-A「提交失败快照不变」语义不受影响
                GenerationRegistry registry = generationRegistry;
                if (registry != null && !registry.begin(state.getSessionId(), GENERATING_LEASE_MS)) {
                    return buildResult(state, "行程正在生成中，请稍候，无需重复提交。", null);
                }
                try {
                    requireStage(state, TravelStage.HOTELS);
                    state.getPickedHotelIds().addAll(ids);
                    if (regen) {
                        int wrapped = candidateService.nextHotelBatch(state);
                        sessionService.save(state);
                        return buildResult(state, wrapped == 0
                                ? "已从备选池为你翻出新的一批酒店（不重复、秒出，已勾选的保留置顶）："
                                : "备选池已展示完一轮，为你从头再翻一页（已勾选的保留置顶）：", null);
                    }
                    List<Long> validIds = state.getHotelPool() == null ? List.of()
                            : state.getHotelPool().stream().map(HotelCandidate::getHotelId).toList();
                    List<Long> ordered = orderedDistinct(ids);
                    validateSelected(state, ordered, validIds);
                    ChatStepResult blocked = lockAndCheckConflicts(state, "HOTEL", ordered);
                    if (blocked != null) {
                        return blocked;
                    }
                    state.setSelectedHotelIds(new ArrayList<>(ordered));
                    obsUserConfirmed(null, state, actor, "HOTEL", ordered.size(), 0);
                    // 生成前问卷闸门与跳过路径共用 finishWithItineraryCore（未作答先返回 PLAN_QUIZ 问卷）
                    return finishWithItineraryCore(state, "已确认酒店！");
                } finally {
                    if (registry != null) {
                        registry.end(state.getSessionId());
                    }
                }
            }
            default -> throw new BizException(ResultCode.PARAM_ERROR);
        }
    }

    /** S07：有序去重（未知地点已由 validateSelected 拒绝，不因用户传入自动成为可信锁定项） */
    private List<Long> orderedDistinct(List<Long> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 跳过酒店/美食环节后直接生成行程（与确认酒店同路径：同步生成 + 生成中守卫） */
    private ChatStepResult finishWithItinerary(TravelState state, String ack) {
        GenerationRegistry registry = generationRegistry;
        if (registry != null && !registry.begin(state.getSessionId(), GENERATING_LEASE_MS)) {
            return buildResult(state, "行程正在生成中，请稍候，无需重复提交。", null);
        }
        try {
            return finishWithItineraryCore(state, ack);
        } catch (OpAbortException abort) {
            // 审计落库：生成被发布检查拒绝（422）也写入 FAILED 记录，管理后台可定位失败步骤与原因
            usageService.recordOp(state.getSessionId(), state.getUserId(), state.getUsername(),
                    state.getStage().name(), "生成失败", "FAILED",
                    abort.getCustomMessage() == null ? String.join("；", abort.getViolations())
                            : abort.getCustomMessage(),
                    UsageChannel.RULE_FALLBACK);
            throw abort;
        } finally {
            if (registry != null) {
                registry.end(state.getSessionId());
            }
        }
    }

    /** 生成行程 + 待确认分支 + 推进 DONE（不含生成租约；调用方负责占用与失败审计） */
    private ChatStepResult finishWithItineraryCore(TravelState state, String ack) {
        if (!Boolean.TRUE.equals(state.getPlanQuizAnswered())) {
            // 生成前问卷闸门：酒店确认与所有跳过路径统一先过问卷，未作答则进入 PLAN_QUIZ 阶段
            state.setStage(TravelStage.PLAN_QUIZ);
            sessionService.save(state);
            ChatStepResult r = buildResult(state, ack
                    + "\n\n在生成行程前，请你确认几个安排细节（选择或直接输入即可）：", null);
            r.setPlanQuiz(buildPlanQuiz(state));
            return r;
        }
        state.setStage(TravelStage.ITINERARY);
        itineraryService.generate(state);
        if (Boolean.TRUE.equals(state.getPendingFatigueConfirm())) {
            // B：疲劳超载草稿留存待确认——保存待确认状态，不推进 DONE、不发布
            sessionService.save(state);
            ChatStepResult r = buildResult(state, ack + "\n\n" + fatiguePendingText(state)
                    + missingSelectedNote(state), null);
            r.setStatus("NEEDS_CONFIRMATION");
            return r;
        }
        if (Boolean.TRUE.equals(state.getPendingBudgetConfirm())) {
            // 预算超支知情放行：保存待确认状态，不推进 DONE、不发布
            sessionService.save(state);
            ChatStepResult r = buildResult(state, ack + "\n\n" + budgetPendingText(state)
                    + missingSelectedNote(state), null);
            r.setStatus("NEEDS_CONFIRMATION");
            return r;
        }
        state.setStage(TravelStage.DONE);
        sessionService.save(state);
        return buildResult(state, ack + "\n\n行程已生成（含预计消费），点击地点可查看具体路径："
                + missingSelectedNote(state), null);
    }

    /** 问卷问题集合：按会话状态判定（跳过景点→不问倾向/夜景；无夜景景点→不问夜景数量） */
    private PlanQuiz buildPlanQuiz(TravelState state) {
        boolean attractionsSkipped = Boolean.TRUE.equals(state.getNoAttractionNeeded())
                || state.getSelectedAttractionIds().isEmpty();
        List<AttractionCandidate> pool = state.getAttractionPool() == null ? List.of()
                : state.getAttractionPool();
        Map<Long, AttractionCandidate> byId = pool.stream().collect(Collectors.toMap(
                AttractionCandidate::getAttractionId, c -> c, (a, b) -> a));
        List<AttractionCandidate> nightSelected = state.getSelectedAttractionIds().stream()
                .map(byId::get).filter(Objects::nonNull)
                .filter(c -> NightScorer.isNight(c.getTags()))
                .toList();
        AttractionCandidate top = nightSelected.stream()
                .max(Comparator.comparingInt(c -> NightScorer.score(c.getTags()))).orElse(null);

        PlanQuiz quiz = new PlanQuiz();
        quiz.setHotelSelected(!state.getSelectedHotelIds().isEmpty());
        quiz.setHasNight(!nightSelected.isEmpty());
        quiz.setAskActivityBias(!attractionsSkipped);
        quiz.setAskNightPlan(!attractionsSkipped && !nightSelected.isEmpty());
        quiz.setTopNightName(top == null ? null : top.getName());
        return quiz;
    }

    /** 问卷是否已答完（所有按当前会话需要问的问题都有答案） */
    private boolean quizResolved(TravelState state) {
        PlanQuiz quiz = buildPlanQuiz(state);
        TravelPreference p = state.getPreference();
        if (p == null || p.getWakeTime() == null || p.getWakeTime().isBlank()
                || p.getReturnDeadline() == null || p.getReturnDeadline().isBlank()) {
            return false;
        }
        if (quiz.isAskActivityBias() && (p.getActivityBias() == null || p.getActivityBias().isBlank())) {
            return false;
        }
        if (quiz.isAskNightPlan() && (p.getNightPlan() == null || p.getNightPlan().isBlank())) {
            return false;
        }
        return true;
    }

    /** 问卷提交：校验合法值 → 写入偏好 → 复用生成链路（含疲劳/预算待确认分支） */
    public ChatStepResult submitPlanQuiz(AuthenticatedUser actor, PlanQuizRequest req) {
        TravelState state = sessionService.loadOwned(req.getSessionId(), actor.id());
        requireStage(state, TravelStage.PLAN_QUIZ);
        PlanQuiz quiz = buildPlanQuiz(state);
        TravelPreference p = state.getPreference();
        if (p == null) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        String wake = blankToNull(req.getWakeTime());
        String deadline = blankToNull(req.getReturnDeadline());
        if (wake == null || !wake.matches(TIME_PATTERN) || wake.compareTo("05:00") < 0 || wake.compareTo("12:00") > 0) {
            throw new BizException(ResultCode.PARAM_ERROR.getCode(), "起床时间需在 05:00-12:00 之间（HH:mm）");
        }
        if (deadline == null || (!"UNLIMITED".equals(deadline)
                && (!deadline.matches(TIME_PATTERN) || deadline.compareTo("17:00") < 0 || deadline.compareTo("24:00") > 0))) {
            throw new BizException(ResultCode.PARAM_ERROR.getCode(), "回家/回酒店时间需在 17:00-24:00 之间（HH:mm）或 UNLIMITED");
        }
        p.setWakeTime(wake);
        p.markConfirmed("wakeTime");
        p.setReturnDeadline(deadline);
        p.markConfirmed("returnDeadline");
        if (quiz.isAskActivityBias()) {
            String bias = blankToNull(req.getActivityBias());
            if (bias == null || !Set.of("MORNING", "BALANCED", "EVENING").contains(bias)) {
                throw new BizException(ResultCode.PARAM_ERROR.getCode(), "活动倾向取值非法");
            }
            p.setActivityBias(bias);
            p.markConfirmed("activityBias");
        }
        if (quiz.isAskNightPlan()) {
            String night = blankToNull(req.getNightPlan());
            if (night == null || !Set.of("ONE", "ALL").contains(night)) {
                throw new BizException(ResultCode.PARAM_ERROR.getCode(), "夜景数量取值非法");
            }
            p.setNightPlan(night);
            p.markConfirmed("nightPlan");
        }
        state.setPlanQuizAnswered(true);
        return finishWithItinerary(state, "已收到你的安排偏好，正在生成行程：");
    }

    /** B：疲劳超载待确认的提示文案（含最满一天的疲劳分与两个操作方向） */
    private String fatiguePendingText(TravelState state) {
        Double s = state.getPendingFatigueScore();
        String score = s == null ? "" : String.format("（疲劳分约 %.1f，安全值 9）", s);
        return "行程已生成，但强度偏高" + score + "，已为你安排休息点。"
                + "请点击下方「确认生成」发布行程，或「返回调整」减少景点后重新确认。";
    }

    /** 预算超支知情放行的提示文案（含超支金额与两个操作方向） */
    private String budgetPendingText(TravelState state) {
        BigDecimal over = state.getPendingBudgetOver();
        BigDecimal budget = state.getPreference() == null ? null : state.getPreference().getTotalBudget();
        String part = over == null ? "餐饮费用超出预算" : "餐饮费用超出预算 " + over + " 元"
                + (budget == null ? "" : "（预算 " + budget + " 元）");
        return "行程已生成，但" + part + "。请点击下方「确认发布」按超支自付发布行程，"
                + "或「返回调整」换更实惠的餐厅后重新确认。";
    }

    /**
     * S07：把本轮确认写入锁定集合；锁定项与新约束存在冲突时阻止直接提交，
     * 返回 NEEDS_CONFIRMATION 结果（不推进阶段、不落库任何已提交结果）。
     */
    private ChatStepResult lockAndCheckConflicts(TravelState state, String type, List<Long> ids) {
        for (Long id : ids) {
            state.lockedSelection().lock(CandidateService.placeKey(type, id), "USER_LOCKED");
        }
        LockedSelection locked = state.lockedSelection();
        if (locked.hasConflicts()) {
            ChatStepResult r = buildResult(state,
                    "有已锁定的地点与最新需求冲突，请先解锁相关地点或调整需求，再继续确认。", null);
            r.setStatus("NEEDS_CONFIRMATION");
            r.setConflictCodes(new ArrayList<>(locked.getConflictCodes()));
            return r;
        }
        return null;
    }

    private void requireStage(TravelState state, TravelStage expected) {
        if (state.getStage() != expected) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
    }

    private void validateSelected(TravelState state, List<Long> selectedIds, List<Long> validIds) {
        if (selectedIds == null || selectedIds.isEmpty() || !validIds.containsAll(selectedIds)) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
    }

    /** 已勾选项的店名/景点名（用量留痕用，截断防超长） */
    private String selectedNamesSuffix(TravelState state, String candidateType, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        Map<Long, String> names = new LinkedHashMap<>();
        switch (candidateType == null ? "" : candidateType.toUpperCase()) {
            case "ATTRACTION" -> {
                if (state.getAttractionPool() != null) {
                    for (AttractionCandidate c : state.getAttractionPool()) {
                        names.put(c.getAttractionId(), c.getName());
                    }
                }
            }
            case "FOOD" -> {
                if (state.getFoodPool() != null) {
                    for (FoodCandidate g : state.getFoodPool()) {
                        for (FoodCandidate.FoodItem it : g.getRestaurants()) {
                            names.put(it.getRestaurantId(), it.getName());
                        }
                    }
                }
            }
            case "HOTEL" -> {
                if (state.getHotelPool() != null) {
                    for (HotelCandidate h : state.getHotelPool()) {
                        names.put(h.getHotelId(), h.getName());
                    }
                }
            }
            default -> {
            }
        }
        String joined = ids.stream()
                .map(id -> names.getOrDefault(id, "ID" + id))
                .collect(java.util.stream.Collectors.joining("、"));
        return "：" + UsageService.clip(joined, 400);
    }

    /** 已勾选但未安排进行程的景点提示（受硬约束限制被少排时明确告知，不静默遗漏） */
    private String missingSelectedNote(TravelState state) {
        ItineraryPlan plan = state.getPlan();
        if (plan == null || state.getSelectedAttractionIds() == null
                || state.getSelectedAttractionIds().isEmpty()) {
            return "";
        }
        Set<Long> planned = new LinkedHashSet<>();
        for (DailyPlan d : plan.getDays()) {
            if (d.getNodes() == null) {
                continue;
            }
            for (PlanNode n : d.getNodes()) {
                if ("attraction".equals(n.getType()) && n.getPlaceId() != null) {
                    planned.add(n.getPlaceId());
                }
            }
        }
        List<Long> missing = state.getSelectedAttractionIds().stream()
                .filter(id -> !planned.contains(id)).toList();
        if (missing.isEmpty()) {
            return "";
        }
        Map<Long, String> nameById = new LinkedHashMap<>();
        if (state.getAttractionPool() != null) {
            for (AttractionCandidate c : state.getAttractionPool()) {
                nameById.put(c.getAttractionId(), c.getName());
            }
        }
        String names = missing.stream()
                .map(id -> nameById.getOrDefault(id, "景点#" + id))
                .collect(java.util.stream.Collectors.joining("、"));
        return "\n\n⚠️ 提示：你勾选的「" + names + "」未能排进行程"
                + "（当天时间装不下：截止时间/开放时间受限）。可以直接说「把XX也安排进去」或「回家时间放宽到XX点」让我重排，"
                + "我会调整顺序尽量全部保留。";
    }

    /** 同步生成行程（幂等：已生成则直接返回） */
    public ChatStepResult generateItinerary(AuthenticatedUser actor, String sessionId) {
        TravelState state = sessionService.loadOwned(sessionId, actor.id());
        if (state.getPlan() != null) {
            return buildResult(state, "行程已生成：" + missingSelectedNote(state), null);
        }
        if (state.getStage() != TravelStage.ITINERARY) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        itineraryService.generate(state);
        if (Boolean.TRUE.equals(state.getPendingFatigueConfirm())) {
            // B：疲劳超载草稿留存待确认——不推进 DONE、不发布
            sessionService.save(state);
            ChatStepResult r = buildResult(state, fatiguePendingText(state), null);
            r.setStatus("NEEDS_CONFIRMATION");
            return r;
        }
        if (Boolean.TRUE.equals(state.getPendingBudgetConfirm())) {
            // 预算超支知情放行——不推进 DONE、不发布
            sessionService.save(state);
            ChatStepResult r = buildResult(state, budgetPendingText(state), null);
            r.setStatus("NEEDS_CONFIRMATION");
            return r;
        }
        state.setStage(TravelStage.DONE);
        sessionService.save(state);
        return buildResult(state, "行程已生成！以下是为您规划的行程：" + missingSelectedNote(state), null);
    }

    /**
     * B：疲劳超载待确认草稿的裁决入口。confirm=true 发布行程（知情确认，落库 + 审计标记）；
     * confirm=false 返回景点选择阶段（清空待确认状态，保留已选与备选池，供减景点后重新确认）。
     */
    public ChatStepResult confirmFatigue(AuthenticatedUser actor, String sessionId, boolean confirm) {
        TravelState state = sessionService.loadOwned(sessionId, actor.id());
        if (!Boolean.TRUE.equals(state.getPendingFatigueConfirm()) || state.getPendingPlan() == null) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        if (confirm) {
            itineraryService.publishPending(state);
            state.setFatigueOverrideAccepted(true);
            state.setPendingPlan(null);
            state.setPendingFatigueConfirm(false);
            state.setPendingFatigueScore(null);
            state.setStage(TravelStage.DONE);
            sessionService.save(state);
            log.info("[Orch][sessionId={}] 用户确认发布疲劳超载行程（知情确认）", sessionId);
            return buildResult(state, "已按你的确认发布行程（强度偏高，请留意途中休息）：", null);
        }
        state.setPendingPlan(null);
        state.setPendingFatigueConfirm(false);
        state.setPendingFatigueScore(null);
        state.setStage(TravelStage.ATTRACTIONS);
        sessionService.save(state);
        return buildResult(state, "好的，已返回景点选择：取消勾选部分景点后重新确认即可；"
                + "若想调整体力水平，可回首页重新开始规划。", null);
    }

    /**
     * 预算超支知情放行的裁决入口。confirm=true 发布行程（超支自付，落库 + 审计标记）；
     * confirm=false 返回美食选择阶段（清空待确认状态，保留已选与备选池，供换餐厅后重新确认）。
     */
    public ChatStepResult confirmBudget(AuthenticatedUser actor, String sessionId, boolean confirm) {
        TravelState state = sessionService.loadOwned(sessionId, actor.id());
        if (!Boolean.TRUE.equals(state.getPendingBudgetConfirm()) || state.getPendingPlan() == null) {
            throw new BizException(ResultCode.STATE_CONFLICT);
        }
        if (confirm) {
            itineraryService.publishPending(state);
            state.setBudgetOverrideAccepted(true);
            state.setPendingPlan(null);
            state.setPendingBudgetConfirm(false);
            state.setPendingBudgetOver(null);
            state.setStage(TravelStage.DONE);
            sessionService.save(state);
            log.info("[Orch][sessionId={}] 用户确认发布超预算行程（知情放行）", sessionId);
            return buildResult(state, "已按你的确认发布行程（餐饮超出预算，超支部分自付）：", null);
        }
        state.setPendingPlan(null);
        state.setPendingBudgetConfirm(false);
        state.setPendingBudgetOver(null);
        state.setStage(TravelStage.FOODS);
        sessionService.save(state);
        return buildResult(state, "好的，已返回美食选择：取消勾选较贵的餐厅、勾选更实惠的餐厅后重新确认即可；"
                + "若想调高预算，可回首页重新开始规划。", null);
    }

    // ==================== S06-B：操作协议入口（幂等 / 并发 / 恢复） ====================

    /**
     * 带 requestId/expectedRevision 的行程生成：T1 领取执行权（幂等）→ 事务外模型与校验 →
     * VALIDATED 草案 → T2 短事务提交。相同 requestId 重试直接复用原操作，不重新调用模型。
     */
    public ChatStepResult generateItineraryOp(AuthenticatedUser actor, String sessionId,
                                              String requestId, Long expectedRevision) {
        TravelOperationService.OperationHandle h = operationService.acquire(actor, sessionId, requestId,
                expectedRevision == null ? 0L : expectedRevision, "GENERATE_ITINERARY", "{}");
        return driveGenerate(actor, sessionId, h);
    }

    /** 带 requestId/expectedRevision 的行程调整：旧行条件归档仲裁并发，T2 失败整体回滚 */
    public ChatStepResult adjustItineraryOp(AuthenticatedUser actor, String sessionId, String requestId,
                                            Long expectedRevision, Long itineraryId, String message) {
        if (itineraryId == null || message == null || message.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of("itineraryId", itineraryId, "message", message));
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
        TravelOperationService.OperationHandle h = operationService.acquire(actor, sessionId, requestId,
                expectedRevision == null ? 0L : expectedRevision, "ADJUST", body);
        return driveAdjust(actor, sessionId, h, itineraryId, message);
    }

    /**
     * S09 局部补丁调整：T1 幂等领取 → 事务外提出/验证/应用补丁（白名单操作 + 稳定 nodeId）→
     * T2 短事务提交新版本（条件归档仲裁并发，败者 REVISION_CONFLICT）。
     * 相同 requestId 重放复用原操作，不再次调用提供方。
     */
    public ChatStepResult adjustPatchOp(AuthenticatedUser actor, String sessionId, String requestId,
                                        Long expectedRevision, Long itineraryId, String message) {
        if (itineraryId == null || message == null || message.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        TravelOperationService.OperationHandle h = operationService.acquire(actor, sessionId, requestId,
                expectedRevision == null ? 0L : expectedRevision, "ADJUST_PATCH", message);
        return drivePatch(actor, sessionId, h, itineraryId, message);
    }

    /** 操作查询：先检查 owner；返回状态与结果（不含内部 prompt/快照） */
    public TravelOperationService.OperationView queryOperation(AuthenticatedUser actor, String operationId) {
        return operationService.query(actor, operationId);
    }

    /**
     * S11 显式取消（SSE 断开同路径）：持久化取消标记 + 通知在途执行线程；
     * 晚到结果在提交/修复前被取消检查拒绝。已终态操作幂等不受影响。
     */
    public TravelOperationService.OperationView cancelOperation(AuthenticatedUser actor, String operationId) {
        operationService.cancel(actor, operationId);
        return operationService.query(actor, operationId);
    }

    /** 操作受理结果（202 协议） */
    public record OperationAccepted(String operationId, String status, Long itineraryId) {
    }

    /**
     * 同步领取（T1）：同 requestId 幂等复用、hash 冲突 409、占用/版本冲突 409、存储故障 503。
     * 执行（模型 + T2）由 runGenerateOpAsync 在后台完成，客户端轮询 operation，不得重生成。
     */
    public OperationAccepted acceptGenerateOp(AuthenticatedUser actor, String sessionId,
                                              String requestId, Long expectedRevision) {
        TravelOperationService.OperationHandle h = operationService.acquire(actor, sessionId, requestId,
                expectedRevision == null ? 0L : expectedRevision, "GENERATE_ITINERARY", "{}");
        return new OperationAccepted(h.operationId(), h.status(), h.itineraryId());
    }

    /** 后台执行已受理的生成操作：只执行一次；重复调度/重连不会再次执行 */
    public void runGenerateOpAsync(AuthenticatedUser actor, String sessionId, String operationId) {
        try {
            taskExecutor.execute(() -> {
                TravelOperationService.OperationHandle h;
                try {
                    h = operationService.handleFor(operationId, actor);
                } catch (Exception e) {
                    log.warn("[Op] 后台生成取操作失败 {}: {}", operationId, e.getMessage());
                    return;
                }
                if (!TravelOperationService.STATUS_RUNNING.equals(h.status())) {
                    return; // 已终态（幂等复用/重复调度）：不再执行
                }
                try {
                    driveGenerate(actor, sessionId, h);
                } catch (Exception e) {
                    operationService.markFailed(operationId, h.attemptNo(),
                            ResultCode.SYSTEM_ERROR.getCode() + ":" + e.getClass().getSimpleName(),
                            e.getMessage() == null ? "" : e.getMessage());
                    log.warn("[Op] 后台生成失败 {}: {}", operationId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            // S11 背压：有界队列满 → 快速拒绝（BUSY 终态 + 释放会话占位），客户端可凭原 requestId 重试
            operationService.markBusy(operationId);
            log.warn("[Op] 队列饱和拒绝操作 {}：{}", operationId, e.getMessage());
        }
    }

    /** 当前会话快照（409 冲突后重载状态 / 202 轮询完成后取已提交结果） */
    public ChatStepResult currentSession(AuthenticatedUser actor, String sessionId) {
        TravelState state = sessionService.loadOwned(sessionId, actor.id());
        Question q = state.getStage() == TravelStage.PREFERENCE ? nextQuestion(state) : null;
        return buildResult(state, "已同步最新会话状态。", q);
    }

    /** 断点恢复：当前用户最近一条可恢复的未结束会话；无/过期返回 null（首页卡片据此显示） */
    public ResumeView resumableSession(AuthenticatedUser actor) {
        ResumeView view = sessionService.findResumable(actor.id()).orElse(null);
        return fillGenerating(view);
    }

    /** 断点恢复（按 sessionId 校验，深链接恢复分支）：同一套恢复口径，过期/越权返回 null */
    public ResumeView resumableSession(AuthenticatedUser actor, String sessionId) {
        ResumeView view = sessionService.findResumable(actor.id(), sessionId).orElse(null);
        return fillGenerating(view);
    }

    /** 进程内同步生成标记回填（恢复端据此轮询，不重复提交） */
    private ResumeView fillGenerating(ResumeView view) {
        if (view != null && generationRegistry != null) {
            view.setGenerating(generationRegistry.isGenerating(view.getSessionId()));
        }
        return view;
    }

    /** 会话聊天历史回放（恢复窗口用）：先归属校验（不存在/越权统一 404），再读用量流水 */
    public java.util.List<HistoryItem> chatHistory(AuthenticatedUser actor, String sessionId) {
        sessionService.loadOwned(sessionId, actor.id());
        return usageService.chatHistory(sessionId, 200);
    }

    private ChatStepResult driveGenerate(AuthenticatedUser actor, String sessionId,
                                         TravelOperationService.OperationHandle h) {
        if (h.reused()) {
            return replyOfReused(actor, sessionId, h);
        }
        TravelState st = h.frozen();
        if (st == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
        // S12：operation 根 span（关联字段 + 版本快照）；提供方 attempt span 挂接其下
        com.ghy.mutiagent.trace.TraceMeta meta = new com.ghy.mutiagent.trace.TraceMeta(
                h.operationId(), actor.id(), tracePromptVersion, traceModelVersion,
                (long) st.getConstraintRevision(), snapshotHashOf(st));
        TraceContext opCtx = traceService.newTrace(st.getSessionId(), "操作:" + h.operationId());
        opCtx.setSpanId(h.operationId());
        opCtx.setOperationId(h.operationId());
        opCtx.setOwnerId(actor.id());
        opCtx.setKind("OPERATION");
        opCtx.setPromptVersion(meta.promptVersion());
        opCtx.setModelVersion(meta.modelVersion());
        opCtx.setConstraintRevision(meta.constraintRevision());
        opCtx.setSnapshotHash(meta.snapshotHash());
        traceService.register(opCtx);
        obsOperationStarted(h.operationId(), st, actor.id());
        obsNodeStarted(h.operationId(), st, actor.id(), "plan", "plan-x1",
                Map.of("sessionId", st.getSessionId()));
        if (st.getPlan() == null || st.getPlan().getDays() == null) {
            CancelRegistry.CancelToken cancelToken = operationService.token(h.operationId());
            try {
                Map<String, Object> evidence = itineraryService.plan(st, cancelToken, meta); // 模型 + 规则 + 校验，事务外
                // B：疲劳超载草稿无法走 VALIDATED 草案提交（plan 为空），以明确终态快速失败；
                // 待确认交互走同步生成路径（候选确认入口）
                if (Boolean.TRUE.equals(st.getPendingFatigueConfirm())) {
                    throw new OpAbortException(ResultCode.PLAN_INVALID, fatiguePendingText(st),
                            "NEEDS_CONFIRMATION", List.of("AWAITING_FATIGUE_CONFIRM"),
                            List.of(ItineraryValidator.HARD_FATIGUE_EXCEEDED),
                            Map.of("awaitingFatigueConfirm", true));
                }
                if (Boolean.TRUE.equals(st.getPendingBudgetConfirm())) {
                    throw new OpAbortException(ResultCode.PLAN_INVALID, budgetPendingText(st),
                            "NEEDS_CONFIRMATION", List.of("AWAITING_BUDGET_CONFIRM"),
                            List.of(ItineraryValidator.BUDGET_EXCEEDED),
                            Map.of("awaitingBudgetConfirm", true));
                }
                obsNodeEnded(h.operationId(), st, actor.id(), "plan", "plan-x1",
                        validationOutcomeOf(evidence),
                        Map.of("codes", validationCodesOf(evidence)));
                // S11：晚到结果在提交前被取消检查拒绝（plan 内部已复查，此处为提交前兜底护栏）
                if (cancelToken != null && cancelToken.isCancelled()) {
                    throw new OpAbortException(ResultCode.OPERATION_CANCELLED, "CANCELLED",
                            List.of("CANCELLED"), List.of(), Map.of());
                }
                operationService.recordProviderAttempt(h.operationId(), h.attemptNo());
                operationService.saveValidatedDraft(h.operationId(), h.attemptNo(),
                        operationService.wrapDraft(null, st.getPlan(), evidence));
                try {
                    ChatStepResult committed = finishCommit(h);
                    // S12：业务结果与持久化状态分层记录（提交成功才 COMMITTED）
                    opCtx.setBusinessStatus("COMMITTED");
                    opCtx.setPersistenceStatus("COMMITTED");
                    opCtx.finish("SUCCESS");
                    obsCommitResult(h.operationId(), st, actor.id(), "COMMITTED",
                            Map.of("persistenceStatus", "COMMITTED"));
                    obsOperationEnded(h.operationId(), st, actor.id(), "COMMITTED", "COMMITTED");
                    return committed;
                } catch (BizException e) {
                    // S12：校验通过但持久化失败——业务结果仍为 FAILED，与解析失败区分
                    opCtx.setBusinessStatus("FAILED");
                    opCtx.setPersistenceStatus("FAILED");
                    opCtx.finish("FAILED");
                    obsCommitResult(h.operationId(), st, actor.id(), "COMMIT_FAILED",
                            Map.of("persistenceStatus", "FAILED"));
                    obsOperationEnded(h.operationId(), st, actor.id(), "FAILED", "FAILED");
                    throw e;
                }
            } catch (OpAbortException abort) {
                // S08/S11/S12 终止语义：业务结果 = 操作终态，持久化未尝试（提交失败已在内部记录 FAILED，不覆盖）
                if (opCtx.getPersistenceStatus() == null) {
                    opCtx.setBusinessStatus(abort.getOpStatus());
                    opCtx.setPersistenceStatus("NOT_ATTEMPTED");
                }
                opCtx.finish("FAILED");
                obsCancelled(h.operationId(), st, actor.id(), abort.getOpStatus());
                obsOperationEnded(h.operationId(), st, actor.id(), abort.getOpStatus(), "NOT_ATTEMPTED");
                operationService.markTerminal(h.operationId(), h.attemptNo(), abort.getOpStatus(),
                        abort.getCode() + ":" + abort.getClass().getSimpleName(), abortDetail(abort));
                throw abort;
            } catch (BizException e) {
                // S12：解析/校验失败——业务 FAILED，持久化未尝试（提交失败已在内部记录 FAILED，不覆盖）
                if (opCtx.getPersistenceStatus() == null) {
                    opCtx.setBusinessStatus("FAILED");
                    opCtx.setPersistenceStatus("NOT_ATTEMPTED");
                }
                opCtx.finish("FAILED");
                obsOperationEnded(h.operationId(), st, actor.id(), "FAILED", "NOT_ATTEMPTED");
                operationService.markFailed(h.operationId(), h.attemptNo(),
                        e.getCode() + ":" + e.getClass().getSimpleName(), e.getMessage());
                throw e;
            }
        }
        operationService.saveValidatedDraft(h.operationId(), h.attemptNo(),
                operationService.wrapDraft(null, st.getPlan()));
        opCtx.setBusinessStatus("COMMITTED");
        opCtx.setPersistenceStatus("COMMITTED");
        opCtx.finish("SUCCESS");
        obsCommitResult(h.operationId(), st, actor.id(), "COMMITTED", Map.of("reusedPlan", true));
        obsOperationEnded(h.operationId(), st, actor.id(), "COMMITTED", "COMMITTED");
        return finishCommit(h);
    }

    // ==================== Observability 薄埋点（可空，无副作用） ====================

    private void obsOperationStarted(String operationId, TravelState st, Long ownerId) {
        if (obsInstrumentation != null) {
            obsInstrumentation.operationStarted(operationId, st.getSessionId(), ownerId);
        }
    }

    private void obsOperationEnded(String operationId, TravelState st, Long ownerId,
                                   String businessStatus, String persistenceStatus) {
        if (obsInstrumentation != null) {
            obsInstrumentation.operationEnded(operationId, st.getSessionId(), ownerId,
                    businessStatus, persistenceStatus);
        }
    }

    private void obsNodeStarted(String operationId, TravelState st, Long ownerId,
                                String nodeType, String nodeExec, Map<String, Object> summary) {
        if (obsInstrumentation != null) {
            obsInstrumentation.nodeStarted(operationId, st.getSessionId(), ownerId,
                    nodeExec, nodeType, summary);
        }
    }

    private void obsNodeEnded(String operationId, TravelState st, Long ownerId,
                              String nodeType, String nodeExec, String outcome,
                              Map<String, Object> summary) {
        if (obsInstrumentation != null) {
            obsInstrumentation.nodeEnded(operationId, st.getSessionId(), ownerId,
                    nodeExec, nodeType, outcome, summary);
        }
    }

    private void obsCommitResult(String operationId, TravelState st, Long ownerId,
                                 String outcome, Map<String, Object> summary) {
        if (obsInstrumentation != null) {
            obsInstrumentation.commitResult(operationId, st.getSessionId(), ownerId, outcome, summary);
        }
    }

    private void obsCancelled(String operationId, TravelState st, Long ownerId, String reason) {
        if (obsInstrumentation != null) {
            obsInstrumentation.cancelled(operationId, st.getSessionId(), ownerId, reason);
        }
    }

    private void obsUserConfirmed(String operationId, TravelState st, AuthenticatedUser actor,
                                  String candidateType, int selectedCount, int blockedCount) {
        if (obsInstrumentation != null) {
            obsInstrumentation.userConfirmed(operationId, st.getSessionId(),
                    actor == null ? null : actor.id(), candidateType, selectedCount, blockedCount);
        }
    }

    private static Object validationCodesOf(Map<String, Object> evidence) {
        if (evidence == null) {
            return List.of();
        }
        Object codes = evidence.get("finalValidationCodes");
        return codes == null ? evidence.getOrDefault("validationCodes", List.of()) : codes;
    }

    private static String validationOutcomeOf(Map<String, Object> evidence) {
        Object codes = validationCodesOf(evidence);
        return codes instanceof List<?> list && list.isEmpty() ? "PASS" : "VIOLATIONS";
    }

    /** S12 候选快照摘要：冻结输入的选中集合 + 天数 + 目的地（SHA-256 前 16 位，不可反推） */
    private String snapshotHashOf(TravelState st) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            String s = (st.getDestinationId() == null ? "" : st.getDestinationId()) + "|"
                    + (st.getPreference() == null ? "" : st.getPreference().getDays()) + "|"
                    + st.getSelectedAttractionIds() + "|" + st.getSelectedFoodIds() + "|"
                    + st.getSelectedHotelIds();
            md.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private ChatStepResult driveAdjust(AuthenticatedUser actor, String sessionId,
                                       TravelOperationService.OperationHandle h,
                                       Long itineraryId, String message) {
        if (h.reused()) {
            return replyOfReused(actor, sessionId, h);
        }
        ItineraryService.AdjustPlan ap;
        try {
            ap = itineraryService.prepareAdjust(actor, itineraryId, message, sessionId);
            Map<String, Object> evidence = itineraryService.plan(ap.state()); // 模型 + 校验，事务外
            operationService.recordProviderAttempt(h.operationId(), h.attemptNo());
            operationService.saveValidatedDraft(h.operationId(), h.attemptNo(),
                    operationService.wrapDraft(itineraryId, ap.state().getPlan(), evidence));
            return finishCommit(h);
        } catch (OpAbortException abort) {
            operationService.markTerminal(h.operationId(), h.attemptNo(), abort.getOpStatus(),
                    abort.getCode() + ":" + abort.getClass().getSimpleName(), abortDetail(abort));
            throw abort;
        } catch (BizException e) {
            operationService.markFailed(h.operationId(), h.attemptNo(),
                    e.getCode() + ":" + e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    /** S08 终止详情的可观察 JSON：原因码（TOKEN_BUDGET_EXHAUSTED/REPAIR_ATTEMPTS_EXCEEDED/…）+ 违规码 + 用户可读说明 */
    private String abortDetail(OpAbortException abort) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reasonCodes", abort.getReasonCodes());
        detail.put("violations", abort.getViolations());
        if (abort.getCustomMessage() != null) {
            detail.put("userMessage", abort.getCustomMessage());
        }
        detail.put("message", "CANCELLED".equals(abort.getOpStatus())
                ? "操作已取消。"
                : "DEADLINE_EXCEEDED".equals(abort.getOpStatus())
                        ? "操作超出时间限制已中止，请稍后重试。"
                        : "行程方案经修复仍未通过发布校验，需要你确认后处理"
                                + (abort.getViolations().isEmpty() ? "。" : "。违规：" + String.join("、", abort.getViolations())));
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** T2 提交 + 非权威缓存回填；缓存失败不影响已提交事务（S06-B） */
    private ChatStepResult finishCommit(TravelOperationService.OperationHandle h) {
        TravelOperationService.CommitOutcome out = operationService.commit(h.operationId(), h.attemptNo());
        try {
            TravelState finalState = objectMapper.readValue(out.finalStateJson(), TravelState.class);
            sessionService.cacheBestEffort(finalState);
            return buildResult(finalState, "行程已生成！以下是为您规划的行程：", null);
        } catch (Exception e) {
            log.warn("[Op] 提交结果反序列化失败（不影响已提交事务）: {}", e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
    }

    /**
     * S09 补丁执行：事务外提出/验证/应用补丁，T2 短事务提交新版本。
     * 范围外/候选证据不足/预算/校验失败走明确错误码（PatchRejectException）；
     * T2 条件归档版本冲突 → REVISION_CONFLICT。
     */
    private ChatStepResult drivePatch(AuthenticatedUser actor, String sessionId,
                                      TravelOperationService.OperationHandle h,
                                      Long itineraryId, String message) {
        if (h.reused()) {
            return replyOfReused(actor, sessionId, h);
        }
        ItineraryService.AdjustPlan ap;
        try {
            ap = itineraryService.prepareAdjust(actor, itineraryId, message, sessionId);
            TravelState sessionState = sessionService.loadOwned(sessionId, actor.id());
            Map<String, Object> evidence = itineraryService.applyPatch(ap.state(), ap.oldRow(), sessionState);
            operationService.recordProviderAttempt(h.operationId(), h.attemptNo());
            operationService.saveValidatedDraft(h.operationId(), h.attemptNo(),
                    operationService.wrapDraft(itineraryId, ap.state().getPlan(), evidence));
        } catch (PatchRejectException reject) {
            operationService.markFailed(h.operationId(), h.attemptNo(), reject.getErrorCode(), reject.getMessage());
            throw reject;
        } catch (OpAbortException abort) {
            operationService.markTerminal(h.operationId(), h.attemptNo(), abort.getOpStatus(),
                    abort.getCode() + ":" + abort.getClass().getSimpleName(), abortDetail(abort));
            throw abort;
        } catch (BizException e) {
            operationService.markFailed(h.operationId(), h.attemptNo(),
                    e.getCode() + ":" + e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
        try {
            return finishCommit(h);
        } catch (BizException e) {
            if (e.getCode() == ResultCode.STATE_CONFLICT.getCode()) {
                // T2 条件归档仲裁：同版本竞争败者 → REVISION_CONFLICT（新行已随事务回滚）
                operationService.markFailed(h.operationId(), h.attemptNo(),
                        "REVISION_CONFLICT", e.getMessage());
            }
            throw e;
        }
    }

    /** 幂等复用回复：COMPLETED 返回结果；UNKNOWN 明示不可重放；S08 终态给出对应用户语义；其余提示处理中 */
    private ChatStepResult replyOfReused(AuthenticatedUser actor, String sessionId,
                                         TravelOperationService.OperationHandle h) {
        TravelState st = sessionService.loadOwned(sessionId, actor.id());
        if (TravelOperationService.STATUS_COMPLETED.equals(h.status())) {
            return buildResult(st, "行程已生成！以下是为您规划的行程：", null);
        }
        if (TravelOperationService.STATUS_UNKNOWN.equals(h.status())) {
            return buildResult(st, "上次操作结果未知（可能已产生模型费用），请勿更换 requestId 重试；"
                    + "可稍后查询操作 " + h.operationId() + " 的结果。", null);
        }
        if (TravelOperationService.STATUS_NEEDS_CONFIRMATION.equals(h.status())) {
            return buildResult(st, "行程方案经多次修复仍未完全符合要求，已暂停并保留草稿，"
                    + "需要你确认或调整要求后再试（操作 " + h.operationId() + "）。", null);
        }
        if (TravelOperationService.STATUS_DEADLINE_EXCEEDED.equals(h.status())) {
            return buildResult(st, "本次操作超出时间限制已中止（可能已产生模型费用），"
                    + "请稍后重试（操作 " + h.operationId() + "）。", null);
        }
        if (TravelOperationService.STATUS_CANCELLED.equals(h.status())) {
            return buildResult(st, "操作已取消，本次未生成行程（操作 " + h.operationId()
                    + "）。", null);
        }
        if (TravelOperationService.STATUS_BUSY.equals(h.status())) {
            return buildResult(st, "系统繁忙，本次操作未执行（未产生模型费用）；"
                    + "请用原 requestId 重试（操作 " + h.operationId() + "）。", null);
        }
        return buildResult(st, "操作处理中，请稍后按同一 requestId 查询结果（操作 " + h.operationId() + "）。", null);
    }

    /** 归属预检：SSE 建连前同步校验（不存在/不属于当前用户 → 404，不建立连接） */
    public void requireOwnedSession(AuthenticatedUser actor, String sessionId) {
        sessionService.loadOwned(sessionId, actor.id());
    }

    /** SSE 流式生成行程：先推进度事件，再推结构化行程（异步任务内以不可变 actor 复核归属） */
    public void generateItineraryStreaming(AuthenticatedUser actor, String sessionId, SseEmitter emitter) {
        taskExecutor.execute(() -> {
            try {
                TravelState state = sessionService.loadOwned(sessionId, actor.id());
                if (state.getStage() != TravelStage.ITINERARY && state.getPlan() == null) {
                    emitStream(emitter, evt("error", "当前阶段不能生成行程"));
                    emitter.complete();
                    return;
                }
                emitStream(emitter, evt("stage", "正在规划行程…"));
                if (state.getPlan() == null) {
                    itineraryService.generate(state);
                    state.setStage(TravelStage.DONE);
                    sessionService.save(state);
                }
                Map<String, Object> m = evt("itinerary", null);
                m.put("itineraryId", state.getItineraryId());
                m.put("text", state.getItineraryText());
                m.put("plan", state.getPlan());
                emitStream(emitter, m);
                emitStream(emitter, evt("done", null));
                emitter.complete();
            } catch (Exception e) {
                log.warn("流式生成行程失败", e);
                emitStream(emitter, evt("error", e.getMessage()));
                emitter.complete();
            }
        });
    }

    private Map<String, Object> evt(String type, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (message != null) {
            m.put("message", message);
        }
        return m;
    }

    private void emitStream(SseEmitter emitter, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            // 客户端断开：流式常态，忽略
        }
    }

    // ============ 内部逻辑 ============

    /** LLM 解析（带 trace），失败时返回空更新（本轮不更新，下轮重试） */
    private Map<String, String> llmParse(TravelState state, String message, String currentField) {
        TraceContext ctx = traceService.newTrace(state.getSessionId(), "偏好解析");
        long startNs = System.nanoTime();
        try {
            String prefJson = objectMapper.writeValueAsString(state.getPreference());
            Result<String> r = preferenceAgent.parse(
                    prefJson, currentField == null ? "" : currentField, message);
            String content = recordAgent(ctx, "PreferenceAgent", startNs, r);
            PreferenceResult pr = JsonUtils.parse(content, PreferenceResult.class);
            if (pr == null) {
                throw new IllegalStateException("偏好解析结果为空");
            }
            if (pr.getConflict() != null && !pr.getConflict().isBlank()) {
                state.setConflict(pr.getConflict());
            }
            ctx.finish("SUCCESS");
            traceService.finish(ctx);
            int[] tk = tokenCounts(r.tokenUsage());
            usageService.recordAgent(state.getSessionId(), state.getUserId(), state.getUsername(),
                    "PREFERENCE", "偏好解析", "PreferenceAgent", r.tokenUsage(),
                    (System.nanoTime() - startNs) / 1_000_000, "SUCCESS", trim50(message),
                    defaultModel, UsageChannel.AGENT, UsageService.clip(content, 2000));
            state.addTurnUsage(defaultModel, tk[0], tk[1], UsageChannel.AGENT);
            return pr.getUpdates() == null ? Map.of() : pr.getUpdates();
        } catch (Exception e) {
            log.warn("偏好解析 LLM 调用失败（{}），本轮按未解析处理", e.getClass().getName(), e);
            ctx.finish("FAILED");
            traceService.finish(ctx);
            usageService.recordAgent(state.getSessionId(), state.getUserId(), state.getUsername(),
                    "PREFERENCE", "偏好解析", "PreferenceAgent", null,
                    (System.nanoTime() - startNs) / 1_000_000, "FAILED",
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    defaultModel, UsageChannel.RULE_FALLBACK, null);
            state.addTurnUsage(null, 0, 0, UsageChannel.RULE_FALLBACK);
            return Map.of();
        }
    }

    /** 需求分析路由（带 trace + 用量记录）：Agent 判定 workflow/agent 路径；失败按标准流程处理 */
    private RequirementAnalysis analyzeRequirement(TravelState state) {
        TraceContext ctx = traceService.newTrace(state.getSessionId(), "需求分析");
        long startNs = System.nanoTime();
        try {
            String prefJson = objectMapper.writeValueAsString(state.getPreference());
            Result<String> r = requirementAgent.analyze(prefJson);
            String content = recordAgent(ctx, "RequirementAgent", startNs, r);
            RequirementAnalysis a = JsonUtils.parse(content, RequirementAnalysis.class);
            if (a == null || a.getMode() == null || a.getMode().isBlank()) {
                // 模型偶发把结果包在别的键里：统一解析层泛化查找含 mode 字段的对象
                a = AgentOutputParser.nestedRequirement(JsonUtils.readTree(content));
            }
            if (a == null || a.getMode() == null || a.getMode().isBlank()) {
                log.warn("需求分析路由 LLM 输出不符合模板，原始输出前300字：{}", UsageService.clip(content, 300));
                throw new IllegalStateException("需求分析结果为空");
            }
            ctx.finish("SUCCESS");
            traceService.finish(ctx);
            int[] tk = tokenCounts(r.tokenUsage());
            usageService.recordAgent(state.getSessionId(), state.getUserId(), state.getUsername(),
                    "PREFERENCE", "需求分析路由", "RequirementAgent", r.tokenUsage(),
                    (System.nanoTime() - startNs) / 1_000_000, "SUCCESS",
                    "mode=" + a.getMode(), defaultModel, UsageChannel.AGENT,
                    UsageService.clip(content, 2000));
            state.addTurnUsage(defaultModel, tk[0], tk[1], UsageChannel.AGENT);
            return a;
        } catch (Exception e) {
            log.warn("需求分析路由 LLM 失败（{}），改用关键词规则分析", e.getClass().getName(), e);
            ctx.finish("FAILED");
            traceService.finish(ctx);
            usageService.recordAgent(state.getSessionId(), state.getUserId(), state.getUsername(),
                    "PREFERENCE", "需求分析路由", "RequirementAgent", null,
                    (System.nanoTime() - startNs) / 1_000_000, "FAILED",
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    defaultModel, UsageChannel.RULE_FALLBACK, null);
            state.addTurnUsage(null, 0, 0, UsageChannel.RULE_FALLBACK);
            return fallbackAnalysis(state);
        }
    }

    /** LLM 不可用时的关键词规则兜底：从特殊需求文字推断主导需求，保证权重调整可用 */
    private RequirementAnalysis fallbackAnalysis(TravelState state) {
        String text = state.getPreference() == null || state.getPreference().getSpecialRequests() == null
                ? "" : state.getPreference().getSpecialRequests();
        int path = 3, cost = 3, sight = 3, food = 3;
        if (text.contains("吃") || text.contains("美食") || text.contains("口味") || text.contains("餐厅")
                || text.contains("氛围感") || text.contains("约会")) {
            food = 5;
        }
        if (text.contains("省钱") || text.contains("便宜") || text.contains("预算") || text.contains("经济")) {
            cost = 5;
        }
        if (text.contains("老人") || text.contains("小孩") || text.contains("不想折腾") || text.contains("省事")
                || text.contains("方便") || text.contains("体力")) {
            path = 5;
        }
        if (text.contains("拍照") || text.contains("打卡") || text.contains("玩") || text.contains("夜景")) {
            sight = 5;
        }
        RequirementAnalysis a = new RequirementAnalysis();
        a.setNeeds(Map.of("path", path, "cost", cost, "sightseeing", sight, "food", food));
        if (path > 3 || cost > 3 || sight > 3 || food > 3) {
            a.setMode("agent");
            List<String> focus = new ArrayList<>();
            if (food > 3) focus.add("美食与氛围");
            if (cost > 3) focus.add("控制预算");
            if (path > 3) focus.add("行程省事");
            if (sight > 3) focus.add("拍照打卡");
            a.setFocus(focus);
            a.setBrief("按你的特殊需求「" + (text.length() > 20 ? text.substring(0, 20) + "…" : text)
                    + "」做重点筛选");
        } else {
            a.setMode("workflow");
            a.setBrief("需求比较常规");
        }
        return a;
    }

    private String focusOf(RequirementAnalysis a) {
        return a.getFocus() == null || a.getFocus().isEmpty() ? "个性化偏好" : String.join("、", a.getFocus());
    }

    private String briefOf(RequirementAnalysis a) {
        return a.getBrief() == null || a.getBrief().isBlank() ? "围绕你的偏好做个性化挑选" : a.getBrief();
    }

    /** 评分权重调整说明（明示评分体系的工作） */
    private String weightNote(RequirementAnalysis a, Map<String, Double> w) {
        List<String> notes = new ArrayList<>();
        if (AhpWeightCalculator.dominantNeed(a.getNeeds(), AhpWeightCalculator.FOOD)) {
            notes.add("美食需求突出，美食权重提升至 " + pct(w.get(AhpWeightCalculator.FOOD)));
        }
        if (AhpWeightCalculator.dominantNeed(a.getNeeds(), AhpWeightCalculator.PATH)) {
            notes.add("行程省时优先，路径权重提升至 " + pct(w.get(AhpWeightCalculator.PATH)));
        }
        if (AhpWeightCalculator.dominantNeed(a.getNeeds(), AhpWeightCalculator.COST)) {
            notes.add("预算敏感，成本权重提升至 " + pct(w.get(AhpWeightCalculator.COST)));
        }
        if (AhpWeightCalculator.dominantNeed(a.getNeeds(), AhpWeightCalculator.SIGHTSEEING)) {
            notes.add("游玩体验优先，旅游需求权重提升至 " + pct(w.get(AhpWeightCalculator.SIGHTSEEING)));
        }
        if (notes.isEmpty()) {
            return "评分权重按默认优先级（路径优>成本低>旅游需求>美食需求），推荐按综合评分降序。";
        }
        return "评分权重已按你的偏好调整：" + String.join("；", notes) + "。推荐按综合评分降序。";
    }

    private String pct(Double v) {
        return v == null ? "-" : Math.round(v * 100) + "%";
    }

    private String trim50(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 50 ? s : s.substring(0, 50) + "…";
    }

    private <T> T recordAgent(TraceContext ctx, String agent, long startNs, Result<T> result) {
        ctx.add(AgentTrace.success(agent, (System.nanoTime() - startNs) / 1_000_000, result.tokenUsage()));
        return result.content();
    }

    private static int[] tokenCounts(TokenUsage u) {
        return u == null ? new int[]{0, 0}
                : new int[]{u.inputTokenCount() == null ? 0 : u.inputTokenCount(),
                            u.outputTokenCount() == null ? 0 : u.outputTokenCount()};
    }

    /** 按优先级取第一个缺失字段的问题；无缺失返回 null（问询完成） */
    private Question nextQuestion(TravelState state) {
        for (String field : FIELD_PRIORITY) {
            // 用户明确不需要美食/酒店/景点：对应字段不再询问（跳过标记在任意阶段均生效）
            if (Boolean.TRUE.equals(state.getNoFoodNeeded()) && "foodTaste".equals(field)) {
                continue;
            }
            if (Boolean.TRUE.equals(state.getNoHotelNeeded()) && "hotelStyle".equals(field)) {
                continue;
            }
            if (Boolean.TRUE.equals(state.getNoAttractionNeeded()) && "attractionType".equals(field)) {
                continue;
            }
            if (state.getPreference().isMissing(field)) {
                return QUESTION_TEMPLATES.get(field);
            }
        }
        return null;
    }

    /** 问询完成时给所有未回答字段填默认值（明示「已按默认处理」） */
    private void fillDefaults(TravelState state) {
        TravelPreference p = state.getPreference();
        if (p.getDays() == null) {
            p.setDays(DefaultsResolver.defaultDays());
            p.markDefaulted("days");
        }
        if (p.getPeopleCount() == null) {
            p.setPeopleCount(DefaultsResolver.defaultPeople());
            p.markDefaulted("peopleCount");
        }
        if (p.getTotalBudget() == null) {
            Destination d = destinationMapper.selectById(state.getDestinationId());
            p.setTotalBudget(DefaultsResolver.defaultBudget(
                    d == null ? null : d.getDailyBudgetMin(),
                    d == null ? null : d.getDailyBudgetMax(),
                    p.getDays(), p.getPeopleCount()));
            p.markDefaulted("totalBudget");
        }
        if (p.getAttractionType() == null) {
            p.setAttractionType(DefaultsResolver.defaultAttractionType());
            p.markDefaulted("attractionType");
        }
        if (p.getFoodTaste() == null) {
            p.setFoodTaste(DefaultsResolver.defaultFoodTaste());
            p.markDefaulted("foodTaste");
        }
        if (p.getEnergyLevel() == null) {
            p.setEnergyLevel(DefaultsResolver.defaultEnergyLevel());
            p.markDefaulted("energyLevel");
        }
        if (p.getHotelStyle() == null) {
            p.setHotelStyle(DefaultsResolver.defaultHotelStyle());
            p.markDefaulted("hotelStyle");
        }
        if (p.getSpecialRequests() == null) {
            p.setSpecialRequests("");
            p.markDefaulted("specialRequests");
        }
    }

    /** 需求分析一致性护栏：模型易把 citywalk 误判为低强度——体力与关注点矛盾时按已确认体力校正，
     *  避免矛盾文案进入 extraRequest 影响各环节 Agent */
    private void reconcileAnalysisWithEnergy(TravelState state, RequirementAnalysis a) {
        TravelPreference p = state.getPreference();
        if (a == null || p == null || p.getEnergyLevel() == null) {
            return;
        }
        List<String> bad = "偏弱".equals(p.getEnergyLevel())
                ? List.of("紧凑", "高强度", "爬山", "强度大")
                : List.of("低体力", "体力消耗", "轻松", "省力", "不累", "减少步行", "低强度", "强度低");
        List<String> focus = a.getFocus() == null ? new ArrayList<>() : new ArrayList<>(a.getFocus());
        if (focus.removeIf(f -> f != null && bad.stream().anyMatch(f::contains))) {
            if (focus.isEmpty()) {
                focus.add("偏弱".equals(p.getEnergyLevel()) ? "轻松舒适" : "紧凑充实");
            }
            a.setFocus(focus);
            a.setBrief("我将围绕" + String.join("、", focus) + "为你定制"
                    + ("偏弱".equals(p.getEnergyLevel()) ? "轻松舒适" : "紧凑充实") + "的行程。");
            log.warn("[TravelAgent][stage=PREFERENCE][sessionId={}] 需求分析出现与体力「{}」矛盾的关注点，已自动校正",
                    state.getSessionId(), p.getEnergyLevel());
        }
    }

    /** 问询完成后的偏好摘要文案 */
    private String summary(TravelState state) {
        TravelPreference p = state.getPreference();
        String attraction = Boolean.TRUE.equals(state.getNoAttractionNeeded()) ? "不需要" : p.getAttractionType();
        String food = Boolean.TRUE.equals(state.getNoFoodNeeded()) ? "不需要" : p.getFoodTaste();
        String hotel = Boolean.TRUE.equals(state.getNoHotelNeeded()) ? "不需要" : p.getHotelStyle();
        return String.format("已了解您的需求：%s，%d天，预算约 %s 元，%d人，景点偏好：%s，口味：%s，体力：%s，酒店：%s%s",
                state.getDestinationName(), p.getDays(), p.getTotalBudget().toPlainString(), p.getPeopleCount(),
                attraction, food, p.getEnergyLevel(), hotel,
                p.getSpecialRequests() == null || p.getSpecialRequests().isBlank()
                        ? "" : "，特殊需求：" + p.getSpecialRequests());
    }

    private ChatStepResult buildResult(TravelState state, String message, Question question) {
        ChatStepResult r = new ChatStepResult();
        r.setSessionId(state.getSessionId());
        r.setStage(state.getStage());
        r.setMessage(message);
        r.setQuestion(question);
        r.setPreference(state.getPreference());
        r.setConflict(state.getConflict());
        r.setAttractionCandidates(state.getAttractionCandidates());
        r.setFoodCandidates(state.getFoodCandidates());
        r.setHotelCandidates(state.getHotelCandidates());
        r.setWebFoodCandidates(state.getWebFoodCandidates());
        r.setSelectedAttractionIds(state.getSelectedAttractionIds());
        r.setSelectedFoodIds(state.getSelectedFoodIds());
        r.setSelectedHotelIds(state.getSelectedHotelIds());
        r.setItineraryId(state.getItineraryId());
        r.setSessionRevision(state.getSessionRevision());
        r.setItineraryText(state.getItineraryText());
        r.setPlan(state.getPlan());
        r.setCandidateAdvice(state.getCandidateAdvice());
        r.setPendingFatigueConfirm(state.getPendingFatigueConfirm());
        r.setPendingFatigueScore(state.getPendingFatigueScore());
        r.setPendingBudgetConfirm(state.getPendingBudgetConfirm());
        r.setPendingBudgetOver(state.getPendingBudgetOver());
        return r;
    }

    private static Question q(String field, String text, List<String> options) {
        Question question = new Question();
        question.setField(field);
        question.setText(text);
        question.setOptions(options);
        return question;
    }

}
