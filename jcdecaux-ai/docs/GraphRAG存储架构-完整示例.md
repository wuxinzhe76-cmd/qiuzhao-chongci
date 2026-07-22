# GraphRAG 存储架构完整示例（多数据源场景）

> 📅 2026-07-18
> 场景：奶茶品牌户外广告决策系统，5 个数据源，离线知识库 + 在线 MCP 工具

---

## 一、5 个数据源及其处理方式

| 数据源 | 内容 | 数据归属 | 处理方式 |
|--------|------|---------|---------|
| 1. 奶茶品牌信息 | 品牌名、行业、营收、门店数 | 公开数据（企查查/天眼查） | **离线抓取 -> 存入知识库** |
| 2. 地图数据 | 地铁站 POI、商圈、人流量 | 高德地图 API | **在线调用 MCP，不存入知识库** |
| 3. 行业报告 | 茶饮行业趋势、扩张策略 | 第三方报告 PDF | **离线下载 -> 存入知识库** |
| 4. 内部投放数据 | 哪个品牌在哪个地铁投了广告 | 公司内部数据库 | **离线导入 -> 存入知识库** |
| 5. 地铁官方数据 | 地铁站日均人流量 | 地铁公司公开数据 | **离线抓取 -> 存入知识库** |

### 核心原则

```
┌─────────────────────────────────────────────────────────┐
│  能离线获取的数据 -> 存入 GraphRAG 知识库（Neo4j+Milvus）│
│  （品牌信息、行业报告、内部数据、地铁人流量）            │
├─────────────────────────────────────────────────────────┤
│  需要实时获取的数据 -> 通过 MCP 工具在线调用             │
│  （高德实时人流、博查实时新闻、即梦图片生成）            │
└─────────────────────────────────────────────────────────┘
```

---

## 二、Neo4j 里存什么（离线知识图谱）

### 2.1 节点（Node）清单

```cypher
// ========== 来自数据源1：奶茶品牌信息 ==========
(:Brand {
    name: "喜茶",
    industry: "快消",
    sub_category: "新式茶饮",
    annual_revenue: 5000000000,
    founded_year: 2012,
    description: "喜茶是国内知名新式茶饮品牌，主打芝士奶盖茶"
})

(:Brand {
    name: "霸王茶姬",
    industry: "快消",
    sub_category: "新式茶饮",
    annual_revenue: 3000000000,
    founded_year: 2017,
    description: "霸王茶姬是国风茶饮品牌，主打原叶鲜奶茶"
})

// ========== 来自数据源1 + 数据源5：城市和地铁 ==========
(:City {
    name: "杭州",
    tier: "新一线",
    population: 12000000,
    description: "浙江省会，新一线城市"
})

(:MetroStation {
    name: "凤起路站",
    line: "地铁1号线",
    daily_traffic: 350000,  // 日均人流量（来自数据源5）
    location: "杭州核心商圈"
})

// ========== 来自数据源1：门店指标 ==========
(:StoreMetric {
    store_count: 50,
    avg_sales_per_store: 200000,
    date: "2024-04",
    growth_rate: 0.15  // 环比增长15%
})

// ========== 来自数据源4：内部投放数据 ==========
(:Campaign {
    name: "喜茶杭州地铁1号线凤起路站灯箱广告202405",
    location_type: "地铁灯箱",
    start_date: "2024-05-01",
    duration: 30,
    impression: 5000000,  // 曝光量
    budget: 1000000,      // 预算
    conversion_rate: 0.15  // 转化率
})

// ========== 来自数据源3：行业报告 ==========
(:Report {
    title: "2024新式茶饮行业户外投放趋势报告",
    publish_date: "2024-06-01",
    source: "36氪",
    key_finding: "茶饮品牌扩张期户外广告投放预算平均增长2.1倍",
    content_summary: "2024年新式茶饮品牌在扩张期的户外广告投放..."
})
```

### 2.2 边（Edge/关系）清单

