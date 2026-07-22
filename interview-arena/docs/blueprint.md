# interview-arena · 项目蓝图

> 项目代号:**interview-arena**(面试刷题平台,AI 原生)
> 后端工程目录:`MyProject/interview-arena/backend/`
> 文档目录:`MyProject/interview-arena/docs/`
> 任务追踪:`MyProject/interview-arena/tasks/`
> 本文档由原有 mianti-next-backend 代码 + V2 重构计划整合而成,作为 interview-arena 开发的完整参考。
> 阅读本文档即可了解项目现状、技术细节、数据库设计、AI 面试核心逻辑及下一步开发方向。

---

## 一、项目定位

对标面试鸭 + 牛客网 AI 面试,打造一个 **AI 原生的面试题库与模拟面试平台**。

核心差异化:深度 AI 集成(不是简单的 AI 对话包装,而是贯穿全流程的智能辅助)。

### 核心基础能力

| 模块 | 技术方案 | 核心特性 |
|------|---------|---------|
| **面试题 CRUD** | MyBatis-Plus | 题目/题库增删改查、批量操作、分页查询、多条件筛选、多对多关联管理 |
| **用户登录鉴权** | JWT + Redis | 双 token 机制（accessToken 2h + refreshToken 7d）、拦截器统一认证、白名单放行、Redis token 状态管理 |
| **接口限流** | Sentinel | 按 HTTP 路径 + 用户维度 QPS 限流、匿名与登录用户差异化流控、熔断降级保护、ThreadLocal 上下文清理 |

### 目标用户

- 2028 秋招在校生(刷题 + AI 模拟面试)
- 技术面试备考者(多轮对话面试 + 智能追问)

---

## 二、技术选型

### 当前版本(原 mianti-next-backend,已迁移到 interview-arena-backend)

| 层 | 技术 | 版本 |
|----|------|------|
| JDK | Java | 17 |
| 框架 | Spring Boot | 3.2.4 |
| AI | Spring AI Alibaba | 1.0.0-M3.1(预览版,API 已过时) |
| ORM | MyBatis-Plus | 3.5.2 |
| 连接池 | Druid | 1.2.23 |
| 缓存 | Redis + Redisson | 3.21.0 |
| 搜索 | Elasticsearch | Spring Boot Data ES |
| 认证 | Sa-Token | 1.39.0 |
| 限流 | Sentinel | 2021.0.5.0 |
| 配置中心 | Nacos | 0.2.11 |
| API 文档 | Knife4j | 4.4.0 |
| 热点探测 | JD HotKey | 0.0.4-SNAPSHOT(system scope) |
| 错误监控 | Sentry | — |
| 消息队列 | RabbitMQ | — |
| 数据库迁移 | Flyway | — |

### V2 目标版本

| 层 | 技术 | 版本 | 变更原因 |
|----|------|------|----------|
| JDK | Java | **21** | 虚拟线程提升 AI 推理并发 |
| 框架 | Spring Boot | **3.4.x** | Spring AI 1.1.x 要求 |
| AI | Spring AI Alibaba | **1.1.x** | M3.1 已过时,1.1.x 是 GA |
| ORM | MyBatis-Plus | **3.5.x** | 小版本升级 |
| groupId | — | **com.charles** | 修正模板残留 `com.yupi` |
| 前端 | Next.js 14 | — | 替换 Vue 3(V2 计划) |
| 判题沙箱 | Docker | — | 替换本地进程沙箱 |
| 文件存储 | MinIO | — | 替换本地存储 |

---

## 三、系统架构

```
                         ┌─────────────────┐
                         │   Gateway 网关   │
                         └──┬───┬───┬───┬──┘
                            │   │   │   │
                 ┌──────────┘   │   │   └──────────┐
                 ▼              ▼   ▼              ▼
          ┌──────────┐  ┌────────┐ ┌────────┐ ┌────────────┐
          │ user-svc │  │qbank-svc│ │judge-svc│ │ ai-service │
          │          │  │        │ │        │ │            │
          │ Sa-Token │  │ ES搜索 │ │ Docker │ │ 通义千问    │
          │ Redis    │  │ MySQL  │ │ 沙箱   │ │ Spring AI  │
          │ MySQL    │  │Caffeine│ │ MQ     │ │ Alibaba    │
          └──────────┘  └────────┘ └────────┘ │            │
                                               │ RAG 模块    │
                                               │  PgVector  │
                                               │  Embedding │
                                               │  QA Advisor│
                                               │            │
                                               │ Agent 模块  │
                                               │  @Tool     │
                                               │  ChatMemory│
                                               │  状态机     │
                                               │            │
                                               │ MCP 模块   │
                                               │  MCP Server│
                                               └─────┬──────┘
                                                     │
                                          ┌──────────┼──────────┐
                                          │          │          │
                                          ▼          ▼          ▼
                                    ┌────────┐ ┌────────┐ ┌────────┐
                                    │PgVector│ │ MySQL  │ │ Redis  │
                                    │向量存储 │ │记忆持久化│ │会话缓存 │
                                    └────────┘ └────────┘ └────────┘
```

---

## 四、数据库设计

### 4.1 核心业务表

#### user(用户表)

```sql
create table if not exists user (
    id           bigint auto_increment primary key,
    userAccount  varchar(256) not null comment '账号',
    userPassword varchar(512) not null comment '密码',
    unionId      varchar(256) null comment '微信开放平台id',
    mpOpenId     varchar(256) null comment '公众号openId',
    userName     varchar(256) null comment '用户昵称',
    userAvatar   varchar(1024) null comment '用户头像',
    userProfile  varchar(512) null comment '用户简介',
    userRole     varchar(256) default 'user' not null comment '角色: user/admin/ban',
    editTime     datetime default CURRENT_TIMESTAMP not null,
    createTime   datetime default CURRENT_TIMESTAMP not null,
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete     tinyint default 0 not null comment '是否删除',
    index idx_unionId (unionId)
);
```

#### question_bank(题库表)

```sql
create table if not exists question_bank (
    id          bigint auto_increment primary key,
    title       varchar(256) null comment '标题',
    description text null comment '描述',
    picture     varchar(2048) null comment '图片',
    userId      bigint not null comment '创建用户 id',
    editTime    datetime default CURRENT_TIMESTAMP not null,
    createTime  datetime default CURRENT_TIMESTAMP not null,
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete    tinyint default 0 not null,
    index idx_title (title)
);
```

#### question(题目表)

```sql
create table if not exists question (
    id         bigint auto_increment primary key,
    title      varchar(256) null comment '标题',
    content    text null comment '内容',
    tags       varchar(1024) null comment '标签列表(json 数组)',
    answer     text null comment '推荐答案',
    type       varchar(50) default 'PROGRAMMING' comment '类型: PROGRAMMING/CHOICE/FILL_IN',
    difficulty varchar(20) default 'MEDIUM' comment '难度: EASY/MEDIUM/HARD',
    template   text null comment '代码模板(JSON)',
    timeLimit  int default 1000 comment '时间限制(ms)',
    memoryLimit int default 256 comment '内存限制(MB)',
    acceptedCount int default 0 comment '通过人数',
    submissionCount int default 0 comment '提交次数',
    acceptanceRate decimal(5,2) default 0 comment '通过率',
    userId     bigint not null comment '创建用户 id',
    editTime   datetime default CURRENT_TIMESTAMP not null,
    createTime datetime default CURRENT_TIMESTAMP not null,
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete   tinyint default 0 not null,
    index idx_title (title),
    index idx_userId (userId)
);
```

#### question_bank_question(题库题目关联表)

```sql
create table if not exists question_bank_question (
    id             bigint auto_increment primary key,
    questionBankId bigint not null comment '题库 id',
    questionId     bigint not null comment '题目 id',
    userId         bigint not null comment '创建用户 id',
    createTime     datetime default CURRENT_TIMESTAMP not null,
    updateTime     datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    UNIQUE (questionBankId, questionId)
);
```

### 4.2 判题系统表

#### test_case(测试用例表)

```sql
create table if not exists test_case (
    id          bigint auto_increment primary key,
    questionId  bigint not null,
    input       text not null comment '输入样例',
    output      text not null comment '输出样例',
    isExample   tinyint default 0 comment '0-隐藏, 1-示例',
    score       int default 100 comment '分值',
    userId      bigint not null,
    createTime  datetime default CURRENT_TIMESTAMP not null,
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete    tinyint default 0 not null,
    index idx_questionId (questionId)
);
```

#### submission(代码提交表)

```sql
create table if not exists submission (
    id              bigint auto_increment primary key,
    questionId      bigint not null,
    userId          bigint not null,
    languageCode    varchar(100) not null comment 'java/python/cpp/javascript',
    code            text not null,
    status          varchar(50) default 'PENDING' comment 'PENDING/JUDGING/ACCEPTED/WA/TLE/MLE/RE/CE',
    executionTime   int null comment 'ms',
    executionMemory int null comment 'KB',
    testCaseScore   int null,
    totalTestCase   int null,
    passedTestCase  int null,
    errorMessage    text null,
    ip              varchar(100) null,
    createTime      datetime default CURRENT_TIMESTAMP not null,
    updateTime      datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    index idx_questionId (questionId),
    index idx_userId (userId),
    index idx_status (status)
);
```

