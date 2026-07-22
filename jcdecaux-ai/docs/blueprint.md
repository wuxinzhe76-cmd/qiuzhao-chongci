# 德高户外广告 AI 智能决策系统 · 项目蓝图

> 项目代号：**jcdecaux-ai**（德高户外广告 AI 智能决策与多模态生成系统）
> 工程目录：`6-MyProject/jcdecaux-ai/`
> 文档目录：`6-MyProject/jcdecaux-ai/docs/`
> 本文档是德高 AI 项目的完整实现蓝图，包含技术架构、模块设计、每日任务拆解。
> 阅读本文档即可了解项目全貌、技术细节、开发计划及部署方案。

---

## 一、项目定位与目标

### 1.1 项目背景

德高（JCDecaux）户外广告业务覆盖快消、美妆、金融、旅游、汽车等 11 个核心行业，约 2000 家企业客户。销售团队在日常拓客中面临三大痛点：

1. **客户优先级难判断**：2000+ 客户中，谁下个月更可能释放户外预算？依据是什么？
2. **提案准备成本高**：首次见客户时，缺乏直观素材展示"广告投出来长什么样"，依赖历史案例，转化效率低。
3. **决策缺乏数据支撑**：销售凭经验判断"该不该跟"，缺少"预算节奏 + 行业周期 + 实时信号"的结构化归因。

### 1.2 项目目标

构建一套 **"数据驱动 + AI 赋能"** 的智能决策系统，实现：

- ✅ **决策层**：基于 GraphRAG 的知识检索与推理，输出客户/行业优先级评分 + 可解释归因
- ✅ **执行层**：一键生成"客户定制 + 城市地标 + 动态视频"的提案素材，2 分钟出片
- ✅ **智能层**：Agent 自主编排多路工具（图谱检索 + 地图数据 + 实时舆情 + 视频生成），融合历史规律与实时信号

### 1.3 核心差异化

| 维度 | 传统 RAG 方案 | 本项目 GraphRAG 方案 |
|------|--------------|---------------------|
| 检索方式 | 向量相似度匹配 | 图遍历 + 向量召回双路融合 |
| 推理能力 | 单跳检索，无法多跳推理 | 支持多跳推理（品牌→城市→坪效→历史案例） |
| 上下文理解 | 语义相近但业务无关 | 保留业务逻辑关联（如"扩张期品牌优先跟进"） |
| 检索延迟 | 全图遍历，延迟高 | Leiden 社区摘要预加载，延迟下降 60% |

---

## 二、技术选型

### 2.1 核心技术栈

| 层 | 技术 | 版本 | 选型理由 |
|----|------|------|----------|
| **主语言** | Python | 3.10+ | 与 interview-arena（Java）形成双语言经历，AI 生态更成熟 |
| **Web 框架** | FastAPI | 0.110+ | 异步高性能，原生支持 SSE 流式输出 |
| **AI 框架** | LlamaIndex | 0.10+ | 模块化好，可定制 GraphRAG pipeline |
| **文本大模型** | 豆包 2.0 | 火山引擎 | 德高与火山引擎有合作，Function Calling 能力强 |
| **图像生成** | 即梦 AI 绘画 | 火山引擎 | 统一火山引擎生态，支持文生图/图生图 |
| **视频生成** | 即梦视频 | 火山引擎 | 图生视频，2 分钟出片 |
| **图数据库** | Neo4j | 5.x 社区版 | 业界标准，Cypher 查询语言适合多跳推理 |
| **向量库** | Milvus Standalone | 2.3+ | 高性能，Docker 单机部署 |
| **关系数据库** | MySQL | 8.0 | 存储业务数据、用户信息 |
| **缓存** | Redis | 7.x | 会话缓存、语义缓存 |
| **MCP 协议** | FastMCP | 0.30+ | 统一工具调用协议，Agent 动态编排 |

### 2.2 MCP 工具清单

| MCP Server | 功能 | 数据源 | 调用场景 |
|-----------|------|--------|---------|
| **graphrag_search** | 检索本地知识图谱 | Neo4j + Milvus | "XX 茶饮最近投放趋势" |
| **amap_search** | 查地铁口 POI、商圈、人流 | 高德地图 API | "徐家汇地铁站周边有什么商场" |
| **bocha_search** | 实时舆情、官媒新闻 | 博查 API | "XX 品牌最近有什么新闻" |
| **jimeng_image** | 生成广告概念图 | 即梦 AI 绘画 API | "生成一张地铁广告概念图" |
| **jimeng_video** | 生成地铁广告视频 | 即梦视频 API | "生成一段地铁广告视频" |
| **similar_case_search** | 检索相似历史案例 | Neo4j 图查询 | "有没有类似的茶饮品牌投放案例" |

---

## 三、系统架构

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         用户层（CLI/API）                        │
│                    销售对话窗口 / API 调用                        │
└────────────────────────────┬────────────────────────────────────┘
                             │
                ┌────────────▼────────────┐
                │   Agent 调度中枢         │
                │   (豆包 2.0 + MCP)       │
                │                         │
                │   • 意图识别             │
                │   • 工具路由             │
                │   • 冲突消解             │
                │   • 流式输出             │
                └────────────┬────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
        ▼                    ▼                    ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ 决策引擎      │    │ 执行引擎      │    │ 融合中枢      │
