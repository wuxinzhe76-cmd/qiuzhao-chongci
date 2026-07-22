# 德高户外广告 AI 智能决策系统 · 项目蓝图

> 项目代号：**jcdecaux-ai**（德高户外广告 AI 智能决策与多模态生成系统）
> 工程目录：`6-MyProject/jcdecaux-ai/`
> 文档目录：`6-MyProject/jcdecaux-ai/docs/`
> 本文档是德高 AI 项目的完整实现蓝图，包含技术架构、模块设计、每日任务拆解。
> 阅读本文档即可了解项目全貌、技术细节、开发计划及部署方案。

---

## 一、项目定位与目标

### 1.1 项目背景

德高（JCDecaux）户外广告业务覆盖快消、美妆、金融、旅游、汽车等核心行业（示例规模约 2000 家企业客户，仅作背景说明，非精确统计）。销售团队在日常拓客中面临三大痛点：

1. **客户优先级难判断**：大量客户中，谁下个月更可能释放户外预算？依据是什么？
2. **提案准备成本高**：首次见客户时，缺乏直观素材展示"广告投出来长什么样"，依赖历史案例，转化效率低。
3. **决策缺乏数据支撑**：销售凭经验判断"该不该跟"，缺少"预算节奏 + 行业周期 + 实时信号"的结构化归因。

### 1.2 项目目标

构建一套 **"数据驱动 + AI 赋能"** 的智能决策系统，实现：

- ✅ **决策层**：基于 GraphRAG 的知识检索与推理，输出客户/行业优先级评分 + 可解释归因
- ✅ **执行层**：一键生成"客户定制 + 城市地标 + 动态视频"的提案素材（目标：分钟级出片，具体耗时待压测验证）
- ✅ **智能层**：Agent 自主编排多路工具（图谱检索 + 地图数据 + 实时舆情 + 视频生成），融合历史规律与实时信号

### 1.2.1 指标分级说明

> 🔴 项目指标严格区分"已验证"与"目标/待验证"，避免面试中被追问穿。

| 指标分级 | 说明 | 具体指标 |
|---------|------|---------|
| **✅ 已验证指标** | 业务交付效率（非接口延迟），内部试用可证明 | 人工制作广告效果图从 3-4 天缩短至约 1 天 |
| **🎯 目标/待验证** | 系统上线后需压测验证 | GraphRAG 检索 P95 延迟、向量召回准确率、点位推荐采纳率、投前预测误差、效果图平均生成时间 |
| **📋 示例指标** | 仅用于需求理解，非承诺 | 客户数量、出片耗时等具体数字 |

### 1.3 核心差异化

| 维度 | 传统 RAG 方案 | 本项目 GraphRAG 方案 |
|------|--------------|---------------------|
| 检索方式 | 向量相似度匹配 | 图遍历 + 向量召回双路融合 |
| 推理能力 | 单跳检索，无法多跳推理 | 支持多跳推理（品牌→城市→坪效→历史案例） |
| 上下文理解 | 语义相近但业务无关 | 保留业务逻辑关联（如"扩张期品牌优先跟进"） |
| 检索延迟 | 全图遍历，延迟高 | 第一阶段固定 Schema + Cypher 模板检索；第三阶段可选 Leiden 社区摘要预加载（目标值，待验证） |

---

## 二、技术选型

### 2.1 核心技术栈

> 🔴 **双语言架构**：Java 负责业务平台（CRUD + 确定性计算），Python 负责 AI 模块（Agent + GraphRAG + 生成）。

| 层 | 技术 | 版本 | 选型理由 |
|----|------|------|----------|
| **业务平台语言** | Java | 17+ | 地铁站/广告位 CRUD、投前投后报告计算、调用团队已有曝光算法，企业级稳定性 |
| **AI 模块语言** | Python | 3.10+ | AI 生态成熟，LlamaIndex/LangChain 等框架原生支持 |
| **Web 框架（AI 侧）** | FastAPI | 0.110+ | 异步高性能，原生支持 SSE 流式输出 |
| **Web 框架（业务侧）** | Spring Boot | 3.x | Java 业务平台 REST API，对接 MySQL + 已有算法 |
| **AI 框架** | LlamaIndex | 0.10+ | 模块化好，可定制 GraphRAG pipeline |
| **文本大模型** | 豆包 2.0 | 火山引擎 | 德高与火山引擎有合作，Function Calling 能力强 |
| **图像生成** | 即梦 AI 绘画 | 火山引擎 | 统一火山引擎生态，支持文生图/图生图 |
| **视频生成** | 即梦视频 | 火山引擎 | 图生视频 |
| **图数据库** | Neo4j | 5.x 社区版 | 业界标准，Cypher 查询语言适合多跳推理 |
| **向量库** | Milvus Standalone | 2.3+ | 高性能，Docker 单机部署 |
| **关系数据库** | MySQL | 8.0 | 存储业务数据、客流曝光、投放记录、报告结果 |
| **缓存** | Redis | 7.x | 会话缓存、语义缓存 |
| **MCP 协议** | FastMCP | 0.30+ | 统一工具调用协议，Agent 动态编排，Java 能力封装为 MCP 工具供 Python Agent 调用 |

#### 2.1.1 双语言职责分工

| 模块 | 语言 | 职责 |
|------|------|------|
| **Java 业务平台** | Java + Spring Boot | 地铁站 CRUD、广告位 CRUD、人群画像查询、投放记录 CRUD、投前报告计算、投后报告计算、曝光指标查询、已有算法调用、报告数据持久化 |
| **Python AI 模块** | Python + FastAPI | 自然语言理解、GraphRAG 检索、向量召回、Agent 编排、MCP Client 调用、答案生成、效果图工作流 |

**推荐调用链**：

```
用户
  ↓
FastAPI Agent（Python）
  ↓
识别投前报告需求
  ↓
调用 MCP 工具
  ↓
Java 投前计算服务（Spring Boot）
  ↓
查询 MySQL + 调用既有曝光算法
  ↓
返回结构化计算结果
  ↓
Agent 结合 GraphRAG 案例生成报告
```