```cypher
// 喜茶在杭州有门店（来自数据源1）
(:Brand {name: "喜茶"})-[:OPERATES_IN {since: "2013", store_count: 50}]->(:City {name: "杭州"})

// 喜茶的门店指标（来自数据源1）
(:Brand {name: "喜茶"})-[:HAS_METRIC {date: "2024-04"}]->(:StoreMetric {store_count: 50})

// 喜茶投放了广告（来自数据源4）
(:Brand {name: "喜茶"})-[:LAUNCHED {budget: 1000000}]->(:Campaign {name: "喜茶杭州地铁..."})

// 广告投放在凤起路站（来自数据源4）
(:Campaign {name: "喜茶杭州地铁..."})-[:LOCATED_IN {position: "B口灯箱"}]->(:MetroStation {name: "凤起路站"})

// 凤起路站在杭州（来自数据源5）
(:MetroStation {name: "凤起路站"})-[:LOCATED_IN]->(:City {name: "杭州"})

// 喜茶属于快消行业（来自数据源1）
(:Brand {name: "喜茶"})-[:BELONGS_TO {sub_category: "新式茶饮"}]->(:Industry {name: "快消"})

// 行业报告提到喜茶（来自数据源3）
(:Report {title: "2024新式茶饮..."})-[:MENTIONS {context: "扩张期投放案例"}]->(:Brand {name: "喜茶"})

// 两个投放案例相似（来自数据源3的对比分析）
(:Campaign {name: "喜茶杭州地铁..."})-[:SIMILAR_TO {similarity: 0.85}]->(:Campaign {name: "霸王茶姬杭州地铁..."})
```

### 2.3 Neo4j 图谱可视化（文字版）

```
                    ┌─────────────┐
                    │  快消(Industry)│
                    └──────┬──────┘
                           │ BELONGS_TO
                    ┌──────▼──────┐
                    │ 喜茶(Brand)  │
                    └──┬───┬───┬──┘
           OPERATES_IN  │   │   │ LAUNCHED
                    ┌───▼┐  │   └──────▼──────────┐
                    │杭州 │  │                  │喜茶杭州地铁广告
                    │(City)│ │ HAS_METRIC       │(Campaign)
                    └──┬──┘  │                  └───┬───────┬───┘
                       │     └───────┐              │       │
           LOCATED_IN  │             │      LOCATED_IN   SIMILAR_TO
                    ┌──▼──┐    ┌─────▼──────┐  ┌─────▼──┐    └──────────────┐
                    │凤起路│    │StoreMetric │  │凤起路站│    │霸王茶姬杭州地铁│
                    │地铁站 │    │50家,20万   │  │(Metro) │    │(Campaign)     │
                    │(Metro)│   └────────────┘  └────────┘    └───────────────┘
                    └──────┘
```

---

## 三、Milvus 里存什么（离线向量库）

### 3.1 向量集合（Collection）设计

```python
# Milvus 集合1：文本块向量（所有文档的文本块）
collection_1 = {
    "name": "text_chunks",
    "fields": [
        {"name": "id", "type": "INT64", "is_primary": True, "auto_id": True},
        {"name": "embedding", "type": "FLOAT_VECTOR", "dim": 1536},
        {"name": "text_chunk", "type": "VARCHAR", "max_length": 2000},  # 原始文本
        {"name": "entity_name", "type": "VARCHAR", "max_length": 200},  # 关联实体名
        {"name": "entity_type", "type": "VARCHAR", "max_length": 50},   # 实体类型
        {"name": "source_doc", "type": "VARCHAR", "max_length": 200},   # 来源文档
        {"name": "chunk_id", "type": "VARCHAR", "max_length": 50}       # 文本块ID
    ]
}

# Milvus 集合2：社区摘要向量（Leiden 社区发现后生成）
collection_2 = {
    "name": "community_summaries",
    "fields": [
        {"name": "id", "type": "INT64", "is_primary": True, "auto_id": True},
        {"name": "embedding", "type": "FLOAT_VECTOR", "dim": 1536},
        {"name": "community_id", "type": "INT64"},
        {"name": "summary", "type": "VARCHAR", "max_length": 1000}  # 社区摘要文本
    ]
}
```