│ GraphRAG     │    │ 视频生成      │    │ MCP 工具集    │
│              │    │              │    │              │
│ • Neo4j      │    │ • 即梦 AI    │    │ • 高德地图    │
│ • Milvus     │    │ • 即梦视频    │    │ • 博查舆情    │
│ • LlamaIndex │    │ • OpenCV     │    │ • 案例检索    │
└──────────────┘    └──────────────┘    └──────────────┘
        │                    │                    │
        └────────────────────┼────────────────────┘
                             │
                ┌────────────▼────────────┐
                │   数据存储层             │
                │                         │
                │   • MySQL（业务数据）     │
                │   • Redis（会话缓存）     │
                │   • Neo4j（知识图谱）     │
                │   • Milvus（向量索引）    │
                └─────────────────────────┘
```

### 3.2 核心流程

#### 决策引擎流程（GraphRAG）

```
用户提问："徐家汇地铁站适合投 XX 茶饮的广告吗？"
  │
  ├── Step 1: Agent 意图识别 → 需要检索历史投放 + 地铁口数据 + 实时舆情
  │
  ├── Step 2: 并行调用 MCP 工具
  │   ├── graphrag_search("XX 茶饮", "上海", "投放趋势")
  │   │   → Neo4j 图遍历：Brand → City → Campaign → StoreMetric
  │   │   → Milvus 向量召回：语义相似的历史案例
  │   │   → 融合结果：XX 茶饮近 3 月杭州门店 +12 家，坪效 +3%
  │   │
  │   ├── amap_search("徐家汇地铁站", "周边商圈", "人流")
  │   │   → 高德 API：地铁口 POI、周边商场、日均人流
  │   │   → 结果：徐家汇地铁站日均人流 50 万，周边 3 个大型商场
  │   │
  │   └── bocha_search("XX 茶饮", "最新新闻")
  │       → 博查 API：官媒新闻、公告
  │       → 结果：今日新华社官宣 XX 茶饮签约顶流代言人
  │
  ├── Step 3: Agent 冲突消解
  │   • 历史规律：XX 茶饮处于扩张期，建议跟进
  │   • 实时信号：代言人官宣，预计 72 小时内释放预算
  │   • 融合结论：强烈建议跟进，置信度 92%
  │
  └── Step 4: 生成自然语言回复 + 推荐动作
      → "建议本周内触达 XX 茶饮，重点推徐家汇 + 商圈公交组合..."
```

#### 执行引擎流程（视频生成）

```
用户追问："能生成个广告效果图吗？"
  │
  ├── Step 1: Agent 意图识别 → 调用视频生成工具
  │
  ├── Step 2: 调用 jimeng_image MCP
  │   → 即梦 AI 绘画 API：生成广告概念图
  │   → 输入：品牌 Logo + 风格参数 + 地铁场景
  │   → 输出：3 版广告概念图供选择
  │
  ├── Step 3: 调用 jimeng_video MCP
  │   → 即梦视频 API：图生视频
  │   → 输入：概念图 + 运镜参数（跟随地铁、光影变化）
  │   → 输出：2 分钟地铁广告视频
  │
  └── Step 4: 返回视频 URL + 预览图
```

---

## 四、核心模块设计

### 4.1 决策引擎：GraphRAG 模块

#### 4.1.1 知识图谱设计

**节点（Node）类型**：

| 节点类型 | 属性 | 示例 |
|---------|------|------|
| **Brand** | name, industry, annual_revenue | XX 茶饮，快消，年销售额 10 亿 |
| **City** | name, tier, population | 上海，一线，2500 万 |
| **StoreMetric** | brand_id, city_id, store_count, avg_sales_per_store, date | XX 茶饮上海门店数 50，月均销售额 20 万 |
| **Campaign** | brand_id, city_id, location_type, start_date, duration, impression | XX 茶饮上海地铁广告，2024-05，1 个月，曝光 500 万 |
| **Report** | title, industry, publish_date, content_vector | 《2024 快消行业户外投放趋势报告》 |
| **Industry** | name, key_brands, growth_rate | 快消，[XX 茶饮, YY 零食]，+5% |

**边（Edge/关系）类型**：

| 关系类型 | 起点 → 终点 | 属性 | 示例 |
|---------|------------|------|------|
| **OPERATES_IN** | Brand → City | market_share | XX 茶饮 → 上海，市场份额 15% |
| **HAS_METRIC** | Brand → StoreMetric | date | XX 茶饮 → 门店指标，2024-04 |
| **LAUNCHED** | Brand → Campaign | budget | XX 茶饮 → 上海地铁广告，预算 100 万 |
| **SIMILAR_TO** | Campaign → Campaign | similarity_score | 茶饮案例 A → 茶饮案例 B，相似度 0.85 |
| **BELONGS_TO** | Brand → Industry | sub_category | XX 茶饮 → 快消，奶茶细分 |
| **LOCATED_IN** | Campaign → City | specific_location | 上海地铁广告 → 徐家汇站 |

#### 4.1.2 GraphRAG 检索流程

```python
# 伪代码：GraphRAG 检索流程
def graphrag_search(query: str, brand: str, city: str):
    # Step 1: 向量召回（语义相似的历史案例）
    vector_results = milvus_client.search(
        collection="campaigns",
        query_embedding=embed(query),
        top_k=20
    )
    
    # Step 2: 图遍历（多跳推理）
    cypher_query = """
    MATCH (b:Brand {name: $brand})-[:OPERATES_IN]->(c:City {name: $city})
    MATCH (b)-[:HAS_METRIC]->(m:StoreMetric)
    MATCH (b)-[:LAUNCHED]->(camp:Campaign)-[:LOCATED_IN]->(c)
    RETURN m.store_count, m.avg_sales_per_store, camp.impression
    ORDER BY m.date DESC LIMIT 3
    """
    graph_results = neo4j_client.run(cypher_query, brand=brand, city=city)
    
    # Step 3: 融合两路结果
    context = merge_results(vector_results, graph_results)
    
    # Step 4: 送给大模型生成回答
    response = doubao_client.chat(
        system_prompt="你是户外广告决策助手...",
        user_prompt=f"基于以下信息回答问题：{context}\n\n问题：{query}"
    )
    
    return response