> 💡 **面试讲点**："我主要写 Java 数据库 CRUD，并调用团队已有算法完成投前和投后报告计算；这些能力由团队进一步封装成 MCP 工具供 Agent 使用，MCP 框架本身不是我搭建的。"

### 2.2 MCP 工具清单

> 🔴 MCP 工具是 Java 业务能力与 Python AI 模块的桥梁，Agent 通过 MCP 协议调用 Java 服务。

| MCP Server | 功能 | 数据源 / 后端 | 调用场景 |
|-----------|------|--------|---------|
| **search_graphrag_cases** | 检索知识图谱历史案例 | Neo4j + Milvus | "有没有肯德基或类似快餐品牌的地铁投放案例" |
| **query_station_profile** | 查询地铁站画像（客流/商圈/广告位） | Java 服务 + MySQL | "南京西路站有哪些广告位，客流多少" |
| **calculate_pre_campaign_report** | 投前报告计算 | Java 服务 + MySQL + 曝光算法 | "肯德基在南京西路站投放，预计曝光多少" |
| **calculate_post_campaign_report** | 投后报告计算 | Java 服务 + MySQL + 曝光算法 | "这次投放的实际效果如何" |
| **search_company_info** | 实时舆情、官媒新闻 | 博查 API | "肯德基最近有什么新闻" |
| **generate_ad_mockup** | 生成广告效果图/视频 | 即梦 AI 绘画 + 即梦视频 | "生成一张肯德基地铁广告效果图" |

---

## 三、系统架构

### 3.1 整体架构图

```
                    用户提问
                       ↓
              FastAPI / Agent 服务
                       ↓
           意图识别 + 实体抽取 + 路由
                       ↓
       ┌───────────────┼────────────────┐
       ↓               ↓                ↓
GraphRAG 检索      Java 业务 MCP      外部信息 MCP
（Python）         （Java）           （Python）
       ↓               ↓                ↓
Neo4j + Milvus    MySQL + 曝光算法   博查/地铁查询
       └───────────────┼────────────────┘
                       ↓
              结果融合与业务排序
                       ↓
            投前/投后报告与案例说明
                       ↓
              效果图生成工作流
```

**存储分工**：

| 存储 | 存什么 | 为什么 |
|------|--------|--------|
| **Neo4j** | 品牌、人群、站点、广告位、活动、案例关系 | 多跳推理需要图遍历 |
| **Milvus** | 报告、案例、文档、图片描述的语义召回 | 语义相似度匹配 |
| **MySQL** | 客流、曝光、人群比例、投放记录、报告结果 | 结构化时序数据，Java CRUD + 计算 |
| **Redis** | 会话缓存、语义缓存 | 低延迟缓存 |

**语言分工**：

| 语言 | 负责 |
|------|------|
| **Java** | CRUD 与确定性投前投后计算（地铁站、广告位、人群画像、投放记录、报告计算） |
| **Python** | Agent、GraphRAG、MCP 编排和生成流程（意图识别、检索、答案生成、效果图） |

### 3.2 核心流程

#### 决策引擎流程（GraphRAG + Java 业务 MCP）

```
用户提问："肯德基想在南京西路站投放广告，合适吗？"
  │
  ├── Step 1: 查询理解与路由（LLM 提取实体和意图）
  │   -> intent: "evaluate_station"
  │   -> brand: "肯德基", city: "上海", station: "南京西路站"
  │   -> 路由到：指定站点评估检索器
  │
  ├── Step 2: 并行调用检索器 + MCP 工具
  │   ├── GraphRAG 检索（Python）
  │   │   -> Neo4j：Brand->AudienceSegment + MetroStation->AudienceSegment
  │   │   -> Neo4j：MetroStation->AdLocation + Campaign->Case
  │   │   -> Milvus：语义召回相似案例
  │   │   -> 结果：肯德基目标人群与南京西路站人群匹配度
  │   │
  │   ├── query_station_profile（Java MCP）
  │   │   -> Java 服务查询 MySQL：南京西路站客流、可用广告位
  │   │   -> 结果：日均客流 45 万，可用广告位 8 个
  │   │
  │   ├── calculate_pre_campaign_report（Java MCP）
  │   │   -> Java 服务调用曝光算法：预估曝光量
  │   │   -> 结果：预计日均曝光 12 万次
  │   │
  │   └── search_company_info（MCP）
  │       -> 博查 API：肯德基最新动态
  │       -> 结果：肯德基新推出早餐系列，加大地铁广告投放
  │
  ├── Step 3: Agent 结果融合与业务排序
  │   • 人群匹配度高 + 客流充足 + 历史案例支持
  │   • 融合结论：推荐投放，建议选择站厅数字大屏
  │
  └── Step 4: 生成自然语言回复 + 推荐动作 + 历史案例
      -> "推荐肯德基在南京西路站投放，目标人群匹配度高等..."
```

#### 执行引擎流程（效果图生成）

```
用户追问："能生成个广告效果图吗？"
  │
  ├── Step 1: Agent 意图识别 -> 调用 generate_ad_mockup MCP
  │
  ├── Step 2: 调用即梦 AI 绘画生成概念图
  │   -> 即梦 AI 绘画 API：生成广告概念图
  │   -> 输入：品牌 Logo + 风格参数 + 地铁场景
  │   -> 输出：广告概念图供选择
  │
  ├── Step 3: 调用即梦视频生成动态视频
  │   -> 即梦视频 API：图生视频
  │   -> 输入：概念图 + 运镜参数（跟随地铁、光影变化）
  │   -> 输出：地铁广告视频
  │
  └── Step 4: 返回视频 URL + 预览图
```

---

## 四、核心模块设计

### 4.1 决策引擎：GraphRAG 模块

#### 4.1.1 知识图谱设计

> 🔴 在原有 Brand/City/StoreMetric/Campaign/Report/Industry 基础上，新增地铁广告领域模型节点，覆盖人群画像、地铁网络、广告资源、历史案例四大维度。

**节点（Node）类型**：

##### 原有节点