### 3.2 具体存了哪些向量记录

```python
# ========== 来自数据源1：奶茶品牌信息 ==========
{
    "embedding": embed("喜茶是国内知名新式茶饮品牌，主打芝士奶盖茶，2024年营收约50亿"),
    "text_chunk": "喜茶是国内知名新式茶饮品牌，主打芝士奶盖茶，2024年营收约50亿",
    "entity_name": "喜茶",      # 👈 关联 Neo4j 的钥匙
    "entity_type": "Brand",
    "source_doc": "企查查_喜茶.pdf",
    "chunk_id": "chunk_001"
}

{
    "embedding": embed("霸王茶姬是国风茶饮品牌，主打原叶鲜奶茶，2024年营收约30亿"),
    "text_chunk": "霸王茶姬是国风茶饮品牌，主打原叶鲜奶茶，2024年营收约30亿",
    "entity_name": "霸王茶姬",
    "entity_type": "Brand",
    "source_doc": "企查查_霸王茶姬.pdf",
    "chunk_id": "chunk_002"
}

# ========== 来自数据源3：行业报告 ==========
{
    "embedding": embed("2024年新式茶饮品牌在扩张期的户外广告投放预算平均增长2.1倍，" +
                       "其中地铁灯箱广告占比最高，转化率提升15-30%"),
    "text_chunk": "2024年新式茶饮品牌在扩张期的户外广告投放预算平均增长2.1倍...",
    "entity_name": "喜茶",      # 报告中提到了喜茶
    "entity_type": "Brand",
    "source_doc": "36氪_2024茶饮行业报告.pdf",
    "chunk_id": "chunk_003"
}

# ========== 来自数据源4：内部投放数据 ==========
{
    "embedding": embed("喜茶2024年5月在杭州地铁1号线凤起路站投放灯箱广告，" +
                       "预算100万，曝光500万次，转化率15%"),
    "text_chunk": "喜茶2024年5月在杭州地铁1号线凤起路站投放灯箱广告，预算100万...",
    "entity_name": "喜茶杭州地铁灯箱广告202405",
    "entity_type": "Campaign",
    "source_doc": "内部投放记录_DB",
    "chunk_id": "chunk_004"
}

# ========== 来自数据源5：地铁人流量 ==========
{
    "embedding": embed("杭州地铁1号线凤起路站日均人流量35万，位于核心商圈"),
    "text_chunk": "杭州地铁1号线凤起路站日均人流量35万，位于核心商圈",
    "entity_name": "凤起路站",
    "entity_type": "MetroStation",
    "source_doc": "杭州地铁官方数据.json",
    "chunk_id": "chunk_005"
}
```

---

## 四、外部数据源怎么处理（在线 MCP 调用）

### 4.1 哪些数据不存入知识库

```
❌ 高德地图 API：实时人流量、周边商圈（数据实时变化，且无法全量下载）
❌ 博查 API：实时新闻舆情（每天有新内容，无法预存）
❌ 即梦 API：图片/视频生成（按需生成，不需要存储）
```

### 4.2 MCP 工具架构

