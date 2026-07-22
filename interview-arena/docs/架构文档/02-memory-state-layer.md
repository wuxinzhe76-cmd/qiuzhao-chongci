# 记忆与状态层 - 架构设计文档

## 一、这一层解决什么问题

LLM 本身是无状态的,但 Agent 必须维护三类信息才能正常工作:

1. **记忆(Memory)**:过去发生过什么——历史对话、面试记录、用户画像、薄弱点;
2. **状态(State)**:当前任务进行到哪里——面试阶段、题号、追问次数、工具调用次数;
3. **上下文(Context)**:这一轮给模型看什么——从 Memory + State 选择组装出的临时视图。

三者必须分离的原因:

- **State 是流程控制的权威依据**,不能依赖模型摘要(模型可能"忘记"当前是第几题,但代码必须知道);
- **Context 是临时视图**,不持久化,每次 LLM 调用前重新组装;
- **Memory 提供候选**,ContextAssembler 决定最终注入什么,不是搜到什么就全塞。

当前项目原本把这三者混在 `WorkingMemoryService` 里(Redis 同时存消息+题目+轮次),导致状态污染记忆、记忆污染上下文。本层将其拆分,让职责清晰。

## 二、实现了哪些内容

### Memory(管过去)

| 文件路径 | 职责 |
|---------|------|
| `memory/MemoryFacade.java` | 用例级方法门面(loadForInterview/recordTurn/consolidateInterview) |
| `memory/episodic/InterviewHistoryService.java` | 情景记忆(MySQL,面试历史事件) |
| `memory/semantic/UserProfileService.java` | 语义记忆(MySQL+Milvus,用户知识画像) |
| `memory/semantic/ProfileAnalyzer.java` | 画像分析(从 profile/ 合入) |
| `memory/working/WorkingMemoryService.java` | 工作记忆(Redis,当前任务语义信息) |
| `memory/consolidation/ProfileUpdateService.java` | 记忆整合(面试结束触发画像更新) |
| `memory/retrieval/MultiStrategyMemoryRetriever.java` | 四路并行检索 + RRF 融合 |
| `memory/retrieval/KnowledgeRetriever.java` | 知识检索接口 |

### State(管当前进度)

| 文件路径 | 职责 |
|---------|------|
| `runtime/state/AgentStateStore.java` | 状态存储(Redis,含乐观锁 CAS) |
| `runtime/state/InterviewAgentState.java` | 状态 record(sessionId/stage/questionIndex/followUpCount/version) |
| `runtime/state/InterviewStage.java` | 阶段枚举 |

### Context(管这一轮给模型看什么)

| 文件路径 | 职责 |
|---------|------|
| `context/ContextAssembler.java` | Memory + State → AgentContext(简化版) |

## 三、架构设计

### 三层分离数据流

```
┌─────────────────────────────────────────────┐
│  Memory(过去)           State(当前)        │
│  ├─ InterviewHistory      ├─ stage          │
│  ├─ UserProfile           ├─ questionIndex   │
│  ├─ WorkingMemory         ├─ followUpCount   │
│  └─ MultiStrategyRetriever└─ usedQuestionIds │
│         ↓                          ↓         │
│         └──────────┬───────────────┘         │
│                    ↓                         │
│           ContextAssembler                   │
│      (固定保留 + 最近N轮 + Token截断)         │
│                    ↓                         │
│              AgentContext                    │
│         (systemPrompt + messages)            │
│                    ↓                         │
│                  LLM                         │
└─────────────────────────────────────────────┘
```

### MemoryFacade 用例级方法

```java
public interface MemoryFacade {
    MemorySnapshot loadForInterview(Long userId, Long sessionId, String currentTopic);
    void recordTurn(Long sessionId, InterviewTurn turn);
    void consolidateInterview(Long userId, InterviewSummary summary);
}
```

Orchestrator 不需要知道 Redis/Milvus/MySQL 怎么查,只调 MemoryFacade 三个用例级方法。

### AgentStateStore 独立链路

AgentStateStore 被 planning、memory、orchestration 三层共享,但独立于 Memory。状态是流程控制的权威事实源,保存时用 CAS(Compare-And-Swap)乐观锁,防止并发覆盖、阶段跳跃、旧版本覆盖新版本。

### 存储选型

| 存储 | 用途 |
|------|------|
| Redis | 当前会话消息 + WorkingMemory + AgentState |
| MySQL | 面试记录(Episodic) + 知识画像(Semantic) |
| Milvus | 历史面试语义检索 + 用户弱点检索 |

## 四、关键设计决策

### 1. Memory / State / Context 三分离

**Why**:三者生命周期不同(Memory 跨会话、State 单任务、Context 单次调用),职责不同(Memory 管过去、State 管进度、Context 管注入)。混在一起会导致状态污染记忆(题号被当成记忆)、记忆污染上下文(全量历史塞给模型撑爆 Token)。分离后每层职责单一,可独立演进。

### 2. AgentStateStore 独立于 Memory(状态是权威事实源)

**Why**:State 是流程控制的依据(第几题、追问几次),如果放在 Memory 里由模型摘要维护,模型可能"忘记"或"记错",导致流程失控。独立出 AgentStateStore,用代码 + 乐观锁维护,模型只读不写,保证状态权威性。被 planning/memory/orchestration 三层共享,避免状态散落多处不一致。

### 3. Kafka 替换 RabbitMQ(面试报告异步)

**Why**:面试结束后生成 PDF 报告耗时长(LLM 总结 + PDF 渲染 30s+),同步阻塞会导致 HTTP 超时。用 Kafka 异步消费,用户立即收到"报告生成中",后台消费生成后推送。Kafka 相比 RabbitMQ 吞吐更高、持久化更可靠、支持重放,适合报告这种可重试的异步任务。

### 4. 重命名见名知意(EpisodicMemoryService → InterviewHistoryService)

**Why**:原命名 EpisodicMemoryService 是学术术语,团队成员需要查资料才懂。重命名为 InterviewHistoryService 直接表达"面试历史服务",降低理解成本。同理 SemanticMemoryService → UserProfileService(用户画像服务),ProfileUpdateService 替代 MemoryConsolidationService。命名贴近业务,新人上手快。

## 五、面试讲点

- 三层记忆模型:Memory(过去)/ State(当前进度)/ Context(这一轮给模型看什么),三者必须分离;
- State 独立于 Memory:状态是流程控制权威源,代码 + 乐观锁维护,模型只读不写,被三层共享;
- 三类记忆:短期(对话消息 Redis)、工作(任务语义 Redis)、长期(情景 MySQL + 语义 MySQL/Milvus);
- 记忆整合:面试结束触发 ProfileUpdateService,从情景记忆抽取语义记忆(薄弱点/画像);
- 多策略检索:MultiStrategyMemoryRetriever 四路并行 + RRF 融合,提升相关性;
- ContextAssembler 简化版:固定保留 + 最近 N 轮 + Token 截断,后续迭代 6 步完整压缩;
- Kafka 替换 RabbitMQ:面试报告异步生成,吞吐高、可重放。