#### judge_result(判题结果详情表)

```sql
create table if not exists judge_result (
    id              bigint auto_increment primary key,
    submissionId    bigint not null,
    questionId      bigint not null,
    userId          bigint not null,
    languageCode    varchar(100) not null,
    code            text not null,
    verdict         varchar(50) not null comment 'ACCEPTED/WA/TLE/MLE/RE/CE',
    executionTime   int null,
    executionMemory int null,
    passedTestCase  int null,
    totalTestCase   int null,
    testCaseResults text null comment '各用例结果(JSON)',
    compileOutput   text null,
    runOutput       text null,
    errorMessage    text null,
    judgeServer     varchar(256) null,
    judgeTime       datetime null,
    createTime      datetime default CURRENT_TIMESTAMP not null,
    index idx_submissionId (submissionId),
    index idx_userId (userId)
);
```

#### programming_language(编程语言表)

```sql
create table if not exists programming_language (
    id          bigint auto_increment primary key,
    languageName varchar(256) not null,
    languageCode varchar(100) not null comment 'java/python/cpp/javascript',
    version      varchar(100) null,
    compileCommand varchar(512) null,
    runCommand     varchar(512) null,
    icon         varchar(1024) null,
    isActive     tinyint default 1 not null,
    userId       bigint not null,
    createTime   datetime default CURRENT_TIMESTAMP not null,
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete     tinyint default 0 not null,
    unique key uk_languageCode (languageCode)
);
```

### 4.3 AI 面试表

#### interview_session(面试会话表)

```sql
create table if not exists interview_session (
    id          bigint not null comment '主键(雪花算法)' primary key,
    user_id     bigint not null comment '面试者 ID',
    mode        tinyint not null comment '模式: 1-指定题库, 2-大厂随机',
    bank_id     bigint null comment '关联题库 ID(模式1有值)',
    status      tinyint default 0 not null comment '0-进行中, 1-已结束, 2-已生成报告',
    score       int null comment '本次面试综合评分(AI生成)',
    create_time datetime default CURRENT_TIMESTAMP not null,
    update_time datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    is_delete   tinyint default 0 not null,
    index idx_user_id (user_id),
    index idx_bank_id (bank_id)
);
```

#### interview_record(面试问答明细表)

```sql
create table if not exists interview_record (
    id          bigint auto_increment primary key,
    session_id  bigint not null comment '关联的面试会话 ID',
    question_id bigint null comment '当前讨论的具体题目 ID',
    role        varchar(20) not null comment 'user 或 assistant',
    content     text null comment '回答或提问内容',
    round_num   int null comment '当前对话属于第几轮',
    create_time datetime default CURRENT_TIMESTAMP not null,
    index idx_session_id (session_id)
);
```

### 4.4 V2 规划新增表(未实现)

| 表 | 用途 | 状态 |
|----|------|------|
| interview_report | 面试综合评估报告(雷达图+学习路径) | ⬜ 未实现 |
| resume | 简历解析与 AI 分析 | ⬜ 未实现 |
| exam_paper | 组卷与考试 | ⬜ 未实现 |
| exam_record | 考试记录 | ⬜ 未实现 |
| post | 社区讨论帖 | ⬜ 未实现 |
| note | 用户笔记 | ⬜ 未实现 |
| learning_path | 学习路径 | ⬜ 未实现 |
| user_answer | 用户答题统计 | ⬜ 未实现 |
| user_favorite_question | 收藏/错题本 | ⬜ 未实现 |
| user_knowledge_profile | 用户知识画像（语义记忆：知识点掌握度+薄弱点） | ⬜ 未实现 |
| user_memory_summary | 用户记忆摘要（跨会话画像：总面试次数+Top薄弱点+推荐复习） | ⬜ 未实现 |

---

## 五、核心模块功能与实现

### 5.1 用户模块

**现有代码**: `UserController` / `UserService` / `UserServiceImpl`

| 功能 | API | 说明 |
|------|-----|------|
| 用户注册 | POST `/user/register` | 账号+密码+确认密码 |
| 用户登录 | POST `/user/login` | Sa-Token 登录 |
| 用户登出 | POST `/user/logout` | Sa-Token 登出 |
| 获取当前用户 | GET `/user/current` | 从 Sa-Token 获取 |
| 微信登录 | GET `/user/login/wx_open` | WxJava SDK |
| 用户管理 | POST `/user/add` `/user/update` `/user/delete` | 管理员权限 |

**认证方案**: Sa-Token,token 有效期 30 天,UUID 风格,不允许同账号多端登录。

**权限注解**: `@AuthCheck(mustRole = "admin")` 通过 AOP 拦截器实现。

### 5.2 题库管理模块

**现有代码**: `QuestionBankController` / `QuestionController` / `QuestionBankQuestionController`

#### 5.2.1 面试题 CRUD 设计

| 功能 | API | 实现细节 |
|------|-----|---------|
| 新增题目 | POST `/api/question/add` | 标题+内容+标签+答案+难度+类型，自动注入 userId，Elasticsearch 同步索引 |
| 更新题目 | POST `/api/question/update` | 全量更新，同步更新 ES 索引，发布 `QuestionChangedEvent` 事件 |
| 删除题目 | DELETE `/api/question/delete/{id}` | 逻辑删除，同步删除 ES 文档 |
| 题目详情 | GET `/api/question/get/vo/{id}` | VO 转换，标签格式化展示 |
| 分页查询 | POST `/api/question/list/page/vo` | 多维度筛选（难度/标签/类型/关键词），MP 分页插件 |
| 全文搜索 | POST `/api/question/search/page/vo` | Elasticsearch 跨字段检索（title/content/tags） |

#### 5.2.2 题库 CRUD 与关联管理

| 功能 | API | 实现细节 |
|------|-----|---------|
| 题库 CRUD | `/api/questionBank/add` `/update` `/delete` `/get/vo` | 标题+描述+封面图片 |
| 题库分页 | `/api/questionBank/list/page/vo` | MyBatis-Plus 分页，支持名称模糊查询 |
| 单题加入题库 | POST `/api/question/bank/add` | question_bank_question 中间表写入 |
| 批量加入题库 | POST `/api/question/bank/batchAdd` | 批量插入中间表，事务保障 |
| 批量移出题库 | POST `/api/question/bank/batchRemove` | 批量删除中间表 |
| 题库题目列表 | GET `/api/question/bank/list/{bankId}` | 查中间表 + 联查题目详情 |

#### 5.2.3 技术要点

**数据层**：
- MyBatis-Plus `IService` + `BaseMapper`，代码生成器减少样板代码
- `question_bank_question` 中间表维护多对多关系，唯一索引防重复
- 逻辑删除 `isDelete` 字段，数据可恢复

**搜索层**：
- Elasticsearch 索引 `question`，字段：`title` + `content` + `tags`
- 题目新增/更新时异步同步 ES，解耦主流程

**缓存层**：
- Caffeine 本地缓存热点题目（高频访问不打 DB）
- 题目变更事件触发缓存失效（`@CacheEvict`）

### 5.3 判题模块

**现有代码**: `JudgeController` / `JudgeService` / `CodeSandbox` / `SimpleCodeSandbox`

| 功能 | API | 说明 |
|------|-----|------|
| 提交代码 | POST `/judge/submit` | 创建 submission + 异步判题 |
| 查询结果 | GET `/judge/result/{id}` | 轮询判题结果 |
| 测试用例管理 | POST `/testCase/add` `/update` `/delete` | CRUD |

**判题流程**:
1. 用户提交代码 → 创建 submission(status=PENDING)
2. 异步执行: 取测试用例 → CodeSandbox 执行 → 对比输出
3. 保存 judge_result → 更新 submission status
4. 前端轮询 GET `/judge/result/{id}`

**判题状态**: PENDING → JUDGING → ACCEPTED / WA / TLE / MLE / RE / CE

**代码沙箱**: 当前只有 `SimpleCodeSandbox`(本地进程执行,不安全)。V2 计划用 Docker 隔离沙箱。

**CodeSandbox 接口**:
```java
public interface CodeSandbox {
    ExecuteResult execute(String languageCode, String code, String input, int timeLimit, int memoryLimit);
    CompileResult compile(String languageCode, String code);
    enum Verdict { ACCEPTED, WRONG_ANSWER, TIME_LIMIT_EXCEEDED, MEMORY_LIMIT_EXCEEDED, RUNTIME_ERROR, COMPILE_ERROR }
}
```

### 5.4 AI 面试模块(核心)

**现有代码**: `InterviewController` / `InterviewService` / `AiInterviewStrategyService` / `InterviewPromptConstants`

#### 面试模式