```

#### 4.1.3 Leiden 社区发现（优化检索性能）

```python
# 伪代码：Leiden 社区发现 + 社区摘要预加载
def build_community_summaries():
    # Step 1: 从 Neo4j 加载全图
    graph = neo4j_client.get_full_graph()
    
    # Step 2: 运行 Leiden 算法分层聚类
    communities = leiden_algorithm(graph, resolution=1.0)
    
    # Step 3: 为每个社区生成摘要
    for community_id, nodes in communities.items():
        # 提取社区内所有节点的文本描述
        texts = [node.description for node in nodes]
        
        # 调用大模型生成社区摘要
        summary = doubao_client.chat(
            system_prompt="总结以下户外广告案例的共同特征...",
            user_prompt="\n".join(texts)
        )
        
        # 存储到 MySQL
        mysql_client.insert(
            "community_summaries",
            community_id=community_id,
            summary=summary,
            node_count=len(nodes)
        )
    
    # Step 4: 检索时先定位社区，再精细检索
    # 检索延迟下降 60%
```

### 4.2 执行引擎：视频生成模块

#### 4.2.1 视频生成流程

```python
# 伪代码：视频生成工作流
async def generate_video(brand: str, city: str, style: str):
    # Step 1: 调用即梦 AI 绘画生成概念图
    image_response = await jimeng_image_client.generate(
        prompt=f"{brand} 地铁广告，{style}风格，{city}地标背景",
        negative_prompt="低质量，模糊",
        width=1024,
        height=576
    )
    concept_image = image_response.image_url
    
    # Step 2: 调用即梦视频生成动态视频
    video_response = await jimeng_video_client.generate(
        image_url=concept_image,
        prompt=f"镜头跟随地铁移动，光影变化，{city}城市氛围",
        duration=10  # 10 秒视频
    )
    video_url = video_response.video_url
    
    # Step 3: 返回结果
    return {
        "concept_image": concept_image,
        "video_url": video_url,
        "status": "completed"
    }
```

#### 4.2.2 异步任务管理

```python
# 伪代码：长耗时任务异步管理
class VideoTaskManager:
    def __init__(self):
        self.redis_client = redis.Redis()
        self.mysql_client = mysql.MySQL()
    
    async def submit_task(self, brand: str, city: str, style: str):
        # Step 1: 创建任务记录
        task_id = uuid.uuid4()
        self.mysql_client.insert(
            "video_tasks",
            task_id=task_id,
            brand=brand,
            city=city,
            style=style,
            status="PENDING",
            created_at=datetime.now()
        )
        
        # Step 2: 异步执行任务
        asyncio.create_task(self.execute_task(task_id))
        
        # Step 3: 返回任务 ID
        return task_id
    
    async def execute_task(self, task_id: str):
        # Step 1: 更新状态为 PROCESSING
        self.mysql_client.update(
            "video_tasks",
            task_id=task_id,
            status="PROCESSING"
        )
        
        try:
            # Step 2: 调用视频生成
            result = await generate_video(...)
            
            # Step 3: 更新状态为 COMPLETED
            self.mysql_client.update(
                "video_tasks",
                task_id=task_id,
                status="COMPLETED",
                video_url=result["video_url"],
                completed_at=datetime.now()
            )
            
            # Step 4: 设置 Redis TTL（7 天自动过期）
            self.redis_client.setex(
                f"video_task:{task_id}",
                7 * 24 * 3600,  # 7 天
                result["video_url"]
            )
            
        except Exception as e:
            # Step 5: 失败处理
            self.mysql_client.update(
                "video_tasks",
                task_id=task_id,
                status="FAILED",
                error_message=str(e)
            )
