# Memory 模块面试题

> 基于项目 `interview-arena` 的 Memory 模块实现，覆盖短期/长期记忆分离、记忆整合、滑动窗口、遗忘策略等考点。

---

## 一、记忆架构设计

### Q1：你的记忆系统是怎么设计的？为什么分短期和长期？

**答**：

参考认知科学的三阶段记忆模型，分两层：

```
短期记忆（ShortTermMemory）= Redis，TTL 2h
├── 对话历史：滑动窗口最近 10 条（5 轮 user+assistant）
└── 工作聚焦：当前题目ID / 总轮次 / 追问轮次 / 已用题目集

长期记忆（LongTermMemory）= MySQL + Milvus，永久
├── 情景记忆（Episodic）：每次面试完整问答明细，30 天时间衰减
└── 语义记忆（Semantic）：用户知识画像 + 薄弱点，永久保留权重 x2
```

分短期和长期的原因：

1. **Token 控制**：LLM 上下文窗口有限，不能把所有历史对话塞进去。短期记忆只保留最近 10 条，控制 Token 消耗。
2. **成本控制**：Redis 是热数据（AI 实时读写），MySQL 是冷数据（持久化记录）。分层存储避免 Redis 无限膨胀。
3. **遗忘策略**：短期记忆面试结束即清理，长期记忆按时间衰减但薄弱点永久保留。模拟人类记忆的遗忘曲线。

### Q2：短期记忆为什么分"对话历史"和"工作聚焦"两类？

**答**：

两类数据的用途不同：

| 类别 | Redis Key | 类型 | 用途 |
|------|----------|------|------|
| 对话历史 | `interview:history:{sessionId}` | List | 喂给 LLM 做上下文推理（最近 10 条滑动窗口） |
| 当前题目 | `interview:question:{sessionId}` | String | 决定 LLM 评估哪个知识点 |
| 总轮次 | `interview:round:{sessionId}` | String | 三层控制（总轮次 >=10 强制结束） |
| 追问轮次 | `interview:questionRound:{sessionId}` | String | 三层控制（单题 >3 轮强制换题） |
| 已用题目 | `interview:used:{sessionId}` | Set | 防重复抽题 |

对话历史是**给 LLM 看的**（拼到 Prompt 里），工作聚焦是**给代码逻辑用的**（控制面试流程）。分开存是因为：
- 对话历史需要滑动窗口裁剪（LTRIM），但工作聚焦不能被裁剪
- 对话历史是文本（可能很长），工作聚焦是数字/ID（很短）

### Q3：长期记忆为什么分"情景记忆"和"语义记忆"？

**答**：

| 维度 | 情景记忆（Episodic） | 语义记忆（Semantic） |
|------|---------------------|---------------------|
| 存储 | MySQL `interview_record` 表 | MySQL `user_knowledge_profile` 表 + Milvus 向量库 |
| 存什么 | 每轮对话的完整内容（谁说了什么） | 提取后的知识点掌握度（HashMap: 知识点 -> 分数） |
| 检索方式 | 按时间、按 sessionId | 按 userId + 按向量相似度 |
| 衰减策略 | 30 天时间衰减 `max(0.3, 1 - days/30)` | 薄弱点永久保留，权重 x2 |
| 用途 | 回顾历史面试明细 | 记忆驱动出题（优先考薄弱点） |

类比人类记忆：
- 情景记忆 = "我记得上周面试时他问了 HashMap，我答得不好"（具体事件）
- 语义记忆 = "我的 HashMap 掌握度只有 40 分"（抽象知识）

两者关系：情景记忆是**原始数据**，语义记忆是**从原始数据中提取的规律**。面试结束时的记忆整合就是把情景记忆升级为语义记忆。

---

## 二、记忆整合（Consolidation）

### Q4：记忆整合是什么时候触发的？做了哪些事？

**答**：

面试结束时由 `MemoryManager.endSession(sessionId, userId)` 触发，三步：