| 模式 | 说明 | 技术核心 |
|------|------|----------|
| 指定题库(mode=1) | 从指定题库抽题,定向突击 | 上下文注入: 题目信息作为 System Prompt |
| 大厂随机(mode=2) | 全局随机抽题,全真模拟 | 动态追问: AI 自主深度挖掘 |

#### 核心流程（★ 2026-06-25 讨论修订：每轮即时落库 + Redis 滑动窗口）

> **修订点**（vs 原始设计）：
> 1. 每轮回答后**即时 INSERT interview_record**（不等面试结束批量刷入），防止异常中断丢数据
> 2. Redis 对话历史改**滑动窗口**（最近 10 条），超限 LTRIM 砍最早——砍的消息已落库，安心丢
> 3. action_directive **三层控制**：AI 主导 + 代码兜底（单题 >3 轮强制换题 / 总轮 >=10 强制结束）+ 用户主动结束
> 4. 面试结束后**立即删 Redis 4 个 key**（不等 TTL 2h 超时），释放空间
> 5. 面试主流程**不走 RAG**（题目已选定，直接 MySQL 查注入 Prompt）；RAG 用于"快速询问"场景（§5.5.6）

```
开始面试 POST /api/interview/start
  → 创建 interview_session(status=0 进行中)
  → 按模式抽第一道题:
      mode=1 指定题库 → question_bank_question 关联表查
      mode=2 大厂随机 → question 表随机查
  → Redis 缓存 4 个 key: 对话历史/当前题目/轮次/已用题目集（TTL 2h）
  → AI 生成开场提问 → 即时 INSERT interview_record(role=assistant)
  → push AI 开场提问到 Redis 对话历史
  → 返回 sessionId + openingQuestion

提交回答 POST /api/interview/answer（循环调用）
  → 即时 INSERT interview_record(role=user, content=用户回答)  ← 先落库
  → Redis 推进轮次 + push 用户回答到对话历史
  → 从 Redis 取当前题目 + 对话历史（最近 10 条滑动窗口）
  → 调通义千问(结构化输出 → JSON: reply_to_user + action_directive + current_topic_mastery)
  → 即时 INSERT interview_record(role=assistant, content=AI 回复)  ← AI 回复也落库
  → push AI 回复到 Redis 对话历史
  → Redis List > 10 条 → LTRIM 砍最早（已落库，安心丢）
  → 按 action_directive 路由（三层控制见下方）
      DEEP_DIVE → 继续追问当前知识点（AI 回复已在上面存 Redis + DB）
      NEXT_QUESTION → 抽下一题（排除已用题目集）→ AI 生成过渡提问 → 存 Redis + DB
      END_INTERVIEW → 结束面试（见下方）

结束面试 POST /api/interview/end/{sessionId}（用户主动 / 自动触发）
  → interview_session.status → 1（已结束）
  → 立即删除 Redis 4 个 key（不等 TTL 2h 超时，释放空间）
  → 发 MQ 消息 → 异步生成面试报告
```

#### 结构化输出 (Structured Output)

AI 返回 JSON:
```json
{
  "reply_to_user": "你的思路很对,利用了 HashMap 的 O(1) 查找特性。那你能进一步讲讲 HashMap 在 JDK 1.8 中的扩容机制吗?",
  "action_directive": "DEEP_DIVE",
  "current_topic_mastery": 80
}
```

**行为指令枚举**:
- `DEEP_DIVE`: 继续追问当前知识点
- `NEXT_QUESTION`: 切换下一道题
- `END_INTERVIEW`: 结束面试

**三层控制机制**（AI 主导 + 代码兜底 + 用户主动）：

| 控制方 | 职责 | 触发条件 |
|--------|------|---------|
| AI 主导 | 看用户回答质量返回 action_directive | 每轮 AI 调用都返回 |
| 代码兜底 1 | 单题追问 > 3 轮 → 强制 `NEXT_QUESTION` | 防止 AI 在一道题上无限追问 |
| 代码兜底 2 | 总轮次 >= 10 → 强制 `END_INTERVIEW` | 防止 AI 永远不结束 |
| 用户主动 | `POST /api/interview/end/{sessionId}` | 用户中途退出 |

```java
String directive = aiResponse.getActionDirective();
// 代码兜底 1：单题超过 3 轮，强制换题
if (currentQuestionRoundCount >= 3 && "DEEP_DIVE".equals(directive)) {
    directive = "NEXT_QUESTION";
}
// 代码兜底 2：总轮次达到上限（10 轮，可配），强制结束
if (totalRound >= maxRounds) {
    directive = "END_INTERVIEW";
}
```

> 总轮数上限 10 轮的依据：大厂技术面试 45-60 分钟，每轮问答约 4-5 分钟，10 轮刚好覆盖。值放 `application.yaml` 可配。

#### Spring AI 调用方式

```java
// AiInterviewStrategyServiceImpl
AiInterviewResponseDTO response = chatClient.prompt()
        .system(systemPrompt)    // System Prompt 含题目信息 + 面试规则
        .user(userPrompt)        // User Prompt 含对话历史 + 当前回答
        .call()
        .entity(AiInterviewResponseDTO.class);  // 自动反序列化 JSON → DTO
```

#### System Prompt 设计

```
你是一个资深的 Java 架构师面试官。你的目标是考察候选人的真实水平。

【面试规则】
1. 每次只问一个问题,不要一次性抛出多个问题。
2. 如果候选人回答正确且该知识点有深度,请继续追问底层原理。
3. 如果候选人连续两次回答偏题,或明确表示不懂,请简短指出正确方向,
   并将 action_directive 设置为 'NEXT_QUESTION'。
4. 如果针对当前题目的提问已经超过 3 轮,无论候选人回答如何,
   请将 action_directive 设置为 'NEXT_QUESTION'。
5. 你的输出必须是严格的 JSON 格式,包含 reply_to_user, action_directive,
   current_topic_mastery 三个字段。

【当前题目信息】
题目: {{questionTitle}}
描述: {{questionContent}}
参考答案: {{questionAnswer}}
```

#### Redis 数据结构（StringRedisTemplate，非 Redisson）

| Key | 类型 | Spring API | 说明 | TTL |
|-----|------|-----------|------|-----|
| `interview:history:{sessionId}` | List | `opsForList()` | 对话历史（role:content），**滑动窗口最近 10 条**，超限 LTRIM 砍最早（已落库） | 2h |
| `interview:question:{sessionId}` | String | `opsForValue()` | 当前题目 ID | 2h |
| `interview:round:{sessionId}` | String | `opsForValue()` | 当前总轮次 | 2h |
| `interview:questionRound:{sessionId}` | String | `opsForValue()` | 当前题目已追问轮次（兜底 1 用） | 2h |
| `interview:used:{sessionId}` | Set | `opsForSet()` | 已使用题目集（防重复抽题） | 2h |

**滑动窗口机制**：
- Redis List 只保留最近 10 条消息（5 轮 user+assistant），给 AI 看上下文
- 超过 10 条 → `LTRIM` 砍最早的，**砍的消息已经在 MySQL interview_record 里了**（每轮即时落库）
- Redis 是 AI 的工作记忆（热数据），MySQL 是持久化记录（冷数据），分层不冲突

**面试结束清理**：
```java
// 面试结束时立即删 4 个 key（不等 TTL 2h 超时，释放空间）
redisTemplate.delete("interview:history:" + sessionId);
redisTemplate.delete("interview:question:" + sessionId);
redisTemplate.delete("interview:round:" + sessionId);
redisTemplate.delete("interview:questionRound:" + sessionId);
redisTemplate.delete("interview:used:" + sessionId);
```

#### 消息队列

`InterviewReportProducer` 在面试结束时发送 RabbitMQ 消息,异步生成面试报告。

#### 记忆分层架构（★ 借鉴 Hello-Agents 第八章 + Spring AI 落地）

> 设计灵感：Hello-Agents 第八章将 Agent 记忆分为工作记忆 / 情景记忆 / 语义记忆 / 感知记忆四层，
> 配套记忆整合（consolidation）与遗忘（forgetting）机制。
> 本项目是 Java Spring AI 项目，不照搬 Python 四层架构，而是取其**分层思想 + 整合 + 遗忘**，
> 用 Redis + MySQL + Milvus 三级存储落地，贴合面试场景。

##### 为什么需要记忆分层

当前 Agent 模块用 Redis 存对话历史（TTL 2h），面试结束即丢失。问题：
1. **跨会话遗忘**：用户上次面试在 volatile 可见性上卡住，下次面试不会自动回避或重点考察
2. **无用户画像**：不知道用户擅长 Java 基础但弱在并发，每次都从零开始
3. **无法个性化出题**：没有历史表现数据驱动抽题策略
4. **对话上下文割裂**：长面试中早期信息可能因 Redis 容量被挤出

##### 三层记忆模型

借鉴 Hello-Agents 的工作记忆 / 情景记忆 / 语义记忆（感知记忆暂不需要，无多模态场景）：

