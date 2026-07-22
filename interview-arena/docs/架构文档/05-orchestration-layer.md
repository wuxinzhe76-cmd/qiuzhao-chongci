# 编排与调度层 - 架构设计文档

## 一、这一层解决什么问题

Orchestrator 是 Agent 的中枢神经,协调 Model + Tools + Memory 三者交互。**核心价值:把流程控制权从模型手里拿回到代码手里**。

当前项目有两个 Agent,编排模式不一致:
- **面试 Agent**:有专门 `InterviewOrchestrator`(10 步编排);
- **询问助手**:**没有 Orchestrator**,编排逻辑塞在 `QuickAskService` 里,职责混乱。

需要统一 Orchestrator 抽象,让两个 Agent 编排模式对齐。核心原则:**确定性步骤代码控制(记忆写入、轮次推进、护栏检查),非确定性决策委托 LLM(评估回答、追问/换题)**。模型不能自己决定终止,Orchestrator 用代码强制:到 maxRounds 就结束,到 maxQuestionRounds 就换题。

## 二、实现了哪些内容

### 统一接口

| 文件路径 | 职责 |
|---------|------|
| `orchestration/api/AgentOrchestrator.java` | 统一 Orchestrator 接口 |
| `orchestration/api/OrchestratorRequest.java` | 编排请求封装 |

### 面试官 Agent 编排

| 文件路径 | 职责 |
|---------|------|
| `orchestration/interviewer/InterviewOrchestrator.java` | 面试 10 步编排主线 |
| `orchestration/interviewer/InterviewService.java` | 薄层接口(参数校验+鉴权+委托) |
| `orchestration/interviewer/InterviewServiceImpl.java` | 薄层实现 |
| `orchestration/interviewer/InterviewLlmService.java` | 面试话术生成 |
| `orchestration/interviewer/PdfReportService.java` | PDF 报告生成 |
| `orchestration/interviewer/KafkaReportProducer.java` | Kafka 生产者(面试报告异步) |
| `orchestration/interviewer/KafkaReportConsumer.java` | Kafka 消费者 |
| `orchestration/interviewer/InterviewKafkaConfig.java` | Kafka 配置 |

### 询问助手 Agent 编排

| 文件路径 | 职责 |
|---------|------|
| `orchestration/ask/QuickAskService.java` | 询问助手编排(从 QuickAskService 升级) |
| `orchestration/ask/AgenticRagService.java` | Agentic RAG 编排 |

### ReAct 执行器(5 个文件)

| 文件路径 | 职责 |
|---------|------|
| `orchestration/react/ReActExecutor.java` | ReAct 循环主线(MAX_STEPS=5) |
| `orchestration/react/ReActRequest.java` | ReAct 请求 |
| `orchestration/react/ReActResult.java` | ReAct 结果(含 traces) |
| `orchestration/react/ReActStep.java` | 单步记录 |
| `orchestration/react/ReActTrace.java` | 轨迹(Think/Act/Observe) |

### 防护组件

| 文件路径 | 职责 |
|---------|------|
| `orchestration/harness/ThreeLayerController.java` | 三层控制(面试专用兜底) |

## 三、架构设计

### 外层 Workflow + 内层 ReAct 混合模式

```
┌─────────────────────────────────────────────────┐
│  外层 Workflow(HTTP 请求-响应驱动)              │
│  ┌─────────────────────────────────────────┐    │
│  │  10 步编排(9 步代码控制 + 1 步模型决策) │    │
│  │                                         │    │
│  │  1.输入清洗    ← 代码                   │    │
│  │  2.漂移检测    ← 代码                   │    │
│  │  3.记忆写入    ← 代码                   │    │
│  │  4.轮次推进    ← 代码                   │    │
│  │  5.循环检测    ← 代码                   │    │
│  │  ┌─────────────────────────────┐        │    │
│  │  │ 6.ReAct 决策 ← 模型决策      │        │    │
│  │  │   ┌─────────────────────┐   │        │    │
│  │  │   │ 内层 ReAct(MAX=5)  │   │        │    │
│  │  │   │ Think->Act->Observe│   │        │    │
│  │  │   └─────────────────────┘   │        │    │
│  │  └─────────────────────────────┘        │    │
│  │  7.答案泄露检测 ← 代码                 │    │
│  │  8.输出监控    ← 代码                   │    │
│  │  9.状态对齐    ← 代码                   │    │
│  │  10.指令路由   ← 代码路由               │    │
│  └─────────────────────────────────────────┘    │
└─────────────────────────────────────────────────┘
```

### PlanningService 约束注入

