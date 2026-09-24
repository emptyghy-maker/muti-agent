# 多模型路由与评测隔离

## 当前模型分工

模型顺序依据 2026-09-22 华北2百炼免费额度页面。生产配置只在额度耗尽、限流、模型未开通或服务不可用时切换；模型成功返回但内容质量不合格时不切换，仍由既有校验与修复流程处理。

| 路由 | 主模型 | 备用模型 | 用途 |
|---|---|---|---|
| `fast` | `qwen3.7-flash-2026-07-15` | `qwen3.8-flash` | 偏好、需求、候选筛选、补丁 |
| `plan` | `qwen3.8-27b` | `qwen3.8-max-0902`、`qwen3.8-flash` | 初次行程规划、强修复 |
| `repair` | `qwen3.8-flash` | `qwen3.7-flash-2026-07-15`、`qwen3.8-27b` | 快速行程修复 |
| `search` | `qwen3.7-flash-2026-07-15` | `qwen3.8-flash` | 联网候选扩充，显式关闭思考 |

运行日志中的 `[LLM_ROUTE]` 会记录模型切换。用量记录通过 `LlmRouteContext` 写入实际命中的模型，而不是始终记录主模型。额度错误后的模型默认冷却 30 分钟，避免每个请求反复撞击已经耗尽的额度。

`plan` 路由通过 `QWEN_PLAN_REASONING_EFFORT=none` 显式关闭 Qwen3.8 默认的高强度思考，并用 `QWEN_PLAN_MAX_TOKENS=1024` 限制决策骨架输出。ItineraryAgent 只做分天、排序和餐次分配；Java 负责交通、酒店、休息点、时间、费用与发布校验。

## 生产运行

使用 `.env.example` 中的模型变量，并设置：

```text
LLM_ROUTING_MODE=FAILOVER
LLM_FAILOVER_ON_TIMEOUT=false
```

默认不因超时切换。超时请求可能已经产生用量，再请求另一个模型会增加成本和尾延迟。SDK 自动重试也已关闭，每个模型一次路由只发一个物理请求。

## Agent/Prompt 性能评测

评测必须固定带日期的模型版本，禁止使用生产备用链：

```text
LLM_ROUTING_MODE=FIXED
QWEN_EXP_MODEL_DEFAULT=qwen3.7-flash-2026-07-15
QWEN_EXP_MODEL_SQL=qwen3.8-27b
QWEN_EXP_REASONING_EFFORT=none
```

`PromptExperimentTest` 自己创建单模型客户端，不读取生产 fallback 列表。对比 Prompt 时，baseline 和 candidate 必须使用相同的 `QWEN_EXP_MODEL_*`、temperature、数据集、重复次数与工具快照；模型对比实验则保持 Prompt 和其他参数相同，只改变明确记录的模型版本。

真实实验必须显式传入 `-Dprompt.exp.live=true`。普通 `mvn test` 即使能读取 `QWEN_API_KEY` 也会跳过真实模型实验，防止误耗额度。无网络烟测使用 `-Dprompt.exp.stub=true`。

## 额度维护

免费额度是按模型分开的临时资源，并有到期时间。每周检查百炼免费额度页面：余额低于 20% 时把该模型移到备用链末尾；额度用尽后从链中移除；新增模型先完成 JSON 输出、延迟和行程硬约束小样本验证，再进入生产链。