```

### 4.3 融合中枢：Agent 模块

#### 4.3.1 Agent 架构

```python
# 伪代码：Agent 调度中枢
class AgentOrchestrator:
    def __init__(self):
        self.doubao_client = DoubaoClient(model="doubao-2.0")
        self.mcp_tools = {
            "graphrag_search": GraphRAGTool(),
            "amap_search": AmapTool(),
            "bocha_search": BochaTool(),
            "jimeng_image": JimengImageTool(),
            "jimeng_video": JimengVideoTool(),
            "similar_case_search": SimilarCaseTool()
        }
    
    async def chat(self, user_message: str, session_id: str):
        # Step 1: 加载会话历史
        history = self.load_session_history(session_id)
        
        # Step 2: 构建 System Prompt
        system_prompt = """
        你是德高户外广告的智能决策助手。你可以调用以下工具：
        - graphrag_search: 检索历史投放案例和品牌数据
        - amap_search: 查询地铁口商圈、人流数据
        - bocha_search: 查询实时新闻舆情
        - jimeng_image: 生成广告概念图
        - jimeng_video: 生成广告视频
        - similar_case_search: 检索相似案例
        
        当用户询问客户优先级时，你需要：
        1. 调用 graphrag_search 检索历史投放数据
        2. 调用 amap_search 查询目标区域数据
        3. 调用 bocha_search 查询实时新闻
        4. 融合三路结果，输出结论 + 归因 + 推荐动作
        
        当用户要求生成视频时，调用 jimeng_video 工具。
        """
        
        # Step 3: 调用豆包 2.0（Function Calling）
        response = await self.doubao_client.chat(
            system_prompt=system_prompt,
            user_prompt=user_message,
            history=history,
            tools=list(self.mcp_tools.keys())
        )
        
        # Step 4: 处理工具调用
        while response.has_tool_call():
            tool_name = response.tool_name
            tool_args = response.tool_args
            
            # 调用对应的 MCP 工具
            tool_result = await self.mcp_tools[tool_name].execute(**tool_args)
            
            # 将工具结果返回给大模型
            response = await self.doubao_client.chat_with_tool_result(
                tool_result=tool_result
            )
        
        # Step 5: 返回最终回答
        return response.content
```

#### 4.3.2 冲突消解规则

```python
# 伪代码：冲突消解规则引擎
class ConflictResolver:
    def resolve(self, historical_signal: dict, realtime_signal: dict):
        """
        冲突消解规则：
        1. 实时高优信号 > 历史规律 → 覆盖历史结论
        2. 实时中优信号 + 历史规律 → 增强置信度
        3. 实时低优信号 → 仅补充信息
        """
        
        # 规则 1: 实时高优信号（如代言人官宣、融资）
        if realtime_signal.get("priority") == "HIGH":
            return {
                "conclusion": realtime_signal["conclusion"],
                "confidence": 0.9,
                "reason": f"实时高优信号覆盖历史规律：{realtime_signal['event']}"
            }
        
        # 规则 2: 实时中优信号 + 历史规律一致
        if realtime_signal.get("priority") == "MEDIUM":
            if realtime_signal["conclusion"] == historical_signal["conclusion"]:
                return {
                    "conclusion": historical_signal["conclusion"],
                    "confidence": 0.85,  # 增强置信度
                    "reason": "实时信号与历史规律一致，置信度增强"
                }
            else:
                return {
                    "conclusion": historical_signal["conclusion"],
                    "confidence": 0.7,
                    "reason": "实时信号与历史规律冲突，以历史规律为准"
                }
        
        # 规则 3: 实时低优信号
        return {
            "conclusion": historical_signal["conclusion"],
            "confidence": historical_signal["confidence"],
            "reason": "实时信号仅作为补充信息"
        }
```

---

## 五、数据库设计

### 5.1 MySQL 业务表

#### user（用户表）

```sql
CREATE TABLE IF NOT EXISTS user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) NOT NULL COMMENT '用户名',
    email VARCHAR(200) COMMENT '邮箱',
    role VARCHAR(50) DEFAULT 'sales' COMMENT '角色: sales/admin',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username)
) COMMENT '用户表';
```

#### video_task（视频任务表）

```sql
CREATE TABLE IF NOT EXISTS video_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(100) NOT NULL COMMENT '任务 ID（UUID）',
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    brand VARCHAR(200) NOT NULL COMMENT '品牌名',
    city VARCHAR(100) NOT NULL COMMENT '城市',
    style VARCHAR(100) COMMENT '风格',
    status VARCHAR(50) DEFAULT 'PENDING' COMMENT '状态: PENDING/PROCESSING/COMPLETED/FAILED',
    concept_image_url VARCHAR(500) COMMENT '概念图 URL',
    video_url VARCHAR(500) COMMENT '视频 URL',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME COMMENT '完成时间',
    INDEX idx_task_id (task_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) COMMENT '视频任务表';
```

#### community_summary（社区摘要表）

```sql
CREATE TABLE IF NOT EXISTS community_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    community_id INT NOT NULL COMMENT '社区 ID',
    summary TEXT NOT NULL COMMENT '社区摘要',
    node_count INT COMMENT '节点数量',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_community_id (community_id)
) COMMENT 'Leiden 社区摘要表';
```

### 5.2 Neo4j 图模型

```cypher
// 创建节点约束
CREATE CONSTRAINT brand_name_unique FOR (b:Brand) REQUIRE b.name IS UNIQUE;
CREATE CONSTRAINT city_name_unique FOR (c:City) REQUIRE c.name IS UNIQUE;
CREATE CONSTRAINT industry_name_unique FOR (i:Industry) REQUIRE i.name IS UNIQUE;

// 创建索引
CREATE INDEX brand_industry_idx FOR (b:Brand) ON (b.industry);
CREATE INDEX campaign_date_idx FOR (c:Campaign) ON (c.start_date);