```
编排层调 PlanningService.getAvailableActions(state)
   ↓
生成当前可用动作清单(软约束,注入 Prompt)
   ↓
LLM 在约束范围内决策 -> PlanningDecision
   ↓
PlanningService.validateAction 验证(硬约束)
   ↓
合法 -> ReActExecutor 执行
非法 -> PlanningRecovery 恢复
```

### 调用关系

```
Controller
   ↓
Service (薄层:参数校验+鉴权+委托)
   ↓
Orchestrator (编排层)
   ├── 确定性步骤(代码控制)
   │   ├── InputSanitizer          (guardrail/input/)
   │   ├── GoalTracker             (planning/harness/)
   │   ├── MemoryFacade            (memory/)
   │   ├── AgentStateStore         (runtime/state/)
   │   ├── LoopDetector            (planning/harness/)
   │   ├── OutputMonitor           (guardrail/output/)
   │   └── ThreeLayerController    (orchestration/harness/)
   │
   ├── 非确定性决策(模型决策)
   │   └── ReActExecutor           (orchestration/react/)
   │       ├── LlmInvoker          (llm/core/)
   │       └── ToolExecutor        (tool/api/)
   │
   └── 副作用(代码控制)
       ├── KafkaReportProducer     (MQ)
       └── MemoryFacade.consolidateInterview
```

## 四、关键设计决策

### 1. Workflow + ReAct 混合(不是纯 ReAct)

**Why**:纯 ReAct 模式让 LLM 自主决定每一步,在面试场景不适用--记忆写入、轮次推进、答案泄露检测等必须由代码控制,不能让模型"忘记"或"跳过"。混合模式:外层 Workflow 用代码控制确定性步骤(9 步),内层 ReAct 只在需要模型决策时启动(1 步)。这样既保留 ReAct 的灵活性(模型决定追问/换题/结束),又保证流程可控(代码强制轮次/状态/护栏)。

### 2. PlanningService 替换 ThreeLayerController(更灵活)

**Why**:原 ThreeLayerController 是硬编码的三层兜底(单题 3 轮换题/总 10 轮结束/指令路由),逻辑固定不可扩展。PlanningService 引入软约束(可用动作注入 Prompt)+ 硬约束(代码验证),模型在约束内自主决策,更灵活。ThreeLayerController 保留作为最后兜底,但日常决策走 PlanningService。

### 3. AgentStateStore 替换 MemoryFacade 状态操作(状态独立)

**Why**:原状态操作(题号/轮次)混在 MemoryFacade 里,导致记忆层承担状态职责,职责不清。拆出 AgentStateStore 独立管理状态,被 planning/memory/orchestration 三层共享。状态是流程控制权威源,用代码 + 乐观锁维护,模型只读不写,保证一致性。MemoryFacade 回归纯记忆职责。

### 4. Prompt 注入可用动作(软约束)

**Why**:与其用代码硬规定模型能做什么,不如把"当前可用动作"动态注入 Prompt,让模型在可见约束内自主决策。例如追问已达上限,动作清单不含 FOLLOW_UP,模型自然不会选。这比纯硬约束更友好,模型理解为什么不能做某事,减少"违规"概率。代码硬约束仍作为兜底,双保险。

### 5. Kafka 替换 RabbitMQ

**Why**:面试报告异步生成场景,Kafka 相比 RabbitMQ 优势:吞吐更高(支持高并发面试)、持久化更可靠(日志追加,不易丢)、支持重放(消费失败可重新消费)、生态更成熟(对接 ClickHouse/ELK 容易)。RabbitMQ 适合低延迟点对点,Kafka 适合可靠异步流,报告生成是后者场景。

## 五、面试讲点

- Workflow + ReAct 混合:外层代码控制确定性步骤,内层 ReAct 处理模型决策,不是纯 ReAct;
- 10 步编排只有 1 步模型决策(第 6 步 ReAct),其余 9 步代码控制,流程可控;
- PlanningService 软约束 + 硬约束:可用动作注入 Prompt(软)+ 代码验证合法性(硬),ThreeLayerController 兜底;
- AgentStateStore 独立:状态是权威源,代码 + 乐观锁维护,被三层共享,模型只读不写;
- 模型不能自己决定终止:代码强制 maxRounds 结束、maxQuestionRounds 换题;
- 终止条件四种:maxSteps(ReAct 5 步)、maxRounds(单题 3 轮)、maxTotalRounds(总 10 轮)、globalTimeout(60s PDF);
- 统一 AgentOrchestrator 接口:面试官和询问助手两个 Agent 编排模式对齐;
- Kafka 替换 RabbitMQ:面试报告异步生成,吞吐高、可重放、对接 ClickHouse。