| 节点类型 | 属性 | 示例 |
|---------|------|------|
| **Brand** | name, industry, annual_revenue | 肯德基，快餐，年销售额 XX 亿 |
| **City** | name, tier, population | 上海，一线，2500 万 |
| **StoreMetric** | brand_id, city_id, store_count, avg_sales_per_store, date | 肯德基上海门店数 50，月均销售额 20 万 |
| **Campaign** | brand_id, city_id, location_type, start_date, duration, impression | 肯德基上海地铁广告，2024-05，1 个月，曝光 500 万 |
| **Report** | title, industry, publish_date, content_vector | 《2024 快消行业户外投放趋势报告》 |
| **Industry** | name, key_brands, growth_rate | 快餐，[肯德基, 麦当劳]，+5% |

##### 新增：人群画像节点

| 节点类型 | 属性 | 示例 |
|---------|------|------|
| **AudienceSegment** | name, description, age_range, income_level | 年轻职场人群、学生人群、家庭人群、商务人群 |
| **ConsumerTag** | tag_name, category | 快餐偏好、高消费、高频通勤、20-35 岁、工作日早高峰 |

##### 新增：地铁网络与空间信息节点

| 节点类型 | 属性 | 示例 |
|---------|------|------|
| **MetroLine** | line_name, city, operator | 上海 2 号线、12 号线、13 号线 |
| **MetroStation** | name, city, daily_traffic, line_count | 南京西路站，上海，日均客流 45 万，3 条线换乘 |
| **BusinessArea** | name, city, type, level | 南京西路商圈、徐家汇商圈、陆家嘴商圈 |
| **District** | name, city | 静安区、黄浦区、浦东新区 |

##### 新增：广告资源节点

| 节点类型 | 属性 | 示例 |
|---------|------|------|
| **AdLocation** | name, station_id, format, estimated_impressions_per_hour, status | 南京西路站 A 出口灯箱、站厅数字大屏、扶梯墙面广告 |
| **AdFormat** | format_name, description, typical_size | 灯箱、数字大屏、墙面贴纸、站台屏幕 |

##### 新增：历史案例与报告节点

| 节点类型 | 属性 | 示例 |
|---------|------|------|
| **Case** | title, brand, campaign_id, summary, outcome | 《肯德基南京西路站早餐系列投放案例》 |
| **PreCampaignReport** | campaign_id, predicted_impression, predicted_reach, created_at | 投前预测：日均曝光 12 万次 |
| **PostCampaignReport** | campaign_id, actual_impression, actual_reach, roi, created_at | 投后实际：日均曝光 11.5 万次，ROI 1.8 |
| **Document** | title, type, content_vector, source_url | 投放方案 PDF、案例总结文档 |
| **ImageAsset** | title, type, url, description_vector | 广告效果图、投放现场照片 |

**边（Edge/关系）类型**：

##### 原有关系

| 关系类型 | 起点 -> 终点 | 属性 | 示例 |
|---------|------------|------|------|
| **OPERATES_IN** | Brand -> City | market_share | 肯德基 -> 上海，市场份额 15% |
| **HAS_METRIC** | Brand -> StoreMetric | date | 肯德基 -> 门店指标，2024-04 |
| **LAUNCHED** | Brand -> Campaign | budget | 肯德基 -> 上海地铁广告，预算 100 万 |
| **SIMILAR_TO** | Campaign -> Campaign | similarity_score | 快餐案例 A -> 快餐案例 B，相似度 0.85 |
| **BELONGS_TO** | Brand -> Industry | sub_category | 肯德基 -> 快餐，炸鸡细分 |
| **LOCATED_IN** | Campaign -> City | specific_location | 上海地铁广告 -> 南京西路站 |

##### 新增：人群画像关系

| 关系类型 | 起点 -> 终点 | 属性 | 示例 |
|---------|------------|------|------|
| **TARGETS** | Brand -> AudienceSegment | weight | 肯德基 -> 年轻职场人群，权重 0.8 |
| **HAS_TAG** | AudienceSegment -> ConsumerTag | - | 年轻职场人群 -> 高频通勤 |
| **CONCENTRATED_AT** | AudienceSegment -> MetroStation | ratio, period, data_version | 年轻职场人群 -> 南京西路站，占比 0.42，工作日早高峰 |

##### 新增：地铁网络关系

| 关系类型 | 起点 -> 终点 | 属性 | 示例 |
|---------|------------|------|------|
| **BELONGS_TO_LINE** | MetroStation -> MetroLine | - | 南京西路站 -> 2 号线 |
| **LOCATED_IN_AREA** | MetroStation -> BusinessArea | - | 南京西路站 -> 南京西路商圈 |
| **LOCATED_IN_DISTRICT** | BusinessArea -> District | - | 南京西路商圈 -> 静安区 |
| **LOCATED_IN_CITY** | District -> City | - | 静安区 -> 上海 |

##### 新增：广告资源关系

| 关系类型 | 起点 -> 终点 | 属性 | 示例 |
|---------|------------|------|------|
| **HAS_AD_LOCATION** | MetroStation -> AdLocation | - | 南京西路站 -> 站厅数字大屏 |
| **USES_FORMAT** | AdLocation -> AdFormat | - | 站厅数字大屏 -> 数字大屏 |

##### 新增：投放与案例关系

| 关系类型 | 起点 -> 终点 | 属性 | 示例 |
|---------|------------|------|------|
| **FOR_BRAND** | Campaign -> Brand | - | 上海地铁广告 -> 肯德基 |
| **PLACED_AT** | Campaign -> AdLocation | duration | 肯德基广告 -> 站厅数字大屏，30 天 |
| **TARGETED** | Campaign -> AudienceSegment | - | 肯德基广告 -> 年轻职场人群 |
| **HAS_PRE_REPORT** | Campaign -> PreCampaignReport | - | 肯德基广告 -> 投前预测报告 |
| **HAS_POST_REPORT** | Campaign -> PostCampaignReport | - | 肯德基广告 -> 投后效果报告 |
| **HAS_CASE** | Campaign -> Case | - | 肯德基广告 -> 投放案例总结 |
| **SUPPORTED_BY** | Case -> Document | - | 投放案例 -> 方案 PDF |
| **HAS_ASSET** | Case -> ImageAsset | - | 投放案例 -> 效果图 |