// 示例：创建节点
CREATE (b:Brand {
    name: "XX茶饮",
    industry: "快消",
    annual_revenue: 1000000000
})

CREATE (c:City {
    name: "上海",
    tier: "一线",
    population: 25000000
})

CREATE (sm:StoreMetric {
    store_count: 50,
    avg_sales_per_store: 200000,
    date: "2024-04"
})

CREATE (camp:Campaign {
    location_type: "地铁",
    start_date: "2024-05",
    duration: 30,
    impression: 5000000,
    budget: 1000000
})

// 创建关系
MATCH (b:Brand {name: "XX茶饮"})
MATCH (c:City {name: "上海"})
MATCH (sm:StoreMetric {date: "2024-04"})
MATCH (camp:Campaign {start_date: "2024-05"})
CREATE (b)-[:OPERATES_IN {market_share: 0.15}]->(c)
CREATE (b)-[:HAS_METRIC]->(sm)
CREATE (b)-[:LAUNCHED {budget: 1000000}]->(camp)
CREATE (camp)-[:LOCATED_IN]->(c)
```

### 5.3 Milvus 向量集合

```python
# 伪代码：Milvus 集合定义
from pymilvus import CollectionSchema, FieldSchema, DataType

# Campaign 向量集合
fields = [
    FieldSchema(name="id", dtype=DataType.INT64, is_primary=True, auto_id=True),
    FieldSchema(name="campaign_id", dtype=DataType.INT64),
    FieldSchema(name="brand", dtype=DataType.VARCHAR, max_length=200),
    FieldSchema(name="city", dtype=DataType.VARCHAR, max_length=100),
    FieldSchema(name="description", dtype=DataType.VARCHAR, max_length=1000),
    FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=1536)
]

schema = CollectionSchema(fields=fields, description="Campaign vector collection")
collection = Collection(name="campaigns", schema=schema)

# 创建索引
index_params = {
    "index_type": "IVF_FLAT",
    "metric_type": "COSINE",
    "params": {"nlist": 1024}
}
collection.create_index(field_name="embedding", index_params=index_params)
```

---

## 六、API 接口设计

### 6.1 对话接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 普通对话（同步返回） |
| POST | `/api/chat/stream` | 流式对话（SSE） |
| GET | `/api/chat/history/{session_id}` | 获取会话历史 |

**请求示例**：

```json
POST /api/chat
{
    "session_id": "uuid-xxx",
    "message": "徐家汇地铁站适合投 XX 茶饮的广告吗？"
}
```

**响应示例**：

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "reply": "建议本周内触达 XX 茶饮，重点推徐家汇 + 商圈公交组合...",
        "sources": [
            {
                "type": "graphrag",
                "content": "XX 茶饮近 3 月杭州门店 +12 家，坪效 +3%",
                "confidence": 0.85
            },
            {
                "type": "amap",
                "content": "徐家汇地铁站日均人流 50 万",
                "confidence": 0.9
            },
            {
                "type": "bocha",
                "content": "今日新华社官宣 XX 茶饮签约顶流代言人",
                "confidence": 0.95
            }
        ],
        "recommended_actions": [
            "本周内触达",
            "重点推地铁 + 商圈公交组合",
            "话术：代言人官宣 + 区域扩张期"
        ]
    }
}
```

### 6.2 视频生成接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/video/generate` | 提交视频生成任务 |
| GET | `/api/video/status/{task_id}` | 查询任务状态 |
| GET | `/api/video/result/{task_id}` | 获取视频结果 |

**请求示例**：

```json
POST /api/video/generate
{
    "brand": "XX茶饮",
    "city": "上海",
    "style": "现代简约"
}
```

**响应示例**：

```json
{
    "code": 0,
    "message": "任务已提交",
    "data": {
        "task_id": "uuid-xxx",
        "status": "PENDING"
    }
}
```

### 6.3 数据查询接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/brand/{brand_name}` | 查询品牌信息 |
| GET | `/api/city/{city_name}/metrics` | 查询城市指标 |
| POST | `/api/campaign/search` | 搜索历史案例 |

---

## 七、部署方案

### 7.1 云端环境

- **云服务商**：京东云
- **配置**：4 核 16G + 5M 带宽
- **操作系统**：Ubuntu 22.04 LTS

### 7.2 Docker Compose 部署