```
┌─────────────────────────────────────────────────────────────┐
│                    MemoryManager（统一调度）                  │
│           负责 add / retrieve / consolidate / forget          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │  工作记忆     │  │  情景记忆     │  │  语义记忆     │       │
│  │ WorkingMemory│  │EpisodicMemory│  │SemanticMemory│       │
│  │              │  │              │  │              │       │
│  │ Redis        │  │ MySQL        │  │ MySQL+Milvus │       │
│  │ 会话级       │  │ 跨会话       │  │ 跨会话       │       │
│  │ TTL 2h       │  │ 永久         │  │ 永久         │       │
│  │              │  │              │  │              │       │
│  │ 当前对话上下文│  │ 历次面试记录 │  │ 用户知识画像 │       │
│  │ 当前题目     │  │ 具体问答明细 │  │ 薄弱点 + 画像│       │
│  │ 当前轮次     │  │ 时间序列     │  │ 向量检索     │       │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘       │
│         │                 │                  │               │
│         └──────consolidate┘──────────────────┘               │
│           （面试结束触发：工作→情景→语义 自动升级）            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

| 记忆层 | 存储介质 | 生命周期 | 存什么 | 对应 Hello-Agents |
|--------|---------|---------|--------|------------------|
| **工作记忆** | Redis（已有） | TTL 2h，会话级 | 当前对话历史、当前题目、轮次、已用题目集 | WorkingMemory（纯内存 + TTL） |
| **情景记忆** | MySQL（已有 interview_session + interview_record） | 永久 | 每次面试的完整问答明细、评分、时间序列 | EpisodicMemory（SQLite + Qdrant） |
| **语义记忆** | MySQL + Milvus（新增） | 永久 | 用户知识画像（薄弱点、掌握度、偏好标签） | SemanticMemory（Qdrant + Neo4j） |

##### 记忆整合机制（Consolidation）

借鉴 Hello-Agents 的"工作记忆→情景记忆→语义记忆"自动升级，在面试结束时触发：

```
面试结束（END_INTERVIEW）
  │
  ├── Step 1: 工作记忆 → 情景记忆
  │   Redis 对话历史 → 异步刷入 interview_record 表（已有逻辑）
  │   interview_session status → 1（已结束）
  │
  ├── Step 2: 情景记忆 → 语义记忆（★ 新增核心）
  │   AI 分析本次面试所有问答 → 提取知识图谱：
  │   - 哪些知识点被考察了
  │   - 每个知识点的掌握度（current_topic_mastery 均值）
  │   - 标记薄弱点（mastery < 60）
  │   → 写入 user_knowledge_profile 表
  │   → 薄弱点描述向量化后写入 Milvus（支持语义检索"用户在哪方面弱"）
  │
  └── Step 3: 更新用户画像
      user_memory_summary 表更新：
      - 总面试次数、平均分
      - Top-3 薄弱知识点
      - 推荐复习方向
```

**触发条件**（借鉴 Hello-Agents 整合触发设计）：
- 面试结束（END_INTERVIEW）→ 全量整合
- 单题 mastery < 40 → 即时标记薄弱点（不等面试结束）
- 同一知识点连续 2 次面试 mastery < 60 → 升级为"顽固薄弱点"，下次面试优先考察

##### 遗忘策略（Forgetting）

借鉴 Hello-Agents 的三种遗忘策略，适配面试场景：

| 策略 | 实现 | 场景 |
|------|------|------|
| **TTL 过期** | Redis 工作记忆 TTL 2h，面试结束 30 分钟后自动清理 | 会话结束即遗忘短期上下文 |
| **容量限制** | 工作记忆对话历史超过 50 条时，FIFO 淘汰最早消息 | 防止长面试上下文爆炸 |
| **时间衰减** | 情景记忆 30 天前的记录不参与检索（但仍保留在 DB 可查） | 近期面试表现权重更高 |
| **重要性保留** | mastery < 40 的问答记录永久保留且权重 ×2（不衰减） | 薄弱点不能忘 |

**评分公式**（借鉴 Hello-Agents EpisodicMemory 评分）：
```
retrieval_score = relevance × time_decay × importance_weight

relevance:         与当前面试主题的语义相似度（Milvus 向量检索）
time_decay:        max(0.3, 1 - days_elapsed / 30)  // 30天衰减到0.3底线
importance_weight: mastery < 40 ? 2.0 : (mastery < 60 ? 1.5 : 1.0)
```

##### 记忆驱动的智能出题（★ 核心差异化）

面试开始时，从语义记忆加载用户画像，驱动出题策略：

```java
// 开始面试时加载用户记忆
UserKnowledgeProfile profile = semanticMemoryService.loadProfile(userId);

if (profile == null || profile.getTotalInterviews() == 0) {
    // 新用户：随机抽题
    return questionService.randomSelect(bankId, excludeSet);
}

// 老用户：记忆驱动出题
List<KnowledgeWeakness> weakPoints = profile.getWeakPoints(); // mastery < 60

if (!weakPoints.isEmpty()) {
    // 优先考顽固薄弱点（连续2次低分）
    KnowledgeWeakness worst = weakPoints.stream()
        .filter(KnowledgeWeakness::isPersistent)
        .max(Comparator.comparingInt(w -> -w.getAvgMastery()))
        .orElse(weakPoints.get(0));

    // 从题库中找与薄弱点语义相似的题目
    List<Long> candidates = vectorStore.similarSearch(
        worst.getDescription(), topK = 10, excludeUsed = true
    );
    return questionService.getById(candidates.get(random));
}

// 无薄弱点：按用户偏好标签抽题
return questionService.selectByTags(profile.getPreferredTags(), excludeSet);
```

##### RAG + Memory 智能路由

借鉴 Hello-Agents 第八章 8.4 的"RAG 检索 + Memory 检索"双路设计：

```
用户在面试中提问
  │
  ├── 是知识题？ → 走 RAG（Hybrid Search + Rerank → 注入 Prompt）
  │   "HashMap 的扩容机制是什么？"
  │
  ├── 是追问历史？ → 走 Memory（情景记忆检索历次面试记录）
  │   "我上次这道题答得怎么样？"
  │
  └── 混合场景？ → 两路并行，合并上下文
      "我上次在这里卡住了，再给我讲讲"
      → Memory: 上次该知识点的问答记录
      → RAG: 该知识点的标准答案
      → 合并注入 Prompt
```

**路由判断**（轻量 LLM 分类或关键词规则）：
```java
public enum RetrievalRoute {
    RAG_ONLY,       // 知识题
    MEMORY_ONLY,    // 历史追问
    RAG_AND_MEMORY  // 混合
}

// 规则兜底（不每次调 LLM）
if (containsKeyword(query, "上次", "之前", "上次面试")) return MEMORY_ONLY;
if (containsKeyword(query, "什么是", "原理", "区别")) return RAG_ONLY;
return RAG_AND_MEMORY; // 默认混合
```

##### 新增数据库表

###### user_knowledge_profile（用户知识画像表 ★ 新增）

```sql
create table if not exists user_knowledge_profile (
    id              bigint auto_increment primary key,
    user_id         bigint not null comment '用户 ID',
    topic           varchar(256) not null comment '知识点名称（如 Java volatile）',
    topic_vector    json null comment '知识点向量（Milvus 同步用）',
    avg_mastery     int default 0 comment '该知识点历史平均掌握度 0-100',
    exam_count      int default 0 comment '被考察次数',
    weak_count      int default 0 comment '掌握度 < 60 的次数',
    is_persistent   tinyint default 0 comment '是否顽固薄弱点（连续2次<60）',
    last_exam_time  datetime null comment '最近一次考察时间',
    create_time     datetime default CURRENT_TIMESTAMP not null,
    update_time     datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    unique key uk_user_topic (user_id, topic),
    index idx_user_weak (user_id, avg_mastery)
) comment '用户知识画像（语义记忆持久化）';
```

###### user_memory_summary（用户记忆摘要表 ★ 新增）

```sql
create table if not exists user_memory_summary (
    id                  bigint auto_increment primary key,
    user_id             bigint not null comment '用户 ID',
    total_interviews    int default 0 comment '总面试次数',
    avg_score           decimal(5,2) default 0 comment '历史平均分',
    weak_topics         varchar(1024) null comment 'Top-3 薄弱知识点（JSON 数组）',
    preferred_tags      varchar(512) null comment '偏好标签（JSON 数组）',
    recommended_review  text null comment 'AI 生成的推荐复习方向',
    last_interview_time datetime null comment '最近面试时间',
    create_time         datetime default CURRENT_TIMESTAMP not null,
    update_time         datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    unique key uk_user (user_id)
) comment '用户记忆摘要（跨会话画像）';
```

##### 新增包结构

```
com.charles.interview.arena.ai.memory/
├── MemoryManager.java                 # ★ 记忆统一调度器（add/retrieve/consolidate/forget）
├── working/
│   └── WorkingMemoryService.java      # 工作记忆（Redis，封装现有 Redis 操作 + 容量管理）
├── episodic/
│   └── EpisodicMemoryService.java     # 情景记忆（MySQL，封装 interview_session/record 检索 + 时间衰减评分）
├── semantic/
│   ├── SemanticMemoryService.java     # 语义记忆（MySQL + Milvus，用户画像 CRUD + 向量检索）
│   └── KnowledgeProfileAnalyzer.java  # AI 分析面试记录 → 提取知识点/薄弱点 → 写入画像
├── consolidation/
│   └── MemoryConsolidationService.java # 记忆整合（面试结束触发：工作→情景→语义）
├── routing/
│   └── RetrievalRouter.java           # RAG + Memory 智能路由
└── model/
    ├── KnowledgeWeakness.java         # 薄弱点 DTO
    ├── UserKnowledgeProfile.java      # 用户知识画像 Entity
    └── RetrievalRoute.java            # 路由枚举