> 💡 **设计原则**：人流和人群画像的时序数据（weekday/weekend/morning/evening/month/quarter）存入 MySQL，图谱只保存关联关系和基础指标。大量历史时间序列不应全塞进 Neo4j。

#### 4.1.2 GraphRAG 检索流程

> 🔴 企业级系统不能只有一条固定查询路径。根据用户意图，路由到三种不同检索场景。

##### 场景一：宽泛点位推荐（recommend_ad_locations）

**用户输入**：肯德基想在上海地铁投放广告。

**检索路径**：

```
Brand -> AudienceSegment -> MetroStation -> AdLocation -> Campaign -> Case
```

```python
# 伪代码：场景一 - 宽泛点位推荐
def recommend_ad_locations(brand: str, city: str):
    # Step 1: 图遍历 - 品牌目标人群 -> 人群集中的站点 -> 站点广告位
    cypher_query = """
    MATCH (b:Brand {name: $brand})-[:TARGETS]->(a:AudienceSegment)
    MATCH (a)-[c:CONCENTRATED_AT]->(s:MetroStation)
    MATCH (s)-[:HAS_AD_LOCATION]->(al:AdLocation)
    OPTIONAL MATCH (camp:Campaign)-[:PLACED_AT]->(al)
    OPTIONAL MATCH (camp)-[:HAS_CASE]->(case:Case)
    WHERE s.city = $city
    RETURN s.name, s.daily_traffic, c.ratio, collect(al.name) as ad_locations,
           collect(case.title) as cases
    ORDER BY c.ratio DESC LIMIT 10
    """
    graph_results = neo4j_client.run(cypher_query, brand=brand, city=city)

    # Step 2: Milvus 向量召回相似案例
    vector_results = milvus_client.search(
        collection="cases",
        query_embedding=embed(f"{brand} 地铁广告投放案例"),
        top_k=10
    )

    # Step 3: 融合结果 - 推荐站点 + 广告位 + 历史案例 + 预测曝光
    return merge_results(graph_results, vector_results)
```

**输出**：推荐站点、推荐广告位、推荐依据、历史案例、预测曝光。

##### 场景二：指定站点评估（evaluate_station）

**用户输入**：肯德基想在南京西路站投放广告。

**检索路径**：

```
Brand -> AudienceSegment
MetroStation -> AudienceSegment（人群匹配度）
MetroStation -> AdLocation（可用广告位）
Campaign -> Case（相似案例）
```

```python
# 伪代码：场景二 - 指定站点评估
def evaluate_station(brand: str, station: str):
    # Step 1: 图遍历 - 品牌人群 vs 站点人群匹配度
    cypher_query = """
    MATCH (b:Brand {name: $brand})-[:TARGETS]->(ba:AudienceSegment)
    MATCH (s:MetroStation {name: $station})<-[c:CONCENTRATED_AT]-(sa:AudienceSegment)
    WITH b, s, ba, sa, c
    WHERE ba.name = sa.name
    RETURN s.name, s.daily_traffic, ba.name as matched_segment, c.ratio

    UNION

    MATCH (s:MetroStation {name: $station})-[:HAS_AD_LOCATION]->(al:AdLocation)
    RETURN s.name, s.daily_traffic, null, collect(al.name) as ad_locations
    """
    graph_results = neo4j_client.run(cypher_query, brand=brand, station=station)

    # Step 2: 同时调用 Java MCP 获取客流和曝光计算
    station_profile = mcp_client.call("query_station_profile", {"station": station})
    pre_report = mcp_client.call("calculate_pre_campaign_report",
                                  {"brand": brand, "station": station})

    # Step 3: 融合 - 人群匹配度 + 客流 + 可用广告位 + 相似案例 + 预计曝光
    return merge_results(graph_results, station_profile, pre_report)
```

**输出**：人群匹配度、站点客流、可用广告位、相似案例、预计曝光。

##### 场景三：历史案例检索（search_cases）

**用户输入**：有没有肯德基或者类似快餐品牌的地铁投放案例？

**检索路径**：

```
Brand -> Industry -> Similar Brand -> Campaign -> Case -> Document/ImageAsset
```

```python
# 伪代码：场景三 - 历史案例检索
def search_cases(brand: str):
    # Step 1: Milvus 语义召回案例（主路径）
    vector_results = milvus_client.search(
        collection="cases",
        query_embedding=embed(f"{brand} 地铁广告投放案例"),
        top_k=20
    )

    # Step 2: Neo4j 扩展同类品牌与相似点位
    cypher_query = """
    MATCH (b:Brand {name: $brand})-[:BELONGS_TO]->(i:Industry)<-[:BELONGS_TO]-(sb:Brand)
    MATCH (sb)-[:LAUNCHED]->(camp:Campaign)-[:HAS_CASE]->(case:Case)
    OPTIONAL MATCH (case)-[:SUPPORTED_BY]->(doc:Document)
    OPTIONAL MATCH (case)-[:HAS_ASSET]->(img:ImageAsset)
    RETURN sb.name, camp.start_date, case.title, case.outcome,
           collect(doc.title) as documents, collect(img.url) as images
    LIMIT 15
    """
    graph_results = neo4j_client.run(cypher_query, brand=brand)

    # Step 3: 融合 - 语义召回 + 同类品牌案例扩展
    return merge_results(vector_results, graph_results)
```

**输出**：同类品牌投放案例、案例文档、效果图、投放效果。

#### 4.1.3 查询理解与路由层

> 🔴 LLM 提取实体和意图，路由器选择对应的 Cypher 模板、向量检索器和 MCP 工具。不是 Neo4j 自己判断，而是先理解再路由。