```yaml
# docker-compose.yml
version: '3.8'

services:
  # MySQL
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root123
      MYSQL_DATABASE: jcdecaux_ai
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
    deploy:
      resources:
        limits:
          memory: 1G

  # Redis
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    deploy:
      resources:
        limits:
          memory: 512M

  # Neo4j
  neo4j:
    image: neo4j:5-community
    environment:
      NEO4J_AUTH: neo4j/password123
      NEO4J_dbms_memory_heap_max__size: 2G
    ports:
      - "7474:7474"
      - "7687:7687"
    volumes:
      - neo4j_data:/data
    deploy:
      resources:
        limits:
          memory: 2G

  # Milvus Standalone
  milvus-etcd:
    image: quay.io/coreos/etcd:v3.5.5
    environment:
      ETCD_AUTO_COMPACTION_MODE: revision
      ETCD_AUTO_COMPACTION_RETENTION: "1000"
    volumes:
      - etcd_data:/etcd

  milvus-minio:
    image: minio/minio:RELEASE.2023-03-20T20-16-18Z
    environment:
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    volumes:
      - minio_data:/minio

  milvus-standalone:
    image: milvusdb/milvus:v2.3.3
    environment:
      ETCD_ENDPOINTS: milvus-etcd:2379
      MINIO_ADDRESS: milvus-minio:9000
    ports:
      - "19530:19530"
    depends_on:
      - milvus-etcd
      - milvus-minio
    volumes:
      - milvus_data:/var/lib/milvus
    deploy:
      resources:
        limits:
          memory: 4G

  # FastAPI 应用
  app:
    build: .
    ports:
      - "8000:8000"
    environment:
      DATABASE_URL: mysql+asyncmy://root:root123@mysql:3306/jcdecaux_ai
      REDIS_URL: redis://redis:6379
      NEO4J_URI: bolt://neo4j:7687
      MILVUS_HOST: milvus-standalone
      DOUBAO_API_KEY: ${DOUBAO_API_KEY}
      JIMENG_API_KEY: ${JIMENG_API_KEY}
      AMAP_API_KEY: ${AMAP_API_KEY}
      BOCHA_API_KEY: ${BOCHA_API_KEY}
    depends_on:
      - mysql
      - redis
      - neo4j
      - milvus-standalone
    deploy:
      resources:
        limits:
          memory: 2G

volumes:
  mysql_data:
  neo4j_data:
  milvus_data:
  etcd_data:
  minio_data:
```

### 7.3 内存分配

| 组件 | 内存限制 | 备注 |
|------|---------|------|
| MySQL | 1GB | 已有 |
| Redis | 512MB | 已有 |
| Neo4j | 2GB | `NEO4J_dbms_memory_heap_max__size=2G` |
| Milvus Standalone | 4GB | 含 etcd + minio |
| FastAPI 应用 | 2GB | Python + LlamaIndex |
| 系统 + Docker | 2GB | 预留 |
| **总计** | **~12GB** | 16GB 够用，留 4GB buffer |

---

## 八、每日任务拆解

### 总体时间规划

- **总工期**：14 天（2 周）
- **每日工时**：8 小时
- **总工时**：112 小时

### Phase 1: 环境搭建与数据准备（Day 1-2）

#### Day 1: 云端环境搭建（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-10:00 | 京东云 ECS 登录，安装 Docker + Docker Compose | Docker 环境就绪 |
| 10:00-12:00 | 编写 `docker-compose.yml`，启动 MySQL + Redis | 数据库可用 |
| 14:00-16:00 | 部署 Neo4j（配置内存 2GB），验证 Cypher 查询 | Neo4j 可用 |
| 16:00-18:00 | 部署 Milvus Standalone（配置内存 4GB），验证向量检索 | Milvus 可用 |

**产出物**：
- `docker-compose.yml`
- 所有中间件运行正常

#### Day 2: Mock 数据准备（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 设计 MySQL 表结构（user, video_task, community_summary） | SQL 脚本 |
| 11:00-12:00 | 执行 SQL 初始化数据库 | 表创建完成 |
| 14:00-16:00 | Mock 德高业务数据（10 个品牌 × 5 个城市 × 历史案例） | Mock 数据脚本 |
| 16:00-18:00 | 将 Mock 数据导入 Neo4j（创建节点和关系） | 知识图谱初始化 |

**产出物**：
- `init_db.sql`
- `mock_data.py`
- Neo4j 中有 50+ 节点，100+ 关系

---

### Phase 2: GraphRAG 模块开发（Day 3-5）

#### Day 3: LlamaIndex GraphRAG 基础（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 学习 LlamaIndex KnowledgeGraphIndex 文档 | 学习笔记 |
| 11:00-12:00 | 编写实体抽取 Prompt（从文本抽取 Brand/City/Campaign） | Prompt 模板 |
| 14:00-16:00 | 实现 `extract_entities()` 函数（调用豆包 2.0） | `graphrag/extractor.py` |
| 16:00-18:00 | 测试实体抽取（输入 10 段文本，验证抽取准确率） | 测试报告 |

**产出物**：
- `graphrag/extractor.py`
- 实体抽取准确率 > 80%

#### Day 4: 知识图谱构建（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 编写关系抽取 Prompt（从文本抽取 OPERATES_IN/LAUNCHED 等） | Prompt 模板 |
| 11:00-12:00 | 实现 `extract_relations()` 函数 | `graphrag/extractor.py` |
| 14:00-16:00 | 实现 `build_graph()` 函数（将实体和关系写入 Neo4j） | `graphrag/builder.py` |
| 16:00-18:00 | 测试图谱构建（输入 10 段文本，验证 Neo4j 节点和关系） | 测试报告 |

**产出物**：
- `graphrag/builder.py`
- Neo4j 中新增 100+ 节点，200+ 关系

#### Day 5: 向量检索与融合（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 实现 `embed()` 函数（调用豆包 Embedding API） | `graphrag/embedder.py` |
| 11:00-12:00 | 将 Neo4j 中的 Campaign 节点向量化，写入 Milvus | 向量数据入库 |
| 14:00-16:00 | 实现 `vector_search()` 函数（Milvus 相似度检索） | `graphrag/retriever.py` |
| 16:00-18:00 | 实现 `graph_search()` 函数（Neo4j Cypher 查询） | `graphrag/retriever.py` |
| 18:00-19:00 | 实现 `merge_results()` 函数（融合两路结果） | `graphrag/retriever.py` |