```

##### 面试讲点

| 面试问题 | 怎么讲 |
|---------|--------|
| "你的 Agent 怎么记住用户的？" | "借鉴 Hello-Agents 的记忆分层思想，设计了工作记忆（Redis 会话级）、情景记忆（MySQL 面试记录）、语义记忆（MySQL+Milvus 用户画像）三层架构，面试结束时自动触发记忆整合，从对话中提取知识薄弱点写入用户画像" |
| "用户下次面试怎么用上历史数据？" | "语义记忆驱动智能出题：加载用户画像，优先考察顽固薄弱点（连续 2 次低分的知识点），通过 Milvus 向量检索找语义相似的题目" |
| "记忆会无限膨胀吗？" | "三种遗忘策略：Redis TTL 过期、对话历史容量 50 条 FIFO 淘汰、情景记忆 30 天时间衰减；但薄弱点 mastery < 40 的记录永久保留且权重 ×2，不能忘" |
| "RAG 和 Memory 怎么选？" | "智能路由：知识题走 RAG（Hybrid+Rerank），历史追问走 Memory，混合场景两路并行合并上下文" |
| "为什么不用 Neo4j 做知识图谱？" | "Hello-Agents 用 Neo4j 做实体关系推理，但面试场景的知识点关系相对简单（标签层级），用 MySQL 标签 + Milvus 向量检索已够用，避免引入额外中间件增加运维成本" |

### 5.5 RAG 深度检索模块（★ 核心区分度）

> ⚠️ 当前 blueprint 中 RAG 模块仅为基础向量检索（PgVector → 已改为 Milvus）。
> 以下 4 个深度模块为 V2 新增，是 interview-arena 区分度的核心来源。

#### 5.5.1 混合检索（HybridRetriever）

**为什么不能只用向量检索？**
- 向量检索擅长语义相似，但不擅长精确关键词匹配
- 面试题场景：用户问"HashMap 的 put 方法"，向量检索可能返回"ConcurrentHashMap 的 put"（语义相近但不对），BM25 能精确命中"HashMap put"

**实现**：
```
用户提问
  ├── VectorRetriever.search(embedding) → Top-20 语义相似 Chunk（Milvus）
  ├── BM25Retriever.search(keywords)    → Top-20 关键词匹配 Chunk（MySQL 全文索引）
  └── RRF 融合（Reciprocal Rank Fusion）→ 合并去重排序 → Top-10
```

**RRF 公式**：`score(d) = Σ 1/(k + rank_i(d))`，k=60

**面试讲点**：向量 vs BM25 的互补性，RRF 为什么比简单加权好（无需调权重超参）

#### 5.5.2 重排序（RerankService）

```
Top-10 候选 Chunk → Reranker 模型精排 → Top-5 注入 Prompt
```

**为什么需要 Rerank？**
- 召回阶段用 Bi-Encoder（速度快但精度低）
- 精排阶段用 Cross-Encoder（逐对打分，精度高但慢）
- 两阶段架构：召回 100→10，精排 10→5

**技术选型**：DashScope Rerank API 或本地 BGE-Reranker-v2-m3

**面试讲点**：Bi-Encoder vs Cross-Encoder 的区别，为什么不能全用 Cross-Encoder（性能）

#### 5.5.3 RAG 评估（RagEvaluator）

**评测集**：构建 100 道面试题 + 标准答案的评测集

**评估指标**：
| 指标 | 含义 | 计算方式 |
|------|------|----------|
| Hit Rate@5 | Top-5 中是否包含正确答案 | 命中数/总数 |
| MRR | 第一个正确答案的平均排名倒数 | `1/rank` 的均值 |
| Faithfulness | 回答是否忠于检索到的上下文 | LLM 评判 |

**对比实验**（产出量化数据）：
```
配置 A: 纯向量检索        → Hit Rate@5 = 68%
配置 B: 混合召回           → Hit Rate@5 = 79%
配置 C: 混合召回 + Rerank  → Hit Rate@5 = 89%
```

**面试讲点**：有量化数据，"Rerank 让 Hit Rate@5 从 79% 提升到 89%"

#### 5.5.4 语义缓存（SemanticCache）

**场景**：用户问"HashMap 底层原理"和"HashMap 的实现原理"，语义相同但字面不同

**实现**：
```
用户提问 → Embedding → Redis 查相似向量（cosine > 0.95）→ 命中返回缓存 → 未命中调 LLM
```

**面试讲点**：语义缓存 vs 精确缓存的区别，相似度阈值如何选取（precision/recall trade-off）

#### 5.5.5 RAG 模块包结构

```
ai-service/rag/
├── DocumentIngestService.java       # 文档解析分块（已有）
├── VectorRetriever.java             # 向量检索（已有，Milvus）
├── BM25Retriever.java               # ★ 新增：BM25 关键词检索
├── HybridRetriever.java             # ★ 新增：RRF 融合
├── RerankService.java               # ★ 新增：重排序
├── RagEvaluator.java                # ★ 新增：评估指标
└── SemanticCache.java               # ★ 新增：语义缓存
```

#### 5.5.6 快速询问 Quick Ask（★ 2026-06-25 新增：RAG + MCP 联网混合）

> **场景**：用户在主页搜索框直接提问（像浏览器地址栏），不限于题库已有的题目。
> 题库有的 → RAG 检索精准答案；题库没有的 → MCP 联网搜索最新内容补充。

**与现有 `/rag/chat` 的区别**：

| 维度 | `/rag/chat`（已有） | `/rag/quick-ask`（新增） |
|------|---------------------|------------------------|
| 数据源 | 仅题库（Milvus + ES） | 题库 + MCP 联网搜索 |
| 适用场景 | 查题库已有面试题答案 | 题库没有的新技术/时效性问题 |
| 入口 | RAG 模块页面 | 主页搜索框（像浏览器） |
| 入库 | 不入库 | 用户可选入库（建立个人知识库） |
| 缓存 | 语义缓存 Redis | 语义缓存 Redis（同） |

**核心流程**：

```
用户在主页搜索框提问 POST /api/rag/quick-ask
  │
  ├── 1. 语义缓存查询（cosine > 0.95 → 命中直接返回）
  │
  ├── 2. RAG 检索题库（HybridRetriever 向量+BM25+RRF → Rerank）
  │      → 命中相关题目？ → 用题库精准内容（精益求精，权威）
  │
  ├── 3. MCP 联网搜索（仅当题库命中不足或用户问时效性内容时触发）
  │      → AI 判断是否需要联网（题库答案覆盖度 < 阈值 / 问"最新""2026""当前"等时效词）
  │      → 调用 MCP 工具（web_search_exa）搜索网页
  │      → 网页内容可能不准确，作为**补充**而非替代
  │
  ├── 4. 合并上下文：题库精准内容（高权重） + 联网搜索内容（低权重，标注来源）
  │      → 拼接 Prompt：题库资料在前 + 联网资料在后 + 标注数据来源
  │      → 通义千问生成回答（必须区分"题库答案"和"联网参考"）
  │
  ├── 5. 返回 QuickAskResponse
  │      → answer（综合回答）
  │      → sourceQuestions（命中的题库题目，引用溯源）
  │      → webSources（联网搜索的 URL 列表，标注"参考"）
  │      → cacheHit（是否命中缓存）
  │      → canSaveToKb（是否可入库，true=用户可选择将此答案存入个人知识库）
  │
  └── 6. 用户可选入库 POST /api/rag/save-to-kb
         → 用户得到答案后，选择"存入我的知识库"
         → 将 question + answer 向量化 → 写入 Milvus（用户私有 collection）
         → 也可选择不入库，仅查看