```
1. consolidate(sessionId, userId)
   ├── 工作记忆 -> 情景记忆：Redis 对话历史已即时落库 MySQL（不需要额外操作）
   ├── 情景记忆 -> 语义记忆：调 KnowledgeProfileAnalyzer 分析面试记录
   │   └── LLM 提取 Map<知识点, 掌握度>，更新 user_knowledge_profile 表
   └── 语义记忆 -> Milvus：薄弱点向量化写入 Milvus（用于后续向量检索）

2. clearAll(sessionId)
   └── 清理 Redis 5 个 key（不等 TTL 2h 超时，主动释放空间）
```

**为什么不在每轮对话后整合？** 整合需要调 LLM 分析知识点（耗时 + 费钱），只在面试结束时做一次批量分析，而不是每轮都做。

### Q5：情景记忆的时间衰减公式是什么？为什么要衰减？

**答**：

```java
// EpisodicMemoryService.timeDecay()
public double timeDecay(LocalDateTime recordTime) {
    long days = Duration.between(recordTime, LocalDateTime.now()).toDays();
    return Math.max(0.3, 1.0 - days / 30.0);  // 30 天内线性衰减，最低 0.3
}
```

- 第 0 天：权重 1.0（刚面试完，最重要）
- 第 15 天：权重 0.5
- 第 30 天：权重 0.3（不再继续衰减）

衰减的原因：用户的知识水平会变化。30 天前的面试记录参考价值低（用户可能已经学会了），但不应完全遗忘（最低 0.3 权重）。

**薄弱点不衰减**：`importanceWeight()` 对 mastery < 60 的记录权重 x2，确保薄弱点长期被优先考察。

### Q6：记忆整合失败了怎么办？

**答**：

```java
public void endSession(Long sessionId, Long userId) {
    try {
        consolidate(sessionId, userId);
    } catch (Exception e) {
        log.warn("记忆整合失败（不影响主流程）: {}", e.getMessage());
    }
    clearAll(sessionId);  // 无论整合是否成功，都清理短期记忆
}
```

整合失败不影响主流程：
1. **对话记录不会丢**：每轮对话已在 `SaveRecordTool` 中即时落库 MySQL（情景记忆），不依赖整合。
2. **画像不更新**：本次面试的知识点分析失败，用户画像保持上一次的状态。下次面试时用旧画像出题。
3. **短期记忆必清**：即使整合失败，`clearAll()` 也会执行，释放 Redis 空间。

---

## 三、滑动窗口与上下文管理

### Q7：滑动窗口是怎么实现的？为什么是 10 条？

**答**：

```java
// WorkingMemoryService.pushHistory()
public void pushHistory(Long sessionId, String message) {
    String key = InterviewRedisConstants.historyKey(sessionId);
    redis.opsForList().rightPush(key, message);    // 右 push 新消息
    // FIFO：超过 50 条上限时左 pop 最早的
    Long size = redis.opsForList().size(key);
    if (size != null && size > MAX_HISTORY) {
        redis.opsForList().leftPop(key);
    }
}
```

面试助手取历史时用 `LRANGE 0 9` 取最近 10 条（5 轮 user+assistant）。

**为什么 10 条**：
- 10 条 ≈ 5 轮对话，覆盖当前知识点的上下文足够
- 每条约 200-500 Token，10 条约 2000-5000 Token，在 TokenBudget 轮预算 5000 以内
- 太少（如 4 条）：LLM 看不出对话进展，可能重复追问
- 太多（如 50 条）：Token 爆炸 + Lost in the Middle 问题

### Q8：砍掉的对话历史会丢吗？

**答**：

不会。砍掉的只是 Redis 里的热数据，MySQL 里有完整记录。

```
用户回答 -> SaveRecordTool（即时 INSERT MySQL）
         -> pushHistory（写 Redis 滑动窗口）
         -> trimHistory（超 10 条砍最早，砍的只是 Redis）
```

**Redis 是 AI 的工作记忆**（热数据，给 LLM 看最近上下文），**MySQL 是情景记忆**（冷数据，永久存储完整记录）。两者分层不冲突。

### Q9：什么是 Lost in the Middle 问题？你怎么解决的？

**答**：

Lost in the Middle：LLM 对长上下文中间位置的信息关注度下降，开头和结尾的信息更容易被注意到。