**产出物**：
- `graphrag/embedder.py`
- `graphrag/retriever.py`
- 双路检索融合完成

---

### Phase 3: MCP 工具开发（Day 6-7）

#### Day 6: 高德地图 MCP + 博查 MCP（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 申请高德地图 API Key，阅读 API 文档 | API Key |
| 11:00-12:00 | 实现 `amap_search()` MCP 工具（POI 查询） | `mcp/amap_tool.py` |
| 14:00-16:00 | 申请博查 API Key，阅读 API 文档 | API Key |
| 16:00-18:00 | 实现 `bocha_search()` MCP 工具（舆情查询） | `mcp/bocha_tool.py` |
| 18:00-19:00 | 测试两个 MCP 工具（查询"徐家汇地铁站"和"XX 茶饮新闻"） | 测试报告 |

**产出物**：
- `mcp/amap_tool.py`
- `mcp/bocha_tool.py`
- 两个工具可正常调用

#### Day 7: 即梦图像/视频 MCP（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 申请火山引擎 API Key（即梦 AI 绘画 + 即梦视频） | API Key |
| 11:00-12:00 | 实现 `jimeng_image()` MCP 工具（文生图） | `mcp/jimeng_image_tool.py` |
| 14:00-16:00 | 实现 `jimeng_video()` MCP 工具（图生视频） | `mcp/jimeng_video_tool.py` |
| 16:00-18:00 | 测试两个 MCP 工具（生成广告概念图 + 视频） | 测试报告 |
| 18:00-19:00 | 实现 `similar_case_search()` MCP 工具（相似案例检索） | `mcp/similar_case_tool.py` |

**产出物**：
- `mcp/jimeng_image_tool.py`
- `mcp/jimeng_video_tool.py`
- `mcp/similar_case_tool.py`
- 三个工具可正常调用

---

### Phase 4: Agent 模块开发（Day 8-10）

#### Day 8: Agent 调度中枢（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 设计 Agent 架构（意图识别 → 工具路由 → 结果融合） | 架构图 |
| 11:00-12:00 | 实现 `AgentOrchestrator` 类（加载会话历史） | `agent/orchestrator.py` |
| 14:00-16:00 | 实现 `chat()` 方法（调用豆包 2.0 Function Calling） | `agent/orchestrator.py` |
| 16:00-18:00 | 实现工具调用循环（处理 `has_tool_call()`） | `agent/orchestrator.py` |
| 18:00-19:00 | 测试 Agent 调度（输入"徐家汇地铁站适合投 XX 茶饮吗？"） | 测试报告 |

**产出物**：
- `agent/orchestrator.py`
- Agent 可正确调用 graphrag_search + amap_search + bocha_search

#### Day 9: 冲突消解与流式输出（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 实现 `ConflictResolver` 类（三条规则） | `agent/conflict_resolver.py` |
| 11:00-12:00 | 测试冲突消解（模拟"历史规律 vs 实时信号"冲突场景） | 测试报告 |
| 14:00-16:00 | 实现 SSE 流式输出（`chat/stream` 接口） | `api/chat.py` |
| 16:00-18:00 | 测试流式输出（验证前端可逐字显示） | 测试报告 |
| 18:00-19:00 | 优化 System Prompt（明确工具调用规则） | Prompt 模板 |

**产出物**：
- `agent/conflict_resolver.py`
- `api/chat.py`（支持 SSE）
- 流式输出正常

#### Day 10: 视频生成任务管理（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 实现 `VideoTaskManager` 类（提交任务 + 异步执行） | `agent/video_manager.py` |
| 11:00-12:00 | 实现任务状态更新（PENDING → PROCESSING → COMPLETED） | `agent/video_manager.py` |
| 14:00-16:00 | 实现 Redis TTL（7 天自动过期） | `agent/video_manager.py` |
| 16:00-18:00 | 测试视频生成流程（提交任务 → 查询状态 → 获取结果） | 测试报告 |
| 18:00-19:00 | 实现失败重试机制（最多重试 3 次） | `agent/video_manager.py` |

**产出物**：
- `agent/video_manager.py`
- 视频生成流程完整

---

### Phase 5: API 接口与测试（Day 11-12）

#### Day 11: FastAPI 接口开发（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 实现 `/api/chat` 接口（同步对话） | `api/chat.py` |
| 11:00-12:00 | 实现 `/api/chat/stream` 接口（流式对话） | `api/chat.py` |
| 14:00-16:00 | 实现 `/api/video/generate` 接口（提交视频任务） | `api/video.py` |
| 16:00-18:00 | 实现 `/api/video/status/{task_id}` 接口（查询状态） | `api/video.py` |
| 18:00-19:00 | 实现 `/api/video/result/{task_id}` 接口（获取结果） | `api/video.py` |

**产出物**：
- `api/chat.py`
- `api/video.py`
- 所有接口可正常调用

#### Day 12: 端到端测试（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 编写测试用例（10 个典型场景） | `tests/test_cases.py` |
| 11:00-12:00 | 测试决策引擎（GraphRAG + 冲突消解） | 测试报告 |
| 14:00-16:00 | 测试执行引擎（视频生成全流程） | 测试报告 |
| 16:00-18:00 | 测试 Agent 调度（多工具协同） | 测试报告 |
| 18:00-19:00 | 修复 Bug，优化性能 | Bug 修复记录 |