```

**MCP 联网搜索触发条件**（AI 判断 + 关键词规则兜底）：

```java
// 规则兜底：包含时效性关键词 → 触发 MCP 联网
if (containsKeyword(query, "最新", "2026", "当前", "现在", "最近", "新版")) {
    triggerWebSearch = true;
}
// RAG 命中不足 → 触发 MCP 联网
if (topDocs.size() < 3 || avgRelevanceScore < 0.6) {
    triggerWebSearch = true;
}
```

**为什么要 RAG + MCP 混合**：

| 问题 | 单 RAG | 单联网 | RAG + MCP 混合 |
|------|--------|--------|----------------|
| 题库有的题目 | ✅ 精准 | ❌ 网页可能不准 | ✅ 用题库权威答案 |
| 题库没有的新技术 | ❌ 答不了 | ✅ 能查到 | ✅ 联网补充 |
| 时效性问题 | ❌ 知识库过时 | ✅ 最新 | ✅ 联网最新 + 题库背景 |

**面试讲点**：
- "题库内容是精益求精的权威答案，联网内容可能不准但能覆盖时效性问题，两者混合取长补短"
- "MCP 工具调用是 AI 主动判断的，不是每次都联网——题库有答案就不联网，省成本"
- "用户可以选择将答案入库，建立个人知识库，下次再问直接命中"

**新增 API**：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rag/quick-ask` | 快速询问（RAG + MCP 混合） |
| POST | `/api/rag/save-to-kb` | 用户选择将答案存入个人知识库 |

### 5.6 基础设施模块

#### 5.6.1 统一响应与异常处理

| 组件 | 实现 | 说明 |
|------|------|------|
| 统一响应体 | `BaseResponse<T>` | code(0=成功) + message + data |
| 全局异常 | `GlobalExceptionHandler` | `@RestControllerAdvice` + `@ExceptionHandler` |
| 业务异常 | `BusinessException` | 携带 ErrorCode |

#### 5.6.2 用户认证体系（JWT 双 Token）

```java
// 认证流程
用户登录 → 生成 accessToken(2h) + refreshToken(7d) → Redis 存储
请求接口 → JwtInterceptor 解析 token → Redis 校验状态匹配
token 过期 → 调用 /user/refresh 用 refreshToken 换新 token
登出 → 删除 Redis token
```

**核心实现**：
- `JwtUtil`：token 生成/解析/校验，支持双 token 类型识别
- `JwtInterceptor`：拦截器统一认证 + URL 白名单放行 + Sentinel 上下文注入
- Redis 存储：`access:{userId}` 键存储当前有效 accessToken，支持强制下线

#### 5.6.3 Sentinel 流量控制

```java
// 流控策略
ContextUtil.enter(path, origin)  // 注入用户上下文
SphU.entry(path)                  // 按路径限流
origin = "anonymous" / "user:{id}" // 匿名与登录用户差异化限流
```

**核心特性**：
- 维度：HTTP 路径 + 用户 origin 双维度
- ThreadLocal 存储 Sentinel Entry，`afterCompletion` 时清理防泄漏
- 限流返回："请求过于频繁，请稍后重试"

#### 5.6.4 其他基础设施

| 组件 | 实现 | 说明 |
|------|------|------|
| CORS | `CorsConfig` | 跨域配置 |
| MyBatis-Plus | `MybatisPlusConfig` | 分页插件 + 逻辑删除 + 自动填充 |
| Redis 缓存 | `CacheConfig` | Caffeine 本地缓存 + Redis 分布式缓存 |
| 自动填充 | `MyMetaObjectHandler` | createTime/updateTime 自动注入 |

### 5.7 前沿技术增强（★ 2026 最新，基于全网调研）

> 以下 6 个方向基于 2026 年最新技术调研（Google Agentic RAG / Spring AI 2.0 / ACL 2026 论文 / GitHub 开源项目），
> 结合 interview-arena 场景筛选，按优先级排列。标注"可落地"的为建议实现，"面试讲点"的为了简历叙事。

#### 5.7.1 Spring AI 三层记忆压缩（P0，可落地）

**来源**：Spring AI 2.0-RC1（2026-06）+ JAVAPRO 生产实践

当前 Agent 模块用 Redis RList 手动管理对话历史。Spring AI 官方推荐三层记忆压缩：

| 层 | Advisor | 作用 | 对应现有 |
|----|---------|------|---------|
| 第一层 | `MessageChatMemoryAdvisor` | 滑动窗口保留最近 20 条（会话级） | 替代 Redis RList 手动管理 |
| 第二层 | `PromptChatMemoryAdvisor`（已 deprecated，用 SummaryAdvisor） | AI 生成对话摘要注入 Prompt（节省 token） | 新增 |
| 第三层 | `VectorStoreChatMemoryAdvisor` | 对话历史向量化存 Milvus，语义检索历史 | 新增 |

**Spring AI 2.0 关键变更**：
- `MessageWindowChatMemory` 新增 turn-boundary snapping（RC1），不会截断半个对话轮次
- `PromptChatMemoryAdvisor` 已 deprecated，推荐用 summary 压缩替代
- Chat Memory advisors 需要显式 conversation ID（1.1.6 起强制）

**落地方式**：
```java
@Bean
ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory, VectorStore vectorStore) {
    return builder
        .defaultAdvisors(
            MessageChatMemoryAdvisor.builder(chatMemory).build(),           // 第一层
            // SummaryChatMemoryAdvisor（第二层，长面试摘要压缩）
            VectorStoreChatMemoryAdvisor.builder(vectorStore).build()       // 第三层
        )
        .build();
}
```

**面试讲点**："三层记忆压缩：滑动窗口管近期上下文，摘要压缩保长期连贯性，向量存储支持跨会话语义检索"

#### 5.7.2 RAG Gap Detection — 跨会话薄弱点发现（P0，可落地）

**来源**：GitHub Friday 项目（2026-02），5-Agent 面试模拟器

Friday 的 Followup Agent 把每条用户回答向量化存 pgvector，用语义相似度检索历史回答，发现反复出现的薄弱点。

**落地方式**：每次用户回答后，向量化存 Milvus，检索历史相似回答：
```java
public void detectRecurringGaps(Long userId, String answer, int mastery) {
    // 1. 当前回答向量化
    float[] embedding = embeddingModel.embed(answer);
    // 2. 检索该用户历史回答中语义相似的（跨面试会话）
    List<Record> similar = milvusService.searchUserHistory(userId, embedding, topK=5);
    // 3. 多次同知识点低分 → 标记顽固薄弱点
    if (similar.stream().filter(r -> r.getMastery() < 60).count() >= 2) {
        knowledgeProfileService.markPersistent(userId, extractTopic(answer));
    }
}
```

**面试讲点**："不只是记住用户哪道题错，而是通过向量检索发现跨会话的重复薄弱点——两次面试都在 volatile 上卡住，系统自动标记为顽固薄弱点并优先考察"

#### 5.7.3 Agentic RAG — 迭代检索直到够用（P1，可落地）

**来源**：Google Gemini Enterprise Agent Platform Agentic RAG（2026）

传统 RAG 检索一次就生成。Google 的 Agentic RAG 引入 "sufficient context" 机制：检索后判断信息是否足够，不够继续迭代检索，准确率提升 34%。

**落地方式**：
```java
public String agenticRag(String query) {
    int maxRounds = 3;
    List<Document> context = new ArrayList<>();
    for (int i = 0; i < maxRounds; i++) {
        List<Document> batch = vectorStore.similaritySearch(
            SearchRequest.query(query).withTopK(5));
        context.addAll(batch);
        if (isContextSufficient(query, context)) break;  // LLM 判断够不够
        query = rewriteQuery(query, context);             // 不够则重写查询
    }
    return chatClient.prompt().user(query).context(context).call().content();
}
```

**面试讲点**："借鉴 Google Agentic RAG，面试问答不是一次检索就回答，而是判断检索结果是否充分，不充分则重写查询迭代检索，最多 3 轮"

#### 5.7.4 RetrievalAugmentationAdvisor — 模块化 RAG（P1，可落地）

**来源**：Spring AI 2.0-RC1（2026-06）官方文档

Spring AI 2.0 提供模块化 RAG Advisor，比 `QuestionAnswerAdvisor` 更灵活，支持查询重写 + 自定义检索 + 后处理（Rerank）：

```java
RetrievalAugmentationAdvisor.builder()
    .queryTransformer(QueryTransformer.builder()           // 查询重写（类似 MQE）
        .chatClientBuilder(chatClientBuilder).build())
    .documentRetriever(VectorStoreDocumentRetriever.builder()  // 检索
        .vectorStore(vectorStore).topK(10).build())
    .documentPostProcessor(DocumentPostProcessor.builder()     // Rerank 后处理
        .chatClientBuilder(chatClientBuilder).build())
    .build();
```

**面试讲点**："用 Spring AI 2.0 的模块化 RAG Advisor，查询重写、检索、重排序都是可插拔模块，符合开闭原则"

#### 5.7.5 多策略记忆检索 + RRF 融合（P2，可落地）

**来源**：Engram（2026）+ MAGMA（ACL 2026）