```python
# 伪代码：查询理解与路由
def query_understanding(user_query: str):
    # Step 1: LLM 提取实体和意图，输出 JSON
    prompt = """
    分析用户问题，提取以下信息并输出 JSON：
    - intent: 意图（recommend_ad_locations / evaluate_station / search_cases
              / calculate_pre_report / calculate_post_report）
    - brand: 品牌名
    - city: 城市
    - station: 地铁站（如有）
    - audience: 人群（如有）
    - budget: 预算（如有）
    - campaign_period: 投放周期（如有）
    - ad_format: 广告形式（如有）
    """
    parsed = doubao_client.chat(
        system_prompt=prompt,
        user_prompt=user_query
    )
    # 示例输出：
    # {
    #   "intent": "evaluate_station",
    #   "brand": "肯德基",
    #   "city": "上海",
    #   "station": "南京西路站",
    #   "audience": null,
    #   "budget": null,
    #   "campaign_period": null,
    #   "ad_format": null
    # }

    return json.loads(parsed)


def route_to_retriever(parsed_query: dict):
    intent = parsed_query["intent"]

    # 根据 intent 路由到不同检索器
    if intent == "recommend_ad_locations":
        return recommend_ad_locations(
            brand=parsed_query["brand"],
            city=parsed_query["city"]
        )
    elif intent == "evaluate_station":
        return evaluate_station(
            brand=parsed_query["brand"],
            station=parsed_query["station"]
        )
    elif intent == "search_cases":
        return search_cases(brand=parsed_query["brand"])
    elif intent == "calculate_pre_report":
        return mcp_client.call("calculate_pre_campaign_report", parsed_query)
    elif intent == "calculate_post_report":
        return mcp_client.call("calculate_post_campaign_report", parsed_query)
    else:
        return fallback_retriever(parsed_query)
```

**意图路由表**：

| Intent | 路由到 | 说明 |
|--------|--------|------|
| `recommend_ad_locations` | 点位推荐检索器 | 宽泛推荐，搜索全城站点 |
| `evaluate_station` | 指定站点评估检索器 | 聚焦单站，计算匹配度 |
| `search_cases` | 历史案例检索器 | 语义召回 + 同类品牌扩展 |
| `calculate_pre_report` | 投前计算 MCP（Java） | 调用 Java 曝光算法 |
| `calculate_post_report` | 投后计算 MCP（Java） | 调用 Java 效果计算 |

#### 4.1.4 Leiden 社区发现（可选优化 · 第三阶段）

> 🔴 Leiden 社区发现属于后续可选优化，当前阶段未实现，面试时如实说明。

**分阶段演进**：

| 阶段 | 内容 | 状态 |
|------|------|------|
| **第一阶段** | Neo4j 固定业务 Schema + Milvus 向量召回 + Cypher 模板检索 | ✅ 当前实现 |
| **第二阶段** | 加入实体对齐、混合检索和重排序 | 🔄 规划中 |
| **第三阶段** | 数据规模扩大后再尝试 Leiden 社区发现和社区摘要 | 📋 可选优化 |

```python
# 伪代码：第三阶段可选 - Leiden 社区发现 + 社区摘要预加载
# 注意：当前未实现，仅作为后续优化方向的参考
def build_community_summaries():
    # Step 1: 从 Neo4j 加载全图
    graph = neo4j_client.get_full_graph()

    # Step 2: 运行 Leiden 算法分层聚类
    communities = leiden_algorithm(graph, resolution=1.0)

    # Step 3: 为每个社区生成摘要
    for community_id, nodes in communities.items():
        texts = [node.description for node in nodes]
        summary = doubao_client.chat(
            system_prompt="总结以下户外广告案例的共同特征...",
            user_prompt="\n".join(texts)
        )
        mysql_client.insert(
            "community_summaries",
            community_id=community_id,
            summary=summary,
            node_count=len(nodes)
        )

    # Step 4: 检索时先定位社区，再精细检索（目标值，待验证）
```

> 💡 **面试讲点**："我们核心是领域知识图谱和向量检索。社区发现属于后续可选优化，我没有参与这一部分，也不能确认真实系统是否采用。"

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
            "search_graphrag_cases": GraphRAGTool(),
            "query_station_profile": StationProfileTool(),
            "calculate_pre_campaign_report": PreReportTool(),
            "calculate_post_campaign_report": PostReportTool(),
            "search_company_info": CompanyInfoTool(),
            "generate_ad_mockup": AdMockupTool()
        }
    
    async def chat(self, user_message: str, session_id: str):
        # Step 1: 加载会话历史
        history = self.load_session_history(session_id)
        
        # Step 2: 查询理解与路由
        parsed = query_understanding(user_message)
        
        # Step 3: 构建 System Prompt
        system_prompt = """
        你是德高户外广告的智能决策助手。你可以调用以下工具：
        - search_graphrag_cases: 检索知识图谱历史案例
        - query_station_profile: 查询地铁站画像（客流/商圈/广告位）
        - calculate_pre_campaign_report: 投前报告计算
        - calculate_post_campaign_report: 投后报告计算
        - search_company_info: 查询实时新闻舆情
        - generate_ad_mockup: 生成广告效果图/视频
        
        当用户询问点位推荐时，调用 search_graphrag_cases 检索历史案例。
        当用户询问指定站点评估时，调用 query_station_profile + calculate_pre_campaign_report。
        当用户要求生成效果图时，调用 generate_ad_mockup 工具。
        """
        
        # Step 4: 调用豆包 2.0（Function Calling）
        response = await self.doubao_client.chat(
            system_prompt=system_prompt,
            user_prompt=user_message,
            history=history,
            tools=list(self.mcp_tools.keys())
        )
        
        # Step 5: 处理工具调用
        while response.has_tool_call():
            tool_name = response.tool_name
            tool_args = response.tool_args
            
            # 调用对应的 MCP 工具
            tool_result = await self.mcp_tools[tool_name].execute(**tool_args)
            
            # 将工具结果返回给大模型
            response = await self.doubao_client.chat_with_tool_result(
                tool_result=tool_result
            )
        
        # Step 6: 返回最终回答
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

### 4.4 数据治理

> 🔴 企业级知识库最关键的不是检索算法，而是数据治理。图谱质量直接决定 GraphRAG 效果。

#### 4.4.1 实体统一

**品牌实体对齐**：

```
KFC / 肯德基 / 肯德基中国 / 百胜旗下肯德基
-> 映射到同一个 Brand 节点（name: "肯德基", aliases: ["KFC", "肯德基中国", ...]）
```