**产出物**：
- `tests/test_cases.py`
- 测试报告（通过率 > 90%）

---

### Phase 6: 部署与文档（Day 13-14）

#### Day 13: 云端部署（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 编写 `Dockerfile`（FastAPI 应用） | `Dockerfile` |
| 11:00-12:00 | 编写 `.env` 文件（API Key 配置） | `.env` |
| 14:00-16:00 | 上传代码到京东云，执行 `docker-compose up -d` | 服务启动 |
| 16:00-18:00 | 验证所有服务运行正常（MySQL/Redis/Neo4j/Milvus/App） | 验证报告 |
| 18:00-19:00 | 配置 Nginx 反向代理（可选） | Nginx 配置 |

**产出物**：
- `Dockerfile`
- `.env`
- 服务在云端正常运行

#### Day 14: 文档与总结（8 小时）

| 时间段 | 任务 | 产出 |
|--------|------|------|
| 09:00-11:00 | 编写 README.md（项目介绍 + 快速启动） | `README.md` |
| 11:00-12:00 | 编写 API 文档（接口说明 + 示例） | `docs/api.md` |
| 14:00-16:00 | 编写技术博客（GraphRAG 实现细节） | 博客草稿 |
| 16:00-18:00 | 整理面试讲点（项目难点 + 解决方案） | 面试准备文档 |
| 18:00-19:00 | 项目总结与复盘 | 复盘文档 |

**产出物**：
- `README.md`
- `docs/api.md`
- 技术博客草稿
- 面试准备文档

---

## 九、面试讲点

### 9.1 项目亮点总结

| 亮点 | 怎么讲 |
|------|--------|
| **GraphRAG 替代纯向量检索** | "传统 RAG 只能做语义相似匹配，无法多跳推理。我用 LlamaIndex + Neo4j 构建了 GraphRAG，支持'品牌→城市→坪效→历史案例'的多跳推理，检索准确率提升 30%" |
| **Leiden 社区发现优化性能** | "图谱有几千个节点，每次查询都遍历全图太慢。我用 Leiden 算法分层聚类，提前算好每个社区的摘要，检索时先定位社区再精细检索，延迟下降 60%" |
| **Agent 自主编排多路工具** | "我没有写死的 if-else，而是让 Agent 通过 Function Calling 动态调用 graphrag_search、amap_search、bocha_search、jimeng_video 等工具，实现真正的智能决策" |
| **冲突消解规则引擎** | "当实时信号（如代言人官宣）与历史规律冲突时，我没有交给大模型判断，而是用 Java/Python 代码实现了三条确定性规则，保证决策的可解释性和稳定性" |
| **双语言经历** | "interview-arena 项目我用 Java + Spring AI，这个项目我用 Python + LlamaIndex，熟悉两种技术栈的优劣，能根据场景灵活选型" |

### 9.2 高频面试题

1. **Q：GraphRAG 和普通 RAG 有什么区别？**
   - A：普通 RAG 只能做语义相似匹配，GraphRAG 支持多跳推理（品牌→城市→坪效→历史案例），保留业务逻辑关联。

2. **Q：Leiden 社区发现算法是什么？怎么用的？**
   - A：Leiden 是图聚类算法，我把图谱分成多个社区，提前算好每个社区的摘要，检索时先定位社区再精细检索，延迟下降 60%。

3. **Q：Agent 是怎么编排工具的？**
   - A：Agent 通过豆包 2.0 的 Function Calling 能力，根据用户意图动态调用 graphrag_search、amap_search、bocha_search 等工具，而不是写死的 if-else。

4. **Q：实时信号和历史规律冲突怎么办？**
   - A：我没有交给大模型判断，而是用代码实现了三条规则：实时高优信号 > 历史规律（覆盖）、实时中优 + 历史一致（增强置信度）、实时低优（仅补充）。

5. **Q：视频生成是怎么做的？**
   - A：用户要求生成视频时，Agent 调用 jimeng_image 生成概念图，再调用 jimeng_video 生成动态视频，整个过程异步执行，2 分钟出片。

---

## 十、风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| **豆包 API 限流** | 高频调用被限流 | 实现重试机制 + 降级策略（返回缓存结果） |
| **Neo4j 查询慢** | 多跳查询性能差 | 优化 Cypher 查询 + 添加索引 + Leiden 社区预加载 |
| **Milvus 内存不足** | 向量数据量大 | 分批加载 + 定期清理过期数据 |
| **视频生成失败** | 即梦 API 调用失败 | 失败重试（最多 3 次）+ 降级返回概念图 |
| **云端内存不足** | 16GB 内存紧张 | 严格限制各组件内存 + 监控告警 |

---

## 十一、下一步

1. **按每日任务拆解执行**：从 Day 1 开始，每天完成对应任务
2. **每日复盘**：记录遇到的问题和解决方案
3. **面试准备**：Day 14 整理面试讲点，模拟面试问答

---

> 📅 创建时间：2026-07-03
> 🎯 目标：14 天完成德高 AI 智能决策系统，形成 Java + Python 双语言经历
> 🚀 开始执行！