记忆检索不用单路 SQL，而是四路并行 + RRF 融合：
```java
public List<Memory> retrieveMemories(Long userId, String query) {
    List<Memory> semantic = milvusService.searchSemantic(userId, query);  // 语义
    List<Memory> keyword = mysqlService.searchKeyword(userId, query);     // 关键词
    List<Memory> recent  = mysqlService.searchRecent(userId, 7);          // 最近7天
    List<Memory> weak    = mysqlService.searchWeakPoints(userId);         // 薄弱点
    return rrfFusion(Arrays.asList(semantic, keyword, recent, weak), 5);
}
```

**面试讲点**："记忆检索四路并行（语义+关键词+时间+重要性）+ RRF 融合，确保既检索到相关历史又覆盖薄弱点"

#### 5.7.6 面试记忆驱动学习路径（P2，可落地）

**来源**：BUDDY（2026）+ LinkedIn Hiring Agent HLTM（2026）

面试结束后，基于语义记忆中的薄弱点，从题库检索相关题目，生成个性化学习路径（激活 V2 已规划的 `learning_path` 表）：
```java
public LearningPath generatePath(Long userId) {
    List<KnowledgeWeakness> weakPoints = profile.getWeakPoints();
    Map<String, List<Question>> plan = new LinkedHashMap<>();
    for (KnowledgeWeakness w : weakPoints) {
        plan.put(w.getTopic(), ragService.hybridSearch(w.getTopic(), 5));
    }
    return new LearningPath(userId, plan, estimatedWeeks(weakPoints.size()));
}
```

**面试讲点**："面试→发现薄弱→检索相关题目→生成学习路径→再面试验证，形成闭环"

#### 5.7.7 技术增强优先级总览

| 优先级 | 功能 | 难度 | 面试加分 | 所属模块 |
|--------|------|------|---------|---------|
| **P0** | Spring AI 三层记忆压缩 | 中 | ⭐⭐⭐ | Agent 记忆 |
| **P0** | RAG Gap Detection | 中 | ⭐⭐⭐⭐ | Agent 记忆 + RAG |
| **P1** | Agentic RAG 迭代检索 | 高 | ⭐⭐⭐ | RAG |
| **P1** | RetrievalAugmentationAdvisor | 低 | ⭐⭐ | RAG |
| **P2** | 多策略记忆检索+RRF | 高 | ⭐⭐ | Agent 记忆 |
| **P2** | 学习路径生成 | 中 | ⭐⭐⭐ | 闭环体验 |

---

## 六、API 接口清单

### 6.1 用户接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/user/register` | 用户注册 |
| POST | `/api/user/login` | 用户登录 |
| POST | `/api/user/logout` | 用户登出 |
| GET | `/api/user/current` | 获取当前登录用户 |
| GET | `/api/user/login/wx_open/app_id` | 获取微信 AppId |
| GET | `/api/user/login/wx_open` | 微信登录 |
| POST | `/api/user/add` | 添加用户(admin) |
| POST | `/api/user/update` | 更新用户(admin) |
| POST | `/api/user/delete` | 删除用户(admin) |
| POST | `/api/user/list/page` | 分页查询(admin) |
| POST | `/api/user/my/list/page` | 我的列表 |
| POST | `/api/user/update/my` | 更新个人信息 |

### 6.2 题库接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/questionBank/add` | 创建题库 |
| POST | `/api/questionBank/update` | 更新题库 |
| POST | `/api/questionBank/delete` | 删除题库 |
| GET | `/api/questionBank/get/vo` | 获取题库详情 |
| POST | `/api/questionBank/list/page/vo` | 分页查询题库 |
| POST | `/api/questionBank/my/list/page/vo` | 我的题库 |

### 6.3 题目接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/question/add` | 创建题目 |
| POST | `/api/question/update` | 更新题目 |
| POST | `/api/question/delete` | 删除题目 |
| POST | `/api/question/delete/batch` | 批量删除 |
| GET | `/api/question/get/vo` | 获取题目详情 |
| POST | `/api/question/list/page/vo` | 分页查询 |
| POST | `/api/question/my/list/page/vo` | 我的题目 |
| POST | `/api/question/search/page/vo` | ES 搜索 |

### 6.4 判题接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/judge/submit` | 提交代码 |
| GET | `/api/judge/result/{id}` | 查询判题结果 |
| POST | `/api/testCase/add` | 添加测试用例 |
| POST | `/api/testCase/update` | 更新测试用例 |
| POST | `/api/testCase/delete` | 删除测试用例 |

### 6.5 AI 面试接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/interview/start` | 开始面试(mode + bankId) |
| POST | `/api/interview/answer` | 提交回答(sessionId + answer) |

---

## 七、后端代码包结构

```
com.charles.interview.arena
├── MainApplication.java          ← 启动类
├── annotation/
│   └── AuthCheck.java            ← 权限校验注解
├── aop/
│   ├── AuthInterceptor.java      ← 权限拦截器
│   └── LogInterceptor.java       ← 日志拦截器
├── blackfilter/
│   ├── BlackIpFilter.java        ← IP 黑名单过滤器
│   ├── BlackIpUtils.java
│   └── NacosListener.java        ← Nacos 动态配置监听
├── common/
│   ├── BaseResponse.java         ← 统一响应体
│   ├── ErrorCode.java            ← 错误码枚举
│   ├── ResultUtils.java          ← 响应工具类
│   ├── DeleteRequest.java
│   └── PageRequest.java          ← 分页请求基类
├── config/
│   ├── CorsConfig.java
│   ├── HotKeyConfig.java         ← JD HotKey 配置
│   ├── InterviewPromptConstants.java  ← AI 面试 Prompt 常量
│   ├── JsonConfig.java
│   ├── MyBatisPlusConfig.java
│   ├── RedissonConfig.java
│   ├── SentryUserFilter.java
│   └── WxOpenConfig.java         ← 微信配置
├── constant/
│   ├── CommonConstant.java
│   ├── InterviewRedisConstants.java  ← 面试 Redis Key 前缀
│   ├── RedisConstant.java
│   └── UserConstant.java
├── controller/                   ← 7 个 Controller
│   ├── UserController.java
│   ├── QuestionBankController.java
│   ├── QuestionController.java
│   ├── QuestionBankQuestionController.java
│   ├── JudgeController.java
│   ├── TestCaseController.java
│   └── InterviewController.java
├── exception/
│   ├── BusinessException.java
│   ├── GlobalExceptionHandler.java
│   └── ThrowUtils.java
├── judge/
│   └── codesandbox/
│       ├── CodeSandbox.java      ← 判题沙箱接口
│       └── impl/
│           └── SimpleCodeSandbox.java  ← 本地进程沙箱(不安全)
├── manager/
│   └── CounterManager.java       ← 计数器管理
├── mapper/                       ← 10 个 Mapper
│   ├── UserMapper.java
│   ├── QuestionMapper.java
│   ├── QuestionBankMapper.java
│   ├── QuestionBankQuestionMapper.java
│   ├── TestCaseMapper.java
│   ├── SubmissionMapper.java
│   ├── JudgeResultMapper.java
│   ├── ProgrammingLanguageMapper.java
│   ├── InterviewSessionMapper.java
│   └── InterviewRecordMapper.java
├── model/
│   ├── entity/                   ← 10 个实体类
│   ├── dto/                      ← 请求 DTO(按模块分包)
│   │   ├── user/
│   │   ├── question/
│   │   ├── questionBank/
│   │   ├── questionBankQuestion/
│   │   ├── interview/
│   │   ├── judge/
│   │   └── file/
│   ├── vo/                       ← 响应 VO
│   └── enums/                    ← 枚举
│       ├── ActionDirectiveEnum.java   ← DEEP_DIVE/NEXT_QUESTION/END_INTERVIEW
│       ├── InterviewModeEnum.java     ← SPECIFIED_BANK/RANDOM_BIG_TECH
│       ├── JudgeVerdictEnum.java
│       ├── QuestionDifficultyEnum.java
│       ├── QuestionTypeEnum.java
│       ├── UserRoleEnum.java
│       └── FileUploadBizEnum.java
├── mq/
│   └── InterviewReportProducer.java  ← RabbitMQ 面试报告生产者
├── satoken/
│   ├── SaTokenConfigure.java
│   ├── StpInterfaceImpl.java     ← Sa-Token 权限实现
│   └── DeviceUtils.java
├── sentinel/
│   ├── SentinelConstant.java
│   ├── SentinelRulesManager.java
│   └── SentinelTest.java
├── service/                      ← 11 个 Service
│   ├── UserService.java
│   ├── QuestionService.java
│   ├── QuestionBankService.java
│   ├── QuestionBankQuestionService.java
│   ├── JudgeService.java
│   ├── TestCaseService.java
│   ├── SubmissionService.java
│   ├── JudgeResultService.java
│   ├── ProgrammingLanguageService.java
│   ├── InterviewService.java              ← 面试核心逻辑
│   ├── AiInterviewStrategyService.java    ← Spring AI 调用
│   └── impl/                              ← 实现类
└── utils/
    ├── DatabaseConnector.java
    ├── NetUtils.java
    ├── SpringContextUtils.java
    └── SqlUtils.java
```

---

## 八、配置说明

### application.yml 核心配置