**地铁站实体对齐**：

```
南京西路 / 南京西路站 / 上海地铁南京西路站 / 2/12/13号线南京西路
-> 映射到同一个 MetroStation 节点（name: "南京西路站", aliases: ["南京西路", ...]）
```

#### 4.4.2 数据溯源

每个节点和关系增加溯源属性：

| 属性 | 说明 | 示例 |
|------|------|------|
| `source_id` | 数据来源 ID | doc_001、api_amap_20240501 |
| `source_type` | 来源类型 | document / api / manual / algorithm |
| `source_system` | 来源系统 | 博查、高德、内部 CRM、曝光算法 |
| `created_at` | 创建时间 | 2024-05-01T10:00:00 |
| `updated_at` | 更新时间 | 2024-06-01T15:00:00 |
| `data_version` | 数据版本 | 2024Q2、2024-05 |
| `confidence` | 置信度 | 0.95（算法计算）、0.8（人工标注）、0.6（推测） |

#### 4.4.3 权限控制

公司内部案例有权限限制，GraphRAG 检索时必须**先做权限过滤，不能检索完再过滤**：

| 权限级别 | 说明 | 示例 |
|---------|------|------|
| `public` | 公开案例，所有销售可见 | 已结案的公开投放案例 |
| `sales_internal` | 销售内部可见 | 进行中的投放方案 |
| `department_restricted` | 部门限制 | 特定行业组的客户数据 |
| `confidential` | 机密 | 高管专属的战略级客户数据 |

```python
# 伪代码：权限过滤（在 Cypher 查询中加入权限条件）
def build_permission_filter(user_role: str):
    if user_role == "admin":
        return ""  # 管理员无限制
    elif user_role == "sales":
        return "AND case.permission_level IN ['public', 'sales_internal']"
    else:
        return "AND case.permission_level = 'public'"
```

#### 4.4.4 时效性管理

人流和人群画像有时间属性，不能把"2024 年早高峰画像"永远当作当前数据：

| 时间维度 | 属性 | 示例 |
|---------|------|------|
| 日期类型 | `day_type` | weekday / weekend |
| 时段 | `time_period` | morning / evening / noon / night |
| 月份 | `month` | 2024-05 |
| 季度 | `quarter` | 2024Q2 |
| 数据版本 | `data_version` | 2024Q2（与溯源字段一致） |

> 💡 **设计原则**：检索时必须指定 `data_version` 或 `time_period`，避免使用过期数据做决策。

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

#### community_summary（社区摘要表 · 第三阶段可选）

```sql
CREATE TABLE IF NOT EXISTS community_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    community_id INT NOT NULL COMMENT '社区 ID',
    summary TEXT NOT NULL COMMENT '社区摘要',
    node_count INT COMMENT '节点数量',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_community_id (community_id)
) COMMENT 'Leiden 社区摘要表（第三阶段可选优化，当前未使用）';
```

#### metro_station（地铁站表）

```sql
CREATE TABLE IF NOT EXISTS metro_station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '站点名称',
    city VARCHAR(50) NOT NULL COMMENT '所属城市',
    line_count INT DEFAULT 1 COMMENT '换乘线路数',
    daily_traffic INT COMMENT '日均客流',
    business_area VARCHAR(100) COMMENT '所属商圈',
    district VARCHAR(50) COMMENT '所属行政区',
    data_version VARCHAR(20) COMMENT '数据版本',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_name_city (name, city),
    INDEX idx_city (city)
) COMMENT '地铁站信息表';
```

#### ad_location（广告位表）

```sql
CREATE TABLE IF NOT EXISTS ad_location (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL COMMENT '地铁站 ID',
    name VARCHAR(200) NOT NULL COMMENT '广告位名称',
    format VARCHAR(50) COMMENT '广告形式: 灯箱/数字大屏/墙面贴纸/站台屏幕',
    estimated_impressions_per_hour INT COMMENT '预估每小时曝光',
    status VARCHAR(20) DEFAULT 'available' COMMENT '状态: available/occupied/maintenance',
    data_version VARCHAR(20) COMMENT '数据版本',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_station_id (station_id),
    INDEX idx_status (status)
) COMMENT '广告位信息表';
```

#### station_traffic（站点客流时序表）

```sql
CREATE TABLE IF NOT EXISTS station_traffic (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL COMMENT '地铁站 ID',
    day_type VARCHAR(20) NOT NULL COMMENT 'weekday/weekend',
    time_period VARCHAR(20) NOT NULL COMMENT 'morning/evening/noon/night',
    traffic_count INT NOT NULL COMMENT '客流量',
    month VARCHAR(10) NOT NULL COMMENT '月份: 2024-05',
    data_version VARCHAR(20) COMMENT '数据版本',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_station_month (station_id, month),
    INDEX idx_day_type_period (day_type, time_period)
) COMMENT '地铁站客流时序数据表';
```

#### audience_distribution（人群分布表）

```sql
CREATE TABLE IF NOT EXISTS audience_distribution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id BIGINT NOT NULL COMMENT '地铁站 ID',
    segment_name VARCHAR(100) NOT NULL COMMENT '人群名称',
    ratio DECIMAL(5,4) NOT NULL COMMENT '占比',
    day_type VARCHAR(20) NOT NULL COMMENT 'weekday/weekend',
    time_period VARCHAR(20) NOT NULL COMMENT '时段',
    data_version VARCHAR(20) NOT NULL COMMENT '数据版本',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_station_segment (station_id, segment_name),
    INDEX idx_data_version (data_version)
) COMMENT '地铁站人群分布时序表';
```

#### campaign_report（投放报告表）

```sql
CREATE TABLE IF NOT EXISTS campaign_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL COMMENT '投放活动 ID',
    report_type VARCHAR(20) NOT NULL COMMENT 'pre/post',
    predicted_impression INT COMMENT '预测曝光量（投前）',
    actual_impression INT COMMENT '实际曝光量（投后）',
    predicted_reach INT COMMENT '预测触达人数',
    actual_reach INT COMMENT '实际触达人数',
    roi DECIMAL(10,4) COMMENT '投资回报率',
    algorithm_version VARCHAR(50) COMMENT '算法版本',
    data_version VARCHAR(20) COMMENT '数据版本',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_campaign_type (campaign_id, report_type)
) COMMENT '投前/投后报告表（Java 计算后持久化）';
```