解决方案：
1. **滑动窗口**：只保留最近 10 条，上下文不会太长
2. **题目信息放 System Prompt**（开头位置）：当前题目标题、内容、参考答案放在 `<system_instructions>` 标签内，LLM 一定能注意到
3. **用户回答放 User Prompt**（结尾位置）：候选人当前回答放在 Prompt 最后，LLM 最关注

```
[System Prompt] 面试官角色 + 题目信息（开头，高注意力）
[User Prompt]
  ├── 对话历史（中间，可能被忽略，但只作辅助）
  └── 候选人当前回答（结尾，高注意力）
```

---

## 四、记忆驱动出题

### Q10：老用户和新用户的出题策略有什么区别？

**答**：

```java
// PickQuestionTool.pickFirstWithMemory()
var profile = memoryManager.retrieveProfile(userId);
if (profile == null || profile.getTotalInterviews() == 0) {
    // 新用户：无画像，随机抽题
    return pickQuestion(mode, bankId, Collections.emptySet());
}

// 老用户：取顽固薄弱点，优先考
List<KnowledgeWeakness> weakPoints = memoryManager.retrievePersistentWeakPoints(userId);
KnowledgeWeakness worst = weakPoints.get(0);  // 取最薄弱的
// 用薄弱点关键词查题库
```

| 用户类型 | 出题策略 | 原因 |
|---------|---------|------|
| 新用户（0 次面试） | 随机抽题 | 无历史数据，无法个性化 |
| 老用户（有画像） | 优先考薄弱点 | 连续 2 次 mastery < 60 的知识点最需要复习 |

### Q11：顽固薄弱点是怎么判定的？

**答**：

```java
// SemanticMemoryService.getPersistentWeakPoints()
// 查询 user_knowledge_profile 表
// 条件：is_persistent = 1（连续 2 次 mastery < 60）
// 排序：avg_mastery ASC（掌握度最低的排最前）
```

判定逻辑：
1. 每次面试结束，`KnowledgeProfileAnalyzer` 分析出 Map<知识点, mastery>
2. `saveOrUpdateProfile()` 更新 `user_knowledge_profile` 表，`weakCount++`（mastery < 60 时）
3. `markPersistent()`：当 `weakCount >= 2` 时，设 `is_persistent = 1`
4. 下次面试出题时，`getPersistentWeakPoints()` 取 `is_persistent = 1` 的记录，优先考察

---

## 五、接口设计

### Q12：为什么 MemoryManager 同时实现 ShortTermMemory 和 LongTermMemory 两个接口？

**答**：

```java
public class MemoryManager implements ShortTermMemory, LongTermMemory {
    private final WorkingMemoryService workingMemoryService;      // 短期
    private final EpisodicMemoryService episodicMemoryService;   // 长期-情景
    private final SemanticMemoryService semanticMemoryService;   // 长期-语义
    private final MemoryConsolidationService consolidationService; // 整合
}
```

原因：
1. **对外统一入口**：调用方只需注入 `MemoryManager`，不需要分别注入 3 个 Service。`InterviewServiceImpl` 只需 `memoryManager.pushHistory()` 和 `memoryManager.loadProfile()`，不关心底层分几个 Service。
2. **接口分离原则（ISP）**：如果调用方只需要短期记忆操作（如 Tool 层的 SaveRecordTool 只需 pushHistory），可以声明依赖 `ShortTermMemory` 接口，不需要依赖整个 MemoryManager。
3. **面试可讲**：实现两个接口明确表达了"我是统一记忆管理器，管短期也管长期"的设计意图。

### Q13：MemoryManager 和已有的 ai/memory/MemoryManager 是什么关系？

**答**：

新的 `memory/MemoryManager` 是已有 `ai/memory/MemoryManager` 的**重构版本**：

| 维度 | ai/memory/MemoryManager（旧） | memory/MemoryManager（新） |
|------|------------------------------|--------------------------|
| 接口 | 无接口，直接实现 | implements ShortTermMemory + LongTermMemory |
| 分层 | 方法混在一起 | 短期记忆方法和长期记忆方法明确分组 |
| 委托 | 直接操作 Redis | 委托给 WorkingMemoryService |
| 额外方法 | retrieveProfile 等 | 保留便捷方法 + 新增 endSession() |

