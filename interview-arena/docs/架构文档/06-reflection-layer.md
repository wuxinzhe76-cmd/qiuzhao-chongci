# 反思与自修正层 - 架构设计文档

## 一、这一层解决什么问题

LLM 输出不可靠,会犯三类错误:

1. **结构错误**:JSON 无法解析、缺字段、类型错误--LLM 没按 Schema 输出;
2. **行为错误**:无 action 且无 final_answer、调用白名单外工具、重复调用相同工具相同参数;
3. **语义错误**:输出内容有逻辑问题、信息不足、推理有误。

如果不校验直接用,会导致 Agent 崩溃(结构错误)、卡死(行为错误)、答非所问(语义错误)。反思层是 LLM 输出的"质检站":校验输出 -> 发现问题 -> 修复重试或纠正提示 -> 限制轮次防爆。

当前项目反思机制**分散在两处**:`LlmInvoker` 的修复重试(结构化输出校验失败)和 `ReActExecutor` 的纠正提示(无 action/白名单外/重复调用)。本层将分散的反思能力收敛到 `reflection/` 包,统一入口、统一轮次限制。

## 二、实现了哪些内容

### 统一入口

| 文件路径 | 职责 |
|---------|------|
| `reflection/ReflectionService.java` | 反思统一入口(整合校验+修复+纠正) |

### 校验与修复

| 文件路径 | 职责 |
|---------|------|
| `reflection/harness/OutputValidator.java` | 3 层校验(JSON/Bean Validation/业务语义) |
| `reflection/harness/RepairRetryHandler.java` | 修复重试(带修复提示重调 LLM,最多 1 次) |
| `reflection/harness/CorrectionPromptBuilder.java` | 4 种纠正提示构建(无 action/白名单外/重复调用/空结果) |
| `reflection/harness/ReflectionLimitPolicy.java` | 轮次限制(修复 1 次/纠正 2 步/总反思 3 轮) |

### 数据模型

| 文件路径 | 职责 |
|---------|------|
| `reflection/model/ReflectionResult.java` | 反思结果 |
| `reflection/model/ErrorCorrection.java` | 纠错四类枚举(参数错误/工具选错/信息不足/逻辑错误) |

## 三、架构设计

### 两条反思链路

```
─── 链路 1:LLM 输出修复(同步) ───

LLM 输出
   ↓
OutputValidator 3 层校验
   ├── 第 1 层:JSON 解析(能否解析成对象)
   ├── 第 2 层:Bean Validation(字段非空/类型正确)
   └── 第 3 层:业务语义(action 在白名单内/参数合法)
   ↓
失败 -> RepairRetryHandler
   ├── ErrorClassifier 分类错误
   ├── 生成修复指令("你输出的 JSON 缺少 action 字段,请补充")
   └── 带修复指令重调 LLM(最多 1 次)
   ↓
仍失败 -> 走降级链


─── 链路 2:ReAct 循环内纠正(异步回灌) ───

ReActExecutor 检测模型输出异常
   ↓
CorrectionPromptBuilder 构建纠正提示
   ├── 无 action 且无 final_answer
   │   -> "你的输出既没有 action 也没有 final_answer,请二选一"
   ├── 白名单外工具
   │   -> "工具 'xxx' 不存在,可用工具见系统提示词"
   ├── 重复调用相同工具相同参数
   │   -> "你重复了相同调用,请基于已有结果给出 final_answer"
   └── 空结果
       -> "工具返回空,请换一个工具或调整参数"
   ↓
纠正提示作为 Observation 回灌 scratchpad(占一步)
   ↓
模型下一轮纠正
```

### 与其他层的交互

- **上游**:LlmInvoker 调 LLM 后调 ReflectionService 校验;
- **ReAct 内**:ReActExecutor 检测异常调 CorrectionPromptBuilder;
- **下游**:修复重试调 LlmInvoker 重调 LLM;纠正提示回灌 ReAct scratchpad;
- **共享**:复用 planning/harness/StructuredErrorHandler 做错误分类。

### 反思轮次限制

```
修复重试:MAX_REPAIR_RETRIES = 1(防 LLM 输出一直错)
纠正步数:ReAct MAX_STEPS = 5 内最多 2 步纠正
总反思:最多 3 轮,超过走降级链,不无限重试
```

## 四、关键设计决策

### 1. 反思是 LLM 输出的质检站

**Why**:LLM 输出本质不可靠(概率模型),直接使用会导致下游崩溃或行为异常。反思层作为"质检站",在 LLM 输出和实际使用之间插入校验+修复+纠正环节。这不是可选优化,而是生产级 Agent 的必需品--没有反思层的 Agent 无法上生产。反思层让 LLM 输出从"尽力而为"变成"经过验证"。

### 2. 修复重试最多 1 次(防死循环)

**Why**:LLM 输出错误时,带修复提示重调可能纠正,但也可能继续错。如果无限重试,会陷入死循环(成本爆炸)。限制最多 1 次:给了模型一次纠正机会,若仍失败说明问题不是提示能解决的(可能是模型能力不足或任务太难),走降级链更安全。1 次是成本与成功率的平衡点--第 2 次修复成功率边际收益骤降。

### 3. 纠正提示占一步(模型下一轮纠正)

**Why**:ReAct 循环内,模型输出异常时,有两种处理:直接报错终止(太激进)或纠正提示回灌(给模型机会)。选后者:把纠正提示作为 Observation 回灌 scratchpad,占 ReAct 的 1 步。模型下一轮看到"你刚才输出有问题,原因是 X,请纠正",有机会自主纠正。这比直接终止更优雅,且 ReAct MAX_STEPS=5 限制了纠正步数,不会无限消耗。

### 4. 轮次限制(修复 1 次/纠正 2 步/总反思 3 轮)

**Why**:反思本身也可能失败(模型就是纠正不了),必须有轮次限制防爆。三重限制:
- **修复重试 1 次**:单次 LLM 调用的修复上限;
- **纠正 2 步**:ReAct 循环内纠正步数上限(5 步里最多 2 步纠正,剩 3 步做事);
- **总反思 3 轮**:跨多个反思环节的总上限,超过走降级链。

三重限制保证反思不会吞噬主流程,Agent 始终能推进。

## 五、面试讲点

- 反思层是 LLM 输出的质检站:校验 -> 修复 -> 纠正,不是可选优化是生产必需品;
- 3 层校验:JSON 解析(结构)-> Bean Validation(字段)-> 业务语义(action 白名单);
- 修复重试:带修复提示重调 LLM,最多 1 次,防死循环,1 次是成本与成功率平衡点;
- 4 种纠正提示:无 action/白名单外/重复调用/空结果,占 ReAct 1 步,模型下一轮自主纠正;
- 轮次限制三重:修复 1 次/纠正 2 步/总反思 3 轮,保证反思不吞噬主流程;
- 两条链路:同步修复(LlmInvoker 后校验)+ 异步纠正(ReAct 内回灌);
- 纠错四类:参数错误(修正重试)/工具选错(换工具)/信息不足(补检索)/逻辑错误(重推理);
- 反思失败走降级链,不无限重试,Agent 始终能推进。