```python
class MCPToolRegistry:
    """MCP 工具注册中心：外部数据源通过 MCP 在线调用"""
    
    def __init__(self):
        self.tools = {
            # 高德地图：实时查询地铁站周边数据
            "amap_search": AmapMCPTool(),
            # 博查：实时查询品牌新闻
            "bocha_search": BochaMCPTool(),
            # 即梦：生成广告图片/视频
            "jimeng_generate": JimengMCPTool()
        }

class AmapMCPTool:
    """高德地图 MCP 工具：在线查询，结果不存入知识库"""
    
    async def search(self, station_name: str, radius: int = 500) -> dict:
        """查询地铁站周边商圈和人流量"""
        # 调用高德 API（实时数据，不存入知识库）
        response = await http_client.get(
            "https://restapi.amap.com/v3/place/around",
            params={
                "key": AMAP_API_KEY,
                "location": await self._get_station_location(station_name),
                "radius": radius,
                "types": "商场|购物中心"
            }
        )
        return {
            "station": station_name,
            "nearby_malls": response["pois"],
            "realtime_traffic": response["traffic"]
        }

class BochaMCPTool:
    """博查 MCP 工具：在线查询实时新闻"""
    
    async def search(self, brand_name: str) -> dict:
        """查询品牌最新新闻舆情"""
        response = await http_client.get(
            "https://api.bochaai.com/v1/search",
            params={"q": brand_name, "type": "news", "days": 7}
        )
        return {
            "brand": brand_name,
            "latest_news": response["news"][:5]
        }
```

### 4.3 在线数据 vs 离线数据的分工

```
┌──────────────────────────────────────────────────────────┐
│                    查询时的数据融合                       │
├──────────────────────┬───────────────────────────────────┤
│   离线知识库          │   在线 MCP 工具                   │
│   (Neo4j + Milvus)   │   (高德 + 博查 + 即梦)             │
├──────────────────────┼───────────────────────────────────┤
│ 历史投放数据          │ 实时人流量                        │
│ 门店数量和坪效        │ 实时新闻舆情                      │
│ 行业报告内容          │ 周边商圈实时数据                   │
│ 地铁站基础信息        │ 广告图片/视频生成                  │
├──────────────────────┼───────────────────────────────────┤
│ 特点：稳定、可追溯    │ 特点：实时、动态                  │
│ 延迟：100ms-1s       │ 延迟：500ms-3s                    │
└──────────────────────┴───────────────────────────────────┘
```

---

## 五、完整查询流程（离线知识 + 在线数据融合）

### 用户提问

> "喜茶明年继续在杭州扩张，应该在哪个地铁站投放广告？依据是什么？"

### 完整流程

```python
class GraphRAGWithMCP:
    """GraphRAG + MCP 融合检索"""
    
    def __init__(self, graph_rag, mcp_tools):
        self.graph_rag = graph_rag  # 离线知识库
        self.mcp = mcp_tools        # 在线 MCP 工具
    
    async def query(self, question: str) -> dict:
        # ========== 第一路：GraphRAG 离线检索 ==========
        
        # Step 1: 向量检索定位入口实体
        vector_results = self.graph_rag.vector_search(question)
        # 返回：entity_name="喜茶", entity_name="杭州"
        
        # Step 2: 图遍历获取结构化数据
        graph_data = self.graph_rag.graph_traverse("喜茶", "杭州")
        # 返回：
        # {
        #   "store_count": 50,           # 喜茶杭州50家门店
        #   "avg_sales": 200000,          # 坪效20万
        #   "growth_rate": 0.15,          # 环比+15%（扩张期）
        #   "past_campaign": {
        #     "name": "喜茶杭州地铁灯箱广告202405",
        #     "impression": 5000000,     # 曝光500万
        #     "conversion_rate": 0.15    # 转化率15%
        #   },
        #   "similar_campaign": {
        #     "name": "霸王茶姬杭州地铁广告",
        #     "conversion_rate": 0.30,   # 转化率30%
        #     "similarity": 0.85
        #   }
        # }
        
        # Step 3: 社区摘要检索（全局视角）
        community_data = self.graph_rag.community_search(question)
        # 返回：社区摘要"华东快消品牌扩张期户外投放预算+2.1倍"
        
        # ========== 第二路：MCP 在线获取实时数据 ==========
        
        # Step 4: 调用高德 MCP，获取杭州各地铁站实时人流量
        metro_data = await self.mcp["amap_search"].search_metro_stations("杭州")
        # 返回：
        # [
        #   {"station": "凤起路站", "daily_traffic": 350000, "nearby_malls": 3},
        #   {"station": "武林广场站", "daily_traffic": 420000, "nearby_malls": 5},
        #   {"station": "龙翔桥站", "daily_traffic": 380000, "nearby_malls": 4}
        # ]
        
        # Step 5: 调用博查 MCP，获取喜茶最新新闻
        news_data = await self.mcp["bocha_search"].search("喜茶")
        # 返回：
        # {"latest_news": "喜茶获5亿融资，计划2025年新增200家门店"}
        
        # ========== 融合两路数据 ==========
        
        # Step 6: 融合离线知识 + 在线数据
        context = {
            "offline": {
                "graph_data": graph_data,        # 历史投放、门店数据
                "community": community_data       # 行业趋势
            },
            "online": {
                "metro_traffic": metro_data,      # 实时人流量
                "latest_news": news_data          # 最新新闻
            }
        }
        
        # Step 7: LLM 生成答案
        answer = await self.llm.generate(question, context)
        return answer
```