### 5.2 Neo4j 图模型

```cypher
// 创建节点约束
CREATE CONSTRAINT brand_name_unique FOR (b:Brand) REQUIRE b.name IS UNIQUE;
CREATE CONSTRAINT city_name_unique FOR (c:City) REQUIRE c.name IS UNIQUE;
CREATE CONSTRAINT industry_name_unique FOR (i:Industry) REQUIRE i.name IS UNIQUE;
CREATE CONSTRAINT station_name_unique FOR (s:MetroStation) REQUIRE s.name IS UNIQUE;
CREATE CONSTRAINT line_name_unique FOR (l:MetroLine) REQUIRE l.line_name IS UNIQUE;
CREATE CONSTRAINT ad_location_name_unique FOR (al:AdLocation) REQUIRE al.name IS UNIQUE;

// 创建索引
CREATE INDEX brand_industry_idx FOR (b:Brand) ON (b.industry);
CREATE INDEX campaign_date_idx FOR (c:Campaign) ON (c.start_date);
CREATE INDEX station_city_idx FOR (s:MetroStation) ON (s.city);
CREATE INDEX ad_location_station_idx FOR (al:AdLocation) ON (al.station_id);

// 示例：创建原有节点
CREATE (b:Brand {
    name: "肯德基",
    industry: "快餐",
    annual_revenue: 1000000000,
    aliases: ["KFC", "肯德基中国"]
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

// 示例：创建新增节点
CREATE (aud:AudienceSegment {
    name: "年轻职场人群",
    description: "20-35岁白领",
    age_range: "20-35",
    income_level: "medium-high"
})

CREATE (ml:MetroLine {
    line_name: "上海2号线",
    city: "上海",
    operator: "上海地铁"
})

CREATE (ms:MetroStation {
    name: "南京西路站",
    city: "上海",
    daily_traffic: 450000,
    line_count: 3,
    aliases: ["南京西路", "上海地铁南京西路站"]
})

CREATE (ba:BusinessArea {
    name: "南京西路商圈",
    city: "上海",
    type: "商业",
    level: "核心"
})

CREATE (al:AdLocation {
    name: "南京西路站站厅数字大屏",
    station_id: 1,
    format: "digital_screen",
    estimated_impressions_per_hour: 12000,
    status: "available"
})

CREATE (case:Case {
    title: "肯德基南京西路站早餐系列投放案例",
    brand: "肯德基",
    summary: "针对早高峰通勤人群投放早餐系列广告",
    outcome: "日均曝光12万次，门店客流增长15%"
})

// 创建原有关系
MATCH (b:Brand {name: "肯德基"})
MATCH (c:City {name: "上海"})
MATCH (sm:StoreMetric {date: "2024-04"})
MATCH (camp:Campaign {start_date: "2024-05"})
CREATE (b)-[:OPERATES_IN {market_share: 0.15}]->(c)
CREATE (b)-[:HAS_METRIC]->(sm)
CREATE (b)-[:LAUNCHED {budget: 1000000}]->(camp)
CREATE (camp)-[:LOCATED_IN]->(c)

// 创建新增关系
MATCH (b:Brand {name: "肯德基"})
MATCH (aud:AudienceSegment {name: "年轻职场人群"})
MATCH (ms:MetroStation {name: "南京西路站"})
MATCH (ml:MetroLine {line_name: "上海2号线"})
MATCH (ba:BusinessArea {name: "南京西路商圈"})
MATCH (al:AdLocation {name: "南京西路站站厅数字大屏"})
MATCH (camp:Campaign {start_date: "2024-05"})
MATCH (case:Case {title: "肯德基南京西路站早餐系列投放案例"})
CREATE (b)-[:TARGETS {weight: 0.8}]->(aud)
CREATE (aud)-[:CONCENTRATED_AT {ratio: 0.42, period: "weekday_morning", data_version: "2025-03"}]->(ms)
CREATE (ms)-[:BELONGS_TO_LINE]->(ml)
CREATE (ms)-[:LOCATED_IN_AREA]->(ba)
CREATE (ms)-[:HAS_AD_LOCATION]->(al)
CREATE (camp)-[:PLACED_AT {duration: 30}]->(al)
CREATE (camp)-[:TARGETED]->(aud)
CREATE (camp)-[:HAS_CASE]->(case)
```

### 5.3 Milvus 向量集合

