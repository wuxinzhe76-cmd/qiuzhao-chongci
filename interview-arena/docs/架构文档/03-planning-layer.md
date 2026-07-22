# 规划与推理层 - 架构设计文档

## 一、这一层解决什么问题

Agent 在运行时需要回答两个问题:**单步怎么想**(推理)和**多步怎么组织**(规划)。

1. **推理**:LLM 如何在单步中思考--评估用户回答、决定追问/换题/结束、选择工具;
2. **规划**:多步如何组织--确定性步骤代码控制 + 非确定性决策委托 LLM;
3. **目标追踪**:防止 LLM 在多轮对话中忘记原始目标(漂移成知识讲解器);
4. **错误分类**:决定重试/修正/重规划策略,避免无脑重试浪费资源。

面试场景流程相对确定(start -> pick -> evaluate -> route),不需要复杂 DAG/任务分解,但需要目标漂移防护、循环检测、错误分类和恢复策略。

**核心设计理念**:不用小 LLM 做决策(成本高、延迟大、不可控),而是用 Prompt 软约束(可用动作注入 Prompt)+ 代码硬约束(代码验证动作合法性)的组合,让主 LLM 在受控范围内决策。

## 二、实现了哪些内容

### 主线

| 文件路径 | 职责 |
|---------|------|
| `planning/PlanningService.java` | 规划主线,提供可用动作 + 验证动作 + 恢复 |
| `planning/model/PlanningAction.java` | 7 种动作枚举(开始/下一题/追问/降难度/提难度/切换/结束) |
| `planning/model/PlanningDecision.java` | 决策结果(动作 + 参数 + 理由) |

### 硬约束与软约束

| 文件路径 | 职责 |
|---------|------|
| `planning/harness/PlanningConstraints.java` | 硬约束(代码验证)+ 软约束(Prompt 注入) |
| `planning/harness/PlanningRecovery.java` | 恢复策略(题库空/追问超限/非法动作) |

### 防护组件

| 文件路径 | 职责 |
|---------|------|
| `planning/harness/GoalDriftDetector.java` | 目标漂移检测(正则,零成本) |
| `planning/harness/LoopDetector.java` | 循环检测(连续相同操作/最大轮次/Ping-Pong) |
| `planning/harness/StructuredErrorHandler.java` | 错误三分类(TRANSIENT/SEMANTIC/STRUCTURAL) |

### 应用层

| 文件路径 | 职责 |
|---------|------|
| `planning/application/LearningPathService.java` | 学习路径规划(面试后置,基于薄弱点生成) |

## 三、架构设计

### 软约束 + 硬约束双层控制流

```
编排层调用 PlanningService.getAvailableActions(state)
              ↓
   生成当前可用动作清单(软约束,注入 Prompt)
   例如:追问次数已达上限 -> 动作清单不含 FOLLOW_UP
              ↓
        LLM 在约束范围内决策
        输出 PlanningDecision(action + 参数)
              ↓
   PlanningService.validateAction(decision, state)
   代码验证动作合法性(硬约束)
              ↓
      合法 -> 执行
      非法 -> PlanningRecovery 恢复
```

### 与其他层的交互

- **上游**:编排层(InterviewOrchestrator)在 ReAct 决策步调 PlanningService;
- **下游**:决策结果交由编排层执行(ReActExecutor 执行工具,不是规划层执行);
- **共享**:GoalDriftDetector/LoopDetector 被编排层调用做防护;AgentStateStore 提供当前状态。

### 关键边界

**ReActExecutor 移到编排层**(执行不是规划)。规划层只决策"做什么",编排层负责"怎么做"--调工具、组装 Prompt、控制循环。这样规划层保持纯粹,不混入执行逻辑。

## 四、关键设计决策

### 1. 不用小 LLM 做决策(成本高)

**Why**:业界有方案用小 LLM(如 GPT-3.5)做规划决策,主 LLM 做执行。但在面试场景,每次决策都调小 LLM 增加一次网络往返(延迟 +500ms)+ 成本,且小 LLM 决策质量不稳定。改为用主 LLM 在 Prompt 软约束内决策,代码硬约束兜底,零额外成本、零额外延迟,且主 LLM 决策质量更高。

### 2. 软约束(可用动作注入 Prompt)+ 硬约束(代码验证)

**Why**:纯靠 Prompt 约束不可靠(模型可能"不听话"),纯靠代码约束不灵活(无法处理模糊场景)。软约束让模型知道"当前能做什么"(如追问已达上限就不给 FOLLOW_UP 选项),硬约束兜底验证(模型若仍输出非法动作,代码拦截并恢复)。两层叠加,既灵活又可控。

### 3. 7 种动作(开始/下一题/追问/降难度/提难度/切换/结束)

**Why**:面试场景的动作空间有限,不需要通用 Agent 的开放式动作。7 种动作覆盖完整面试流程:START(开始)→ 题目循环(PICK/NEXT/FOLLOW_UP/LOWER/RAISE/SWITCH)→ END(结束)。每种动作有明确的前置条件和效果,模型容易学习,代码容易验证。降难度/提难度是面试特色,根据用户表现动态调整。

### 4. 恢复策略(题库空/追问超限/非法动作)

**Why**:LLM 输出不可控,必须预设恢复策略。三种常见异常:
- **题库空**:pickQuestion 返回空 → 自动降难度或切换知识点;
- **追问超限**:FOLLOW_UP 超过 3 次 → 强制 NEXT_QUESTION;
- **非法动作**:模型输出不在可用清单内 → 默认安全的 NEXT_QUESTION。

恢复策略保证 Agent 不会因异常卡死,始终能继续推进。

## 五、面试讲点

- 规划层只决策不执行(执行归编排层),职责单一;
- 软约束 + 硬约束双层控制:Prompt 注入可用动作(软)+ 代码验证合法性(硬),既灵活又可控;
- 不用小 LLM 做决策:省成本、省延迟,主 LLM 在约束内决策质量更高;
- 7 种动作覆盖完整面试流程:开始/下一题/追问/降难度/提难度/切换/结束;
- 恢复策略三件套:题库空降难度、追问超限换题、非法动作默认安全;
- 目标漂移检测:正则识别用户"提问而非回答"(零成本 <1ms),后续可升级向量相似度;
- 错误三分类:TRANSIENT 重试、SEMANTIC 修正参数、STRUCTURAL 重规划;
- 循环检测:连续相同操作/Ping-Pong 检测,防 LLM 陷入死循环。