```yaml
spring:
  application:
    name: interview-arena-backend
  profiles:
    active: dev
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/interview_arena
    type: com.alibaba.druid.pool.DruidDataSource
    druid:
      initial-size: 20
      minIdle: 20
      max-active: 200
  redis:
    database: 1
    host: localhost
    port: 6379
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen-plus
          temperature: 0.7
  elasticsearch:
    uris: http://localhost:9200

server:
  port: 8101
  servlet:
    context-path: /api

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: false
  global-config:
    db-config:
      logic-delete-field: isDelete
      logic-delete-value: 1
      logic-not-delete-value: 0

sa-token:
  token-name: interview-arena
  timeout: 2592000
  is-concurrent: false
  is-share: true
  token-style: uuid
```

### 开发规范

- 后端: Controller → Service → Mapper 三层架构,严格 DTO/VO 分离
- 统一响应: `BaseResponse<T>` with code(0=success) + message + data
- 逻辑删除: `isDelete` 字段(1=deleted, 0=active)
- 权限: `@AuthCheck(mustRole = "admin")` 注解
- AI Prompt: 所有 Prompt 模板集中管理(InterviewPromptConstants)
- 数据库: Flyway 版本化迁移,V2 改用命名 `V{N}__{description}.sql`

---

## 九、V2 重构计划(11 个模块)

| 模块 | 内容 | 核心变更 | 状态 |
|------|------|----------|------|
| M1 基础架构 | 项目骨架 + 用户系统 | Java 21 + Boot 3.4 + groupId 修正 | ⬜ |
| M2 题库管理 | 题库/题目 CRUD + ES 搜索 | 沿用现有,代码规范化 | ⬜ |
| M3 在线判题 | Docker 沙箱 + 多语言 | 替换 SimpleCodeSandbox | ⬜ |
| M4 AI 评分 | Spring AI 答案评分 + SSE | 新增 | ⬜ |
| M5 AI 面试 | 多轮对话 + 状态机 | 沿用现有,升级 Spring AI 1.1.x | ⬜ |
| M6 AI 报告 | 综合评估 + 雷达图 | 新增 | ⬜ |
| M7 简历预测 | 简历解析 + AI 出题 | 新增 | ⬜ |
| M8 AI 编程辅助 | 代码审查 + 优化建议 | 新增 | ⬜ |
| M9 组卷考试 | 智能组卷 + 限时考试 | 新增 | ⬜ |
| M10 社区学习 | 讨论区 + 笔记 + 学习路径 | 新增 | ⬜ |
| M11 部署优化 | Docker Compose + 测试 | 新增 | ⬜ |

---

## 十、下一步开发任务清单

### 第一优先级:Phase 1.1 基础重构

| 任务 | 说明 |
|------|------|
| [ ] 修正 pom.xml | groupId → com.charles, Java → 21, Boot → 3.4.x |
| [ ] 升级 Spring AI | 删除旧 1.0.0-M3.1,后续引入 1.1.x |
| [ ] 代码规范化 | 包结构整理,删除无用代码 |
| [ ] 补测试 | UserService 核心方法单元测试 |
| [ ] Docker Compose | MySQL + Redis + ES 一键启动 |

### 第二优先级:Phase 1.2 ai-service 骨架

| 任务 | 说明 |
|------|------|
| [ ] 新建 ai-service 包 | com.charles.interview.arena.ai |
| [ ] Spring AI Alibaba 1.1.x 依赖 | pom.xml 引入 |
| [ ] DashScope 配置 | application.yml 通义千问配置 |
| [ ] ChatClient 对话跑通 | 基础对话 API |
| [ ] SSE 流式响应 | Flux<ServerSentEvent> |

### 第三优先级:Phase 1.3 RAG 模块

| 任务 | 说明 |
|------|------|
| [ ] PgVector 向量库 | PostgreSQL + pgvector 扩展 |
| [ ] Embedding 服务 | DashScope text-embedding-v2 |
| [ ] 文档入库 | 面试题 → 分块 → Embedding → PgVector |
| [ ] RAG 检索 | 向量相似度搜索 + 上下文注入 |
| [ ] QuestionAnswerAdvisor | Spring AI RAG Advisor |

### 后续模块

| 优先级 | 模块 | 说明 |
|--------|------|------|
| P1 | Agent 模块 | 多 Agent 架构（见 §5.8） |
| P1 | MCP 模块 | MCP Server 暴露工具 |

---

## 5.8 Agent 模块架构设计（2026-06-27 新增）

> 参考：Google Agentic RAG、CoMAI 多 Agent 面试论文、Spring AI Alibaba 多 Agent 编排

### 5.8.1 整体架构：Multi-Agent + Agentic RAG

```
                        用户请求
                           │
                    ┌──────▼──────┐
                    │ LlmRouting  │  ← 意图路由 Agent
                    │   Agent     │
                    └──┬───┬───┬──┘
                       │   │   │
            ┌──────────┘   │   └──────────┐
            ▼              ▼              ▼
     ┌──────────────┐ ┌──────────┐ ┌──────────────┐
     │  RAG Agent   │ │ Interview│ │  Practice    │
     │ (知识问答)    │ │  Agent   │ │  Agent       │
     │              │ │ (面试)   │ │ (刷题)        │
     │ 工具:        │ │          │ │              │
     │ ├searchLocal │ │ 工具:    │ │ 工具:        │
     │ ├searchWeb   │ │ ├pickQ   │ │ ├submitCode  │
     │ └gradeResult │ │ ├evaluate│ │ └getHint     │
     └──────────────┘ │ └saveRecord│ └──────────────┘
                      └──────────┘
```

### 5.8.2 Spring AI Alibaba 4 种编排模式

| 编排模式 | 类名 | 用途 | 本项目场景 |
|---------|------|------|-----------|
| 意图路由 | `LlmRoutingAgent` | Router LLM 决定分发到哪个子 Agent | 用户意图判断 |
| 顺序执行 | `SequentialAgent` | Agent 按固定顺序执行，输出串联 | 面试流程：抽题→回答→评分 |
| 并行执行 | `ParallelAgent` | 多个 Agent 同时执行，结果合并 | searchLocal + searchWeb 并行 |
| 循环执行 | `LoopAgent` | 重复执行直到条件满足 | 追问循环：评分不够继续问 |

### 5.8.3 Agent 工具清单

| Agent | 工具 | 说明 | 复用现有代码 |
|-------|------|------|------------|
| RAG Agent | `searchLocal` | 搜本地知识库（Milvus+ES） | 复用 HybridRetriever |
| RAG Agent | `searchWeb` | 搜网络最新资料 | 新建（Tavily API） |
| Interview Agent | `pickQuestion` | 从题库抽题 | 复用 QuestionService |
| Interview Agent | `evaluateAnswer` | 评估用户回答 | 新建 |
| Interview Agent | `saveRecord` | 保存面试记录 | 复用 InterviewRecordService |
| Practice Agent | `submitCode` | 提交代码判题 | 复用 JudgeService |
| Practice Agent | `getHint` | 获取提示 | 新建 |

### 5.8.4 开发路线（7 步，最小颗粒度）

| Step | 做什么 | 学什么 | 编排模式 | 对应八股 |
|------|--------|--------|---------|---------|
| 1 | ReactAgent 最简骨架 | ChatModel + builder | 无 | 2.1 Models |
| 2 | System Prompt | 面试官人设 | 无 | 2.3 Prompts |
| 3 | @Tool searchLocal | 工具定义 | 无 | 2.4 Tools |
| 4 | @Tool searchWeb | 网络搜索 | 无 | 2.4 Tools |
| 5 | MemorySaver | 多轮记忆 | 无 | 2.6 Memory |
| 6 | 结构化输出 | 返回 DTO | 无 | 2.9 Structured Output |
| 7 | LlmRoutingAgent | 意图路由 | RoutingAgent | 多 Agent |

### 5.8.5 后续进阶

| Step | 做什么 | 编排模式 |
|------|--------|---------|
| 8 | 面试流程 | SequentialAgent |
| 9 | 本地+网络并行搜 | ParallelAgent |
| 10 | 追问循环 | LoopAgent |
| 11 | Hooks 日志+脱敏 | Hooks |
| 12 | 三层记忆 | Context Engineering |

### 5.8.6 项目文件结构

```
backend/src/main/java/com/charles/interview/arena/
├── config/
│   ├── RagConfig.java          ← 已有（ChatClient）
│   └── AgentConfig.java        ← 新建（Agent Bean 配置）
├── agent/
│   ├── controller/
│   │   └── AgentController.java    ← API 入口
│   ├── service/
│   │   └── AgentService.java       ← Agent 调用入口
│   └── tool/
│       ├── SearchLocalTool.java    ← 搜本地知识库
│       ├── SearchWebTool.java      ← 搜网络
│       ├── PickQuestionTool.java   ← 抽面试题
│       └── EvaluateTool.java       ← 评分
└── rag/                            ← 已有（传统 RAG）
```