```python
# 伪代码：Milvus 集合定义
from pymilvus import CollectionSchema, FieldSchema, DataType

# Case 向量集合（案例语义召回）
fields = [
    FieldSchema(name="id", dtype=DataType.INT64, is_primary=True, auto_id=True),
    FieldSchema(name="case_id", dtype=DataType.INT64),
    FieldSchema(name="brand", dtype=DataType.VARCHAR, max_length=200),
    FieldSchema(name="city", dtype=DataType.VARCHAR, max_length=100),
    FieldSchema(name="title", dtype=DataType.VARCHAR, max_length=500),
    FieldSchema(name="description", dtype=DataType.VARCHAR, max_length=2000),
    FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=1536)
]

schema = CollectionSchema(fields=fields, description="Case vector collection")
collection = Collection(name="cases", schema=schema)

# Document 向量集合（文档语义召回）
doc_fields = [
    FieldSchema(name="id", dtype=DataType.INT64, is_primary=True, auto_id=True),
    FieldSchema(name="doc_id", dtype=DataType.INT64),
    FieldSchema(name="title", dtype=DataType.VARCHAR, max_length=500),
    FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=4000),
    FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=1536)
]
doc_schema = CollectionSchema(fields=doc_fields, description="Document vector collection")
doc_collection = Collection(name="documents", schema=doc_schema)

# ImageAsset 向量集合（图片描述语义召回）
img_fields = [
    FieldSchema(name="id", dtype=DataType.INT64, is_primary=True, auto_id=True),
    FieldSchema(name="asset_id", dtype=DataType.INT64),
    FieldSchema(name="title", dtype=DataType.VARCHAR, max_length=500),
    FieldSchema(name="description", dtype=DataType.VARCHAR, max_length=1000),
    FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=1536)
]
img_schema = CollectionSchema(fields=img_fields, description="ImageAsset vector collection")
img_collection = Collection(name="image_assets", schema=img_schema)

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
| **双语言架构（Java + Python）** | "我主要写 Java 数据库 CRUD，并调用团队已有算法完成投前和投后报告计算；这些能力由团队进一步封装成 MCP 工具供 Agent 使用。Python 侧负责 Agent 编排、GraphRAG 检索和答案生成。" |
| **Leiden 社区发现（可选优化）** | "我们核心是领域知识图谱和向量检索。社区发现属于后续可选优化，我没有参与这一部分，也不能确认真实系统是否采用。分三阶段演进：第一阶段固定 Schema + Cypher 模板，第二阶段实体对齐 + 混合检索，第三阶段才考虑 Leiden。" |
| **GraphRAG 三种检索场景** | "我没有用一条固定查询路径，而是根据用户意图路由到三种检索器：宽泛点位推荐、指定站点评估、历史案例检索。每种场景走不同的图遍历路径和 MCP 工具组合。" |
| **查询理解与路由层** | "LLM 先提取实体和意图，输出 JSON（intent/brand/city/station 等），路由器再选择对应的 Cypher 模板、向量检索器和 MCP 工具。不是 Neo4j 自己判断，而是先理解再路由。" |
| **数据治理** | "企业级知识库最关键的是数据治理。我做了实体统一（KFC/肯德基/肯德基中国映射到同一个 Brand）、数据溯源（每个节点有 source_id/data_version/confidence）、权限控制（检索时先做权限过滤）。" |
| **地铁广告领域模型** | "图谱不只是 Brand/City/Campaign，我设计了人群画像（AudienceSegment）、地铁网络（MetroStation/MetroLine/BusinessArea）、广告资源（AdLocation/AdFormat）、历史案例（Case/Document/ImageAsset）四大维度。" |
| **效果图生成工作流** | "人工制作广告效果图原来需要 3-4 天，平台辅助后约 1 天完成。这是业务交付效率的提升，不是接口延迟。" |

### 9.2 高频面试题

1. **Q：GraphRAG 和普通 RAG 有什么区别？**
   - A：普通 RAG 只能做语义相似匹配，GraphRAG 支持多跳推理（品牌->人群->站点->广告位->历史案例），保留业务逻辑关联。我们根据用户意图路由到三种检索场景，不是一条固定路径。

2. **Q：Neo4j、Milvus 和 MySQL 分别存什么？为什么不能全放进一个数据库？**
   - A：Neo4j 存品牌、人群、站点、广告位、活动、案例的关系，需要多跳推理；Milvus 存报告、案例、文档、图片描述的向量，做语义召回；MySQL 存客流、曝光、人群比例、投放记录、报告结果等结构化时序数据，由 Java CRUD 和计算。三种数据特性不同，一个数据库无法同时满足图遍历、向量检索和关系查询的需求。

3. **Q：Agent 是怎么编排工具的？**
   - A：Agent 先通过 LLM 做查询理解，提取 intent/brand/city/station 等实体，然后路由到对应的检索器或 MCP 工具。工具包括 search_graphrag_cases、query_station_profile、calculate_pre_campaign_report 等，通过 Function Calling 动态调用。

4. **Q：Java 和 Python 怎么分工的？**
   - A：Java 负责业务平台（地铁站/广告位 CRUD、人群画像查询、投前投后报告计算、曝光算法调用），Python 负责 AI 模块（Agent 编排、GraphRAG 检索、向量召回、答案生成、效果图工作流）。Java 能力封装成 MCP 工具供 Python Agent 调用。我主要写 Java 部分。

5. **Q：效果图生成是怎么做的？**
   - A：Agent 调用 generate_ad_mockup MCP，先调用即梦 AI 绘画生成概念图，再调用即梦视频生成动态视频。人工制作原来需要 3-4 天，平台辅助后约 1 天完成（业务交付效率，非接口延迟）。

6. **Q：Leiden 社区发现算法是什么？**
   - A：Leiden 是图聚类算法，属于我们规划的第三阶段可选优化。我们核心是领域知识图谱和向量检索，社区发现我没有参与，不能确认真实系统是否采用。

7. **Q：数据治理怎么做的？**
   - A：实体统一（KFC/肯德基/肯德基中国映射到同一个 Brand，南京西路/南京西路站实体对齐）、数据溯源（source_id/source_type/data_version/confidence）、权限控制（public/sales_internal/department_restricted/confidential，检索时先过滤）、时效性管理（人流和人群画像按 weekday/weekend/morning/evening 分版本）。

---

## 十、风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| **豆包 API 限流** | 高频调用被限流 | 实现重试机制 + 降级策略（返回缓存结果） |
| **Neo4j 查询慢** | 多跳查询性能差 | 优化 Cypher 查询 + 添加索引（第三阶段可选 Leiden 社区预加载） |
| **Milvus 内存不足** | 向量数据量大 | 分批加载 + 定期清理过期数据 |
| **视频生成失败** | 即梦 API 调用失败 | 失败重试（最多 3 次）+ 降级返回概念图 |
| **云端内存不足** | 16GB 内存紧张 | 严格限制各组件内存 + 监控告警 |
| **Java 与 Python 跨语言调用** | MCP 协议稳定性 | MCP 工具超时降级 + 本地缓存 |

---

## 十一、下一步

1. **按每日任务拆解执行**：从 Day 1 开始，每天完成对应任务
2. **每日复盘**：记录遇到的问题和解决方案
3. **面试准备**：Day 14 整理面试讲点，模拟面试问答

---

> 📅 创建时间：2026-07-03
> 🎯 目标：14 天完成德高 AI 智能决策系统，形成 Java + Python 双语言经历
> 🚀 开始执行！
