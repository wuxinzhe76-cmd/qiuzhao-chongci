# interview-arena · 项目实现全景图

> 📅 更新时间：2026-06-24  
> 📖 读取本文件即可了解整个项目已实现/待实现状态

---

## 一、项目定位

AI 原生面试刷题平台，对标面试鸭 + 牛客网 AI 面试。核心差异化：深度 AI 集成（RAG + Agent + Memory 贯穿全流程）。

---

## 二、已实现模块清单

### 模块 1：用户认证（✅ 完成）

| 功能 | API | 技术栈 |
|------|-----|--------|
| 用户注册 | POST /user/register | 账号+密码+确认密码 |
| 用户登录 | POST /user/login | Sa-Token |
| 用户登出 | POST /user/logout | Sa-Token |
| 获取当前用户 | GET /user/current | Sa-Token |
| 用户管理 | POST /user/add /update /delete | @AuthCheck AOP 权限校验 |

**代码**：`UserController` / `UserService` / `UserServiceImpl`  
**认证方案**：Sa-Token，token 30 天有效期，UUID 风格

---

### 模块 2：题库管理（✅ 完成）

| 功能 | API | 技术栈 |
|------|-----|--------|
| 题库 CRUD | POST /questionBank/add /update /delete /get/vo | MyBatis-Plus |
| 题库分页 | POST /questionBank/list/page/vo | MyBatis-Plus 分页 |
| 题目 CRUD | POST /question/add /update /delete /get/vo | MyBatis-Plus |
| 题目分页 | POST /question/list/page/vo | 多维度筛选 |
| 批量删除 | POST /question/delete/batch | 批量操作 |
| 关联管理 | POST /questionBankQuestion/add /batchAdd /batchRemove | 题库-题目多对多 |

**代码**：`QuestionBankController` / `QuestionController` / `QuestionBankQuestionController`

---

### 模块 3：判题系统（✅ 基础完成）

| 功能 | API | 技术栈 |
|------|-----|--------|
| 提交代码 | POST /judge/submit | 异步判题（RabbitMQ） |
| 查询结果 | GET /judge/result/{id} | 前端轮询 |
| 测试用例管理 | POST /testCase/add /update /delete | CRUD |

**代码**：`JudgeController` / `JudgeService` / `CodeSandbox` / `DockerCodeSandbox` / `MockCodeSandbox`  
**判题流程**：提交 → submission(PENDING) → MQ 异步 → CodeSandbox 执行 → judge_result → 更新 status  
**判题状态**：PENDING → JUDGING → ACCEPTED / WA / TLE / MLE / RE / CE  
**代码沙箱**：`DockerCodeSandbox`（Docker 隔离）+ `MockCodeSandbox`（测试用）

---

### 模块 4：AI 模拟面试（✅ 基础完成）

| 功能 | API | 技术栈 |
|------|-----|--------|
| 开始面试 | POST /interview/start | 创建 session + AI 生成开场提问 |
| 提交回答 | POST /interview/answer | AI 评估 + 动态追问 |
| 结束面试 | POST /interview/end | 异步刷入 DB + 生成报告 |

**代码**：`InterviewController` / `InterviewService` / `AiInterviewStrategyService`  
**面试模式**：指定题库(mode=1) / 大厂随机(mode=2)  
**核心机制**：结构化 JSON 输出 + 行为指令路由（DEEP_DIVE / NEXT_QUESTION / END_INTERVIEW）  
**Redis 数据结构**：对话历史(RList) + 当前题目(RBucket) + 轮次(RAtomicLong) + 已用题目(RSet)，TTL 2h  
**消息队列**：面试结束 → RabbitMQ → 异步生成报告

---

### 模块 5：RAG 深度检索（🔄 部分完成）

#### 已实现（✅）

| 功能 | 文件 | 说明 |
|------|------|------|
| 离线 ETL 全量导入 | RagService.importQuestionsToVectorStore() | MySQL → Document → DashScope Embedding → Milvus（分批 10 条） |
| ES 倒排索引建表 | question-index-mapping.json | IK 中文分词器（ik_max_word + ik_smart） |
| ES Docker 部署 | Dockerfile + docker-compose-es.yml | ES 8.17 + IK 插件 |
| BM25 关键词检索 | BM25Retriever | multi_match + 字段权重（title^3, content^2, answer^1） |
| 向量检索 | HybridRetriever（内调 VectorStore） | Milvus HNSW + COSINE，Top-20 |
| RRF 融合 | HybridRetriever | 两路 Top-20 → RRF(k=60) → Top-10 |
| Cross-Encoder 精排 | RerankService | DashScope gte-rerank API，Top-10 → Top-5 |
| Spring AI 适配器 | HybridDocumentRetriever + RerankDocumentPostProcessor | 实现 DocumentRetriever / DocumentPostProcessor 接口 |
| 模块化编排 | RagConfig.ragAdvisor() | RetrievalAugmentationAdvisor.builder().documentRetriever().documentPostProcessors().build() |
| 语义缓存 | SemanticCache | Redis + cosine > 0.95 命中 |
| 评估指标 | RagEvaluator | Hit Rate@5 + MRR（未串入链路） |
| API 接口 | RagController | /rag/chat（登录可用）+ /rag/import（管理员） |
| 通用 ChatClient | RagConfig.chatClient() | 系统提示词 + SimpleLoggerAdvisor |

