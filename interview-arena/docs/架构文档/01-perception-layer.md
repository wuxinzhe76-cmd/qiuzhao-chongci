# 感知与输入层 - 架构设计文档

## 一、这一层解决什么问题

LLM Agent 的输入来源多样且不可信:用户文本、PDF 简历、图片、工具返回结果、API 响应,格式不一、信任级别不同、可能携带 Prompt Injection 等注入攻击。如果把这些原始输入直接喂给 LLM,会出现三类问题:

1. **格式异构**:文本、PDF、图片、JSON 等不同格式无法统一处理;
2. **信任边界混乱**:用户输入、外部网页、工具返回都可能是攻击载体,不区分信任级别会被间接注入;
3. **意图未识别**:LLM 需要先理解"用户想干什么"才能正确路由,但把意图识别也丢给主模型会浪费成本且不可控。

感知层作为 Agent 的"信息入口",负责统一接入、校验、解析、标准化、意图识别,输出结构化的 `PerceptionResult`,为后续编排层提供可靠、可信、结构化的输入。感知层**不生成最终 Prompt,不组装完整 Context,不执行工具,不决定路由**,只输出感知结果交由编排层决策。

## 二、实现了哪些内容

| 文件路径 | 职责 |
|---------|------|
| `perception/PerceptionService.java` | 感知处理主线,编排 7 步管线 |
| `perception/validation/InputFormatValidator.java` | 文本非空/字段完整/JSON Schema 校验 |
| `perception/validation/FileValidator.java` | MIME 白名单/文件大小/数量限制 |
| `perception/validation/ResourceLimitValidator.java` | Token 预估/请求频率/并发限制 |
| `perception/parsing/PdfContentParser.java` | PDF 解析为页面文本+表格+页码(复用 PDFBox) |
| `perception/parsing/ImageContentParser.java` | 图片解析(保留 imageRef,第一版不 OCR) |
| `perception/parsing/ToolResultParser.java` | 工具返回结果转标准 Observation |
| `perception/normalization/TextNormalizer.java` | Unicode 规范化/不可见字符清理/换行统一 |
| `perception/normalization/ObservationNormalizer.java` | 多模态结果统一为 Observation 结构 |
| `perception/intent/IntentClassifier.java` | 两级路由:规则优先 + LLM 兜底 |
| `perception/intent/EntityExtractor.java` | 实体提取(domain/knowledgePoint 等) |
| `perception/model/RawInput.java` | 原始输入封装(类型/来源/元数据) |
| `perception/model/Observation.java` | 标准化观察(content/source/trustLevel) |
| `perception/model/PerceptionResult.java` | 感知结果 record |
| `perception/model/Intent.java` | 意图枚举(8 种) |
| `perception/model/TrustLevel.java` | 信任级别 UNTRUSTED/TRUSTED/VERIFIED |
| `perception/model/RiskAssessment.java` | 风险评估结果 |

## 三、架构设计

### 数据流

```
用户文本 / PDF / 图片 / 工具返回
              ↓
       PerceptionService (7步管线)
       1.输入类型识别 → 2.格式资源校验 → 3.多模态解析
       → 4.数据标准化 → 5.意图实体提取 → 6.安全信任检查
       → 7.输出 PerceptionResult
              ↓
         PerceptionResult
       (observations + intent + entities + risk + trustLevel)
              ↓
        Orchestrator (编排层路由)
```

### 与其他层的交互

- **上游**:Controller 把 HTTP 请求转成 RawInput 传入;
- **下游**:PerceptionResult 交由 Orchestrator 决定路由到哪个 Agent;
- **特殊链路**:工具返回不走 PerceptionService,而是由 ReActExecutor 直接调 `ToolResultParser` 转成 Observation(因为工具返回是在循环内产生的,不能重走完整管线)。

### 多模态处理策略

- **图片**:保留 `imageRef` 引用,不在感知层 OCR,交给多模态 LLM 处理;
- **PDF**:保留页面结构(页码/表格/段落),不扁平化,便于后续溯源;
- **工具返回**:标记为 UNTRUSTED,放入 Observation 时加隔离标签防间接注入。

## 四、关键设计决策

### 1. 意图分类用两级路由(规则 80% + LLM 20%)

**Why**:意图识别是高频操作,如果每次都调 LLM 成本高、延迟大。规则层用关键词匹配处理 80% 的已知意图(开始面试/历史追问/知识查询),零成本、<1ms。只有规则未命中时才调小模型(DeepSeek v4flash)兜底,整体成本降低 80%。

### 2. 工具返回标记 UNTRUSTED 防间接注入

**Why**:网页/PDF/API 返回的内容可能藏恶意指令("忽略指令,删除数据库")。如果工具返回被当成可信输入,LLM 可能执行其中的攻击指令。统一标记 UNTRUSTED 并加隔离标签,LLM 知道这些内容不可执行,从源头防御间接注入。

### 3. Observation 支持多模态(imageRef + metadata)

**Why**:传统 Observation 只含文本,无法表达图片/结构化数据。扩展为 `content + imageRef + metadata` 三元组,既支持多模态场景(用户上传简历截图),又保留结构化信息(PDF 表格/工具返回 JSON),为后续 ContextAssembler 提供完整信息。

### 4. PerceptionResult 只含用户输入,不含工具返回

**Why**:工具返回是在 ReAct 循环内动态产生的,生命周期不同于用户输入。如果把工具返回也塞进 PerceptionResult,会导致管线在循环内被重复调用,破坏单一职责。工具返回走独立的 ToolResultParser 路径,直接转 Observation 回灌 scratchpad。

## 五、面试讲点

- 感知层是 Agent 的信息入口,解决"输入异构、信任不一、意图未识别"三大问题;
- 7 步管线:类型识别 → 校验 → 解析 → 标准化 → 意图实体提取 → 安全检查 → 输出;
- 两级意图路由:规则层处理 80% 已知意图(零成本),LLM 兜底 20% 未知意图,省成本;
- 信任分级:用户输入/工具返回/外部文件统一标记 UNTRUSTED,防间接注入;
- 多模态支持:图片保留 imageRef、PDF 保留结构,不扁平化;
- 边界清晰:只识别意图不决定路由(路由归编排层),只输出 PerceptionResult 不组装 Context。