新版本委托给已有的 4 个 Service（WorkingMemoryService / EpisodicMemoryService / SemanticMemoryService / MemoryConsolidationService），不重复造轮子。旧版本保留运行，新版本供重构后的 Tool 层和 Orchestrator 层使用。

---

## 六、Redis Key 设计

### Q14：你的 Redis Key 是怎么设计的？为什么用 5 个 Key 而不是 1 个 Hash？

**答**：

5 个独立 Key：

```java
interview:history:{sessionId}       // List  - 对话历史
interview:question:{sessionId}      // String - 当前题目ID
interview:round:{sessionId}         // String - 总轮次
interview:questionRound:{sessionId} // String - 追问轮次
interview:used:{sessionId}          // Set    - 已用题目集
```

不用 1 个 Hash 的原因：

| 维度 | 5 个独立 Key | 1 个 Hash |
|------|------------|-----------|
| TTL 管理 | 每个 Key 独立 TTL，面试结束主动删 | Hash 整体 TTL，不能单独删某个字段 |
| 数据类型 | List（对话历史）+ Set（已用题目）+ String | Hash 只能存 String，对话历史无法用 List 操作 |
| 原子操作 | INCR 轮次是原子的 | HINCRBY 也是原子的，但 List 操作不支持 |
| 内存效率 | 5 个 Key 稍多 | 1 个 Hash 稍省 |

核心原因：**对话历史需要 List 的 RPUSH/LRANGE/LTRIM 操作，已用题目需要 Set 的 SADD/SMEMBERS 操作，Hash 类型无法支持这些操作**。

### Q15：Redis TTL 2 小时，如果面试超过 2 小时怎么办？

**答**：

每次操作都会刷新 TTL：

```java
public void pushHistory(Long sessionId, String message) {
    redis.opsForList().rightPush(key, message);
    redis.expire(key, ttl);  // 每次写入都刷新 TTL
}
```

只要面试在持续进行（每轮都会 pushHistory），TTL 就会不断续期。只有在 2 小时内没有任何操作（用户挂机），Key 才会过期。这是合理的--2 小时不操作说明用户已离开，清理工作记忆是正确的。

---

## 七、与 Tool 模块的协作

### Q16：Tool 模块怎么使用 Memory 模块？

**答**：

```
Tool 模块                        Memory 模块
─────────                        ───────────
PickQuestionTool ──────────────-> getUsedQuestions()（短期：取已用题目集）
                                 retrievePersistentWeakPoints()（长期：取薄弱点）

SaveRecordTool ─────────────────> pushHistory()（短期：写对话历史）

EvaluateAnswerTool ─────────────> getHistory()（短期：取最近10条做上下文）
                                 getCurrentQuestion()（短期：取当前题目）

GenerateReportTool ─────────────> endSession()（整合 + 遗忘）
```

Tool 层只依赖 `MemoryManager`（统一入口），不直接操作 Redis 或 MySQL。Memory 模块对 Tool 层是黑盒，Tool 不需要知道数据存在 Redis 还是 MySQL。

### Q17：如果 Redis 挂了，面试还能继续吗？

**答**：

当前实现下不能。面试的轮次、当前题目、对话历史都存在 Redis，Redis 挂了：
- 无法取当前题目 -> 不知道评估什么
- 无法取对话历史 -> LLM 没有上下文
- 无法推进轮次 -> 三层控制失效

**改进方向**（未实现）：Redis 降级方案：
1. 对话历史：从 MySQL `interview_record` 表取最近 10 条（已有完整记录）
2. 当前题目：从 MySQL 最后一条 `interview_record.questionId` 推断
3. 轮次：从 MySQL `COUNT(*) WHERE sessionId = ?` 计算

这样 Redis 挂了能降级为 MySQL 查询，但性能会下降（MySQL 查询比 Redis 慢 10 倍）。面试时可以这样讲："我设计了 Redis 作为热数据缓存，MySQL 作为冷数据兜底，Redis 故障时可降级到 MySQL 查询，但性能会有降级。"