### LLM 最终生成的回答

```
建议喜茶明年在杭州武林广场站投放地铁灯箱广告。

【依据】

1. 历史数据（来自知识图谱）：
   - 喜茶杭州现有50家门店，坪效20万/月，环比增长15%（扩张期）
   - 2024年5月凤起路站灯箱广告曝光500万，转化率15%，效果验证

2. 实时人流量（来自高德 MCP）：
   - 武林广场站日均42万人流，高于凤起路站35万
   - 武林广场站周边5个商场，商业密度最高

3. 相似案例（来自知识图谱 SIMILAR_TO 关系）：
   - 霸王茶姬杭州地铁广告转化率30%，相似度0.85
   - 行业报告显示扩张期品牌投放预算平均+2.1倍

4. 实时信号（来自博查 MCP）：
   - 喜茶刚获5亿融资，计划新增200家门店，预算释放概率高

【置信度】高（88%）
【推理链】
喜茶扩张期(知识图谱) + 武林广场人流最高(高德MCP) + 
同类案例验证(知识图谱) + 融资信号(博查MCP) -> 推荐武林广场站
```

---

## 六、总结：Neo4j vs Milvus vs MCP 的分工

| 存储/工具 | 存什么 | 数据来源 | 检索方式 | 延迟 |
|----------|--------|---------|---------|------|
| **Neo4j** | 节点(文本属性)+边(关系) | 离线构建（数据源1/3/4/5） | Cypher 图遍历 | 100-500ms |
| **Milvus** | 向量+文本块+entity_name | 离线构建（同上） | 向量相似度 | 50-200ms |
| **MCP-高德** | ❌ 不存储 | 在线调用 API | HTTP 请求 | 500ms-2s |
| **MCP-博查** | ❌ 不存储 | 在线调用 API | HTTP 请求 | 500ms-3s |
| **MCP-即梦** | ❌ 不存储 | 在线生成 | HTTP 请求 | 2-5min |

### 数据流向图

```
离线构建阶段（一次性）：
  数据源1(品牌) ─┐
  数据源3(报告) ─┼─> LLM抽取实体关系 ─> Neo4j(图) + Milvus(向量)
  数据源4(内部) ─┤
  数据源5(地铁) ─┘

在线查询阶段（每次查询）：
  用户查询
    ├── GraphRAG 离线检索 ──> Neo4j + Milvus ──> 历史数据 + 结构化关系
    ├── MCP 高德在线调用 ──> 实时人流量
    ├── MCP 博查在线调用 ──> 实时新闻
    └── 融合 ─> LLM 生成答案
```

> **核心原则**：能离线获取的数据存入知识库（Neo4j+Milvus），需要实时获取的数据通过 MCP 在线调用。两者在查询时融合，离线知识提供"历史规律 + 结构化关系"，在线 MCP 提供"实时信号"。