#### 已实现（✅ 2026-06-24 Advanced RAG 升级）

| 功能 | 文件 | 说明 |
|------|------|------|
| 查询改写 | `QueryRewriteTransformer` | LLM 改写：纠正术语 + 扩展短 query（Pre-Retrieval） |
| metadata 加 title + category | `QuestionEsDoc` + `importQuestionsToVectorStore` | ETL 入库时加题目标题和题库分类 |
| 元数据过滤 | `HybridRetriever.retrieve(query, filterExpression)` | 向量 + BM25 均支持 category 过滤 |
| 增量入库 | `QuestionChangedEvent` + `RagService.onQuestionChanged` | 事件驱动同步 Milvus + ES（ADD/UPDATE/DELETE） |
| 文档去重 | `DocumentDeduplicator` | RRF 融合后按 questionId/文本去重 |
| Lost-in-the-middle 重排 | `LostInTheMiddleRearranger` | 最相关文档放 Prompt 首尾 |
| 自定义中文 Prompt | `RagService.SYSTEM_PROMPT` + `RAG_PROMPT_TEMPLATE` | 手动编排，约束"只基于检索资料回答" |
| 引用标注（溯源） | `SourceQuestion` + `extractSources` | 返回 questionId + title 列表 |
| RagChatResponse DTO | `RagChatResponse` | answer + sourceQuestions + cacheHit |
| SemanticCache TTL | `SemanticCache` | Redis key 设 1h TTL（bug 修复） |
| ES autocomplete | `QuestionSearchService` + `/rag/suggest` | match_phrase_prefix 前缀匹配 |

#### 评估后不实现（❌）

- 查询路由（多策略）：元数据过滤已实现软路由，按 category 走不同参数属过度设计
- ContextCompressor：Top-5 token 可控，再加 LLM 摘要调用得不偿失

---

### 模块 6：记忆架构（📋 设计完成，未实现）

三层记忆模型（借鉴 Hello-Agents 第八章）：
- 工作记忆（Redis，TTL 2h）→ 已有（AI 面试模块的 Redis 操作）
- 情景记忆（MySQL，永久）→ 已有（interview_session + interview_record 表）
- 语义记忆（MySQL + Milvus，永久）→ ❌ 未实现（user_knowledge_profile + user_memory_summary 表）

记忆整合（Consolidation）：面试结束 → 工作记忆 → 情景记忆 → 语义记忆 自动升级  
遗忘策略：TTL 过期 + 容量限制(50 条 FIFO) + 时间衰减(30 天) + 重要性保留(mastery<40 永不遗忘)  
记忆驱动出题：加载用户画像 → 优先考察顽固薄弱点  
RAG + Memory 智能路由：知识题走 RAG / 历史追问走 Memory / 混合场景两路并行

---

### 模块 7：基础设施（✅ 完成）

| 组件 | 说明 |
|------|------|
| 统一响应体 | BaseResponse\<T\>（code + message + data） |
| 全局异常 | GlobalExceptionHandler + BusinessException |
| MyBatis-Plus | 分页插件 + 逻辑删除 + 自动填充 |
| Flyway | 数据库迁移（V1~V4） |
| Knife4j | API 文档（/swagger-ui.html） |
| Sa-Token | 认证 + 权限 |
| RabbitMQ | 异步判题 + 面试报告 |
| Redis/Redisson | 缓存 + 分布式锁 |
| Elasticsearch | BM25 检索 + 倒排索引（待加 autocomplete） |
| Milvus | 向量存储（HNSW + COSINE，1024 维） |

---

## 三、技术栈

| 层 | 技术 | 版本 |
|----|------|------|
| JDK | Java | 21 |
| 框架 | Spring Boot | 3.4.x |
| AI | Spring AI + Spring AI Alibaba | 1.1.2 / 1.0.0.2 |
| ORM | MyBatis-Plus | 3.5.x |
| 认证 | Sa-Token | 1.39.0 |
| 搜索 | Elasticsearch | 8.17 + IK |
| 向量库 | Milvus | 2.x（HNSW） |
| 缓存 | Redis + Redisson | 7.x |
| 消息队列 | RabbitMQ | — |
| API 文档 | Knife4j | 4.4.0 |
| 数据库迁移 | Flyway | — |
| AI 模型 | DashScope | qwen-plus + text-embedding-v3 + gte-rerank |

---

## 四、数据库表

### 已实现（✅）

| 表 | 用途 |
|----|------|
| user | 用户 |
| question_bank | 题库 |
| question | 题目 |
| question_bank_question | 题库-题目关联（多对多） |
| test_case | 测试用例 |
| submission | 代码提交 |
| judge_result | 判题结果 |
| programming_language | 编程语言 |
| interview_session | 面试会话 |
| interview_record | 面试问答明细 |

### 待实现（❌）

| 表 | 用途 |
|----|------|
| user_knowledge_profile | 用户知识画像（语义记忆） |
| user_memory_summary | 用户记忆摘要（跨会话画像） |
| interview_report | 面试综合评估报告 |
| resume | 简历解析 |
| exam_paper | 组卷 |
| exam_record | 考试记录 |
| post | 社区讨论 |
| note | 用户笔记 |
| learning_path | 学习路径 |
| user_favorite_question | 收藏/错题本 |
