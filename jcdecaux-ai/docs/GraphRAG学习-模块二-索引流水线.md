# 模块二：GraphRAG 索引流水线与存储架构

> 📅 2026-07-18
> 重点解答：Neo4j 节点存什么？向量库存什么？两个库怎么协作？

---

## 2.1 GraphRAG 存储架构（核心解惑）

### 2.1.1 双路存储架构总览

GraphRAG 采用**双路存储**：图数据库 + 向量数据库，各司其职。

```
┌─────────────────────────────────────────────────────────────┐
│                    GraphRAG 双路存储架构                      │
├──────────────────────────┬──────────────────────────────────┤
│      Neo4j（图数据库）     │      Milvus（向量数据库）          │
│                          │                                  │
│  存什么：图结构           │  存什么：向量 + 文本片段           │
│  - 节点(Node)：实体       │  - 向量(embedding)：文本的向量表示  │
│  - 边(Edge)：关系         │  - 原文(text)：对应的文本片段       │
│  - 属性：文本/数值        │  - 实体引用(entity_name)：关联节点  │
│                          │                                  │
│  检索方式：Cypher 图遍历   │  检索方式：向量相似度              │
│  解决：多跳推理           │  解决：语义匹配（定位入口实体）      │
│                          │                                  │
│  ❌ 不存向量              │  ❌ 不存图结构关系                 │
│  ✅ 存文本属性            │  ✅ 存向量 + 文本                  │
└──────────────────────────┴──────────────────────────────────┘
                    ↕ 通过 entity_name 关联
```

### 2.1.2 Neo4j 节点里到底存什么？

**Neo4j 节点存的是文本属性，不是向量。**

```cypher
// Neo4j 中的一个 Brand 节点长这样：
(:Brand {
    name: "XX茶饮",              // 实体名（文本）
    industry: "快消",             // 行业（文本）
    annual_revenue: 1000000000,  // 年营收（数值）
    description: "XX茶饮是一家连锁奶茶品牌，主打新式茶饮",  // 描述（文本）
    created_at: "2024-05-01"     // 元数据
})

// 边长这样：
(:Brand)-[:OPERATES_IN {market_share: 0.15, since: "2023-01"}]->(:City)
//         关系类型          属性：市场份额、开始时间
```

**Neo4j 节点的属性分类**：

| 属性类型 | 例子 | 用途 |
|---------|------|------|
| 标识属性 | name, id | 唯一标识节点，用于关联向量库 |
| 业务属性 | industry, annual_revenue, store_count | 业务数据，图遍历时返回 |
| 描述属性 | description, summary | 实体的自然语言描述 |
| 元数据 | created_at, source | 数据溯源 |

> ❌ **Neo4j 节点不存向量**（传统方案）。虽然 Neo4j 5.x 支持原生向量索引，但生产中通常用分离存储方案：Neo4j 管图结构，Milvus 管向量。

### 2.1.3 Milvus 向量库里存什么？

```python
# Milvus 中的一条向量记录长这样：
{
    "id": 1,                           # 自增主键
    "entity_name": "XX茶饮",            # 关联的实体名（用于关联 Neo4j）
    "entity_type": "Brand",             # 实体类型
    "text_chunk": "XX茶饮是一家连锁奶茶品牌，2024年在杭州有50家门店，月均销售额20万",  # 原始文本片段
    "embedding": [0.12, -0.34, 0.56, ...],  # 向量（1536维，由 Embedding 模型生成）
    "source_doc": "2024快消行业报告.pdf",   # 来源文档
    "chunk_id": "chunk_005"              # 文本块ID
}
```

**Milvus 存储内容分类**：

| 字段 | 类型 | 用途 |
|------|------|------|
| embedding | FLOAT_VECTOR(1536) | 向量相似度检索的核心 |
| entity_name | VARCHAR | **关联 Neo4j 节点的桥梁** |
| text_chunk | VARCHAR | 检索到后返回给 LLM 的文本内容 |
| source_doc | VARCHAR | 数据溯源 |
| entity_type | VARCHAR | 过滤辅助 |

### 2.1.4 两个库怎么协作？（检索流程）

```
用户提问："XX茶饮在杭州的门店扩张对广告投放有什么影响？"
  │
  ├── Step 1: Query Rewrite（LLM 改写查询）
  │   "XX茶饮 杭州门店扩张 广告投放影响" -> 改写为更利于检索的表述
  │
  ├── Step 2: 向量检索（Milvus）—— 定位"入口实体"
  │   query_embedding = embed("XX茶饮杭州门店扩张广告投放")
  │   Milvus 相似度检索 -> top_k=5
  │   返回：
  │     - {entity_name: "XX茶饮", text_chunk: "XX茶饮2024年杭州门店50家...", score: 0.92}
  │     - {entity_name: "杭州", text_chunk: "杭州是新一线城市...", score: 0.85}
  │   作用：找到"从哪个节点开始遍历图" -> 入口实体 = "XX茶饮" + "杭州"
  │
  ├── Step 3: 图遍历（Neo4j）—— 多跳推理扩展
  │   从入口实体出发，Cypher 多跳查询：
  │   MATCH (b:Brand {name: "XX茶饮"})-[:OPERATES_IN]->(c:City {name: "杭州"})
  │   MATCH (b)-[:HAS_METRIC]->(m:StoreMetric)
  │   MATCH (b)-[:LAUNCHED]->(camp:Campaign)-[:LOCATED_IN]->(c)
  │   返回：门店数50、坪效20万、历史广告曝光500万
  │   作用：获取结构化的关联数据（向量检索做不到的多跳推理）
  │
  ├── Step 4: 融合两路结果
  │   向量结果：语义相关的文本片段（提供背景知识）
  │   图遍历结果：结构化业务数据（提供精准事实）
  │   融合：text_chunk + graph_data -> 完整上下文
  │
  └── Step 5: LLM 生成答案
      基于融合上下文生成回答
```

**代码示例：双路检索协作**

```python
class GraphRAGRetriever:
    """双路检索：向量库定位入口 + 图库多跳扩展"""
    
    def __init__(self, milvus_client, neo4j_driver, embedder):
        self.milvus = milvus_client
        self.neo4j = neo4j_driver
        self.embedder = embedder
    
    def retrieve(self, query: str) -> dict:
        # Step 1: 向量检索 —— 找到入口实体（Milvus）
        query_vector = self.embedder.embed(query)
        vector_results = self.milvus.search(
            collection="entities",
            data=[query_vector],
            top_k=5,
            output_fields=["entity_name", "entity_type", "text_chunk"]
        )
        
        # 提取入口实体名（用于图遍历）
        entry_entities = [
            {"name": r.entity_name, "type": r.entity_type}
            for r in vector_results[0]
        ]
        
        # Step 2: 图遍历 —— 多跳推理扩展（Neo4j）
        graph_results = []
        with self.neo4j.session() as session:
            for entity in entry_entities:
                # 从入口实体出发，获取 2 跳邻域子图
                cypher = f"""
                MATCH path = (n:{entity['type']} {{name: $name}})-[*1..2]-(related)
                RETURN nodes(path) as nodes, relationships(path) as rels
                LIMIT 50
                """
                result = session.run(cypher, name=entity["name"])
                graph_results.extend([r.data() for r in result])
        
        # Step 3: 融合两路结果
        return {
            "vector_context": vector_results,  # 语义相关文本（背景知识）
            "graph_context": graph_results,     # 结构化子图（精准事实）
            "entry_entities": entry_entities    # 入口实体
        }
```

### 2.1.5 为什么不把向量也存在 Neo4j 里？

Neo4j 5.x 确实支持原生向量索引，但生产中通常用分离存储，原因：

| 维度 | Neo4j 存向量 | 分离存储（Neo4j+Milvus） |
|------|------------|----------------------|
| 向量检索性能 | 一般（非专用） | 高（Milvus 是专用向量库） |
| 大规模向量 | 百万级变慢 | 十亿级可用 |
| 图遍历性能 | 好 | 好（Neo4j 专长） |
| 运维复杂度 | 低（一个库） | 中（两个库） |
| 生产推荐 | 小规模(<10万向量) | 大规模(>10万向量) |

> 德高项目用分离存储：Neo4j 管图结构（几千节点），Milvus 管向量（几万到几十万文本块）。

---

## 2.2 文本分块策略

GraphRAG 索引的第一步：把长文档切成小块。

| 分块策略 | 原理 | GraphRAG 默认 | 适用场景 |
|---------|------|-------------|---------|
| 固定分块 | 按固定 token 数切 | ❌ | 简单文档 |
| 递归分块 | 按分隔符优先级切（\n\n -> \n -> 句号） | ✅ | 通用文档 |
| 语义分块 | 用 Embedding 检测语义边界 | ❌ | 高质量场景 |

**GraphRAG 默认配置**：chunk_size=1200 tokens，overlap=100 tokens

```python
from llama_index.core.node_parser import SentenceSplitter

# GraphRAG 默认分块策略：递归分块
splitter = SentenceSplitter(
    chunk_size=1200,    # 每块 1200 tokens
    chunk_overlap=100,  # 块间重叠 100 tokens（保证上下文连贯）
    separator="\n\n",   # 优先按段落切
    secondary_separator="\n",  # 其次按行切
    chunking_tokenizer_fn=tokenize  # token 计数函数
)

chunks = splitter.split_text(long_document)
# 输出：["chunk1...", "chunk2...", "chunk3..."]
```

> **为什么是 1200 tokens？** 微软 GraphRAG 官方测试：1200 tokens 在实体抽取质量和 LLM 成本之间取得最佳平衡。太小（300）导致实体抽取不完整，太大（3000）导致 LLM 抽取遗漏细节且成本翻倍。

---

## 2.3 LLM 驱动的实体关系抽取（含 Gleaning 机制）

### 2.3.1 基本抽取流程

```python
# GraphRAG 实体抽取 Prompt（微软官方模板简化版）
ENTITY_PROMPT = """
-Goal-
Given a text document that is potentially relevant to this activity and a list of entity types,
identify all entities of those types and all relationships among the identified entities.

-Steps-
1. Identify all entities. For each entity, extract: entity_name, entity_type, entity_description.
2. From entities in step 1, identify all pairs (source_entity, target_entity) that are clearly related.
   For each pair, extract: source_entity, target_entity, relationship_description, relationship_strength.

-Examples-
Text: "XX茶饮在杭州有50家门店，2024年5月投放了地铁灯箱广告，曝光500万次"
Output:
{"entities": [
    {"name": "XX茶饮", "type": "Brand", "description": "连锁奶茶品牌"},
    {"name": "杭州", "type": "City", "description": "新一线城市"},
    {"name": "杭州地铁灯箱广告202405", "type": "Campaign", "description": "2024年5月投放"}
], "relations": [
    {"source": "XX茶饮", "target": "杭州", "type": "OPERATES_IN", "description": "在杭州有50家门店"},
    {"source": "XX茶饮", "target": "杭州地铁灯箱广告202405", "type": "LAUNCHED", "description": "投放了灯箱广告"}
]}

Text: {text}
Output:
"""
```

### 2.3.2 Gleaning 机制（面试重点）

**问题**：LLM 一次抽取会遗漏实体（比如只抽了 3 个，实际有 5 个）。

**解决**：Gleaning 机制 —— 多轮抽取，每轮问 LLM "还有遗漏吗？"

```python
def extract_with_gleaning(text: str, llm_client, max_gleaning: int = 1):
    """
    Gleaning 机制：多轮抽取提升召回率
    max_gleaning=1 表示抽取1次 + 补充1次 = 共2轮
    """
    # 第 1 轮：初次抽取
    all_entities = []
    all_relations = []
    
    result = llm_client.chat(
        prompt=ENTITY_PROMPT.format(text=text),
        temperature=0.0
    )
    all_entities.extend(result["entities"])
    all_relations.extend(result["relations"])
    
    # 第 2 轮（Gleaning）：问 LLM 是否遗漏
    for gleaning_round in range(max_gleaning):
        gleaning_prompt = f"""
        以下是之前抽取的实体和关系：
        实体：{all_entities}
        关系：{all_relations}
        
        请仔细重读原文，检查是否有遗漏的实体或关系。
        如果有，补充输出。如果没有，返回空列表。
        
        原文：{text}
        """
        gleaning_result = llm_client.chat(
            prompt=gleaning_prompt,
            temperature=0.0
        )
        
        if not gleaning_result["entities"]:
            break  # LLM 认为没有遗漏了
        
        all_entities.extend(gleaning_result["entities"])
        all_relations.extend(gleaning_result["relations"])
    
    return all_entities, all_relations

# 效果：微软官方测试，Gleaning=1 时实体召回率提升 15-20%
```

---

## 2.4 知识图谱构建（Neo4j 属性图模型）

### 2.4.1 节点和边的设计原则

**节点设计**：实体 -> 节点，属性 = 业务数据 + 描述文本
**边设计**：关系 -> 边，属性 = 关系强度 + 时间等元数据

```python
class KnowledgeGraphBuilder:
    def __init__(self, driver):
        self.driver = driver
    
    def build(self, entities: list, relations: list, source_chunks: list):
        """将抽取的实体和关系写入 Neo4j"""
        with self.driver.session() as session:
            # Step 1: 创建唯一约束（防止重复节点）
            session.run("CREATE CONSTRAINT IF NOT EXISTS FOR (b:Brand) REQUIRE b.name IS UNIQUE")
            session.run("CREATE CONSTRAINT IF NOT EXISTS FOR (c:City) REQUIRE c.name IS UNIQUE")
            
            # Step 2: 写入节点（存文本属性，不存向量）
            for entity in entities:
                session.run(f"""
                    MERGE (n:{entity['type']} {{name: $name}})
                    SET n.description = $description,
                        n.source_chunks = $source_chunks,
                        n.created_at = datetime()
                """, 
                name=entity["name"],
                description=entity.get("description", ""),
                source_chunks=source_chunks  # 关联的原始文本块ID
                )
            
            # Step 3: 写入关系（边）
            for rel in relations:
                session.run(f"""
                    MATCH (s:{rel['source_type']} {{name: $source}})
                    MATCH (t:{rel['target_type']} {{name: $target}})
                    MERGE (s)-[r:{rel['type']}]->(t)
                    SET r.description = $description,
                        r.strength = $strength
                """,
                source=rel["source"],
                target=rel["target"],
                description=rel.get("description", ""),
                strength=rel.get("strength", 0.5)
                )
```

### 2.4.2 同步构建向量索引

```python
class VectorIndexBuilder:
    """将文本块向量化，写入 Milvus，关联 Neo4j 实体"""
    
    def __init__(self, milvus_client, embedder, neo4j_driver):
        self.milvus = milvus_client
        self.embedder = embedder
        self.neo4j = neo4j_driver
    
    def build(self, chunks: list, entities: list):
        """为每个文本块生成向量，写入 Milvus"""
        vectors_to_insert = []
        
        for chunk in chunks:
            # 生成向量
            embedding = self.embedder.embed(chunk.text)
            
            # 找到这个文本块关联的实体（通过实体名匹配）
            related_entities = [
                e["name"] for e in entities 
                if e["name"] in chunk.text
            ]
            
            vectors_to_insert.append({
                "embedding": embedding,
                "text_chunk": chunk.text,           # 原始文本
                "entity_names": related_entities,    # 关联的实体名（桥梁）
                "chunk_id": chunk.id,
                "source_doc": chunk.source
            })
        
        # 批量写入 Milvus
        self.milvus.insert(collection="entities", data=vectors_to_insert)
```

---

## 2.5 Leiden 社区发现算法（面试重点）

### 2.5.1 为什么需要社区发现？

**问题**：图谱有几千个节点，每次查询都遍历全图太慢。

**解决**：用 Leiden 算法把图谱分层聚类，提前算好每个社区的摘要。检索时先定位社区，再在社区内部精细检索。

### 2.5.2 Leiden 算法原理

```
聚类前（散乱节点）：
  品牌A ── 城市1
  品牌B ── 城市1
  品牌C ── 城市2
  品牌D ── 城市2
  ...（几千个）

聚类后（分层社区）：
  社区1："华东快消圈"
    ├── 品牌A（茶饮）-> 杭州 -> 坪效+3%
    ├── 品牌B（零食）-> 上海 -> 坪效+1%
    └── 社区摘要："华东快消品牌整体扩张中，户外预算释放概率高"

  社区2："华南汽车圈"
    ├── 品牌C（新能源）-> 深圳 -> 展厅+5家
    └── 社区摘要："华南新能源展厅扩张，新车上市周期"
```

### 2.5.3 代码实现

```python
import networkx as nx
from community import community_louvain  # Leiden 算法实现

class CommunityDetector:
    def __init__(self, neo4j_driver, llm_client):
        self.neo4j = neo4j_driver
        self.llm = llm_client
    
    def detect_and_summarize(self):
        """Step 1: 从 Neo4j 加载全图 -> Step 2: Leiden 聚类 -> Step 3: 生成社区摘要"""
        
        # Step 1: 加载图结构到 NetworkX
        G = nx.Graph()
        with self.neo4j.session() as session:
            # 加载所有节点
            nodes = session.run("MATCH (n) RETURN n.name as name, labels(n) as type")
            for node in nodes:
                G.add_node(node["name"], type=node["type"])
            
            # 加载所有边
            edges = session.run("MATCH (a)-[r]->(b) RETURN a.name as source, b.name as target")
            for edge in edges:
                G.add_edge(edge["source"], edge["target"])
        
        # Step 2: 运行 Leiden 算法分层聚类
        # resolution 越大，社区越多越小；越小，社区越少越大
        communities = community_louvain.best_partition(G, resolution=1.0)
        # communities = {"XX茶饮": 0, "杭州": 0, "品牌C": 1, "深圳": 1, ...}
        
        # 按 community_id 分组
        community_groups = {}
        for node, comm_id in communities.items():
            if comm_id not in community_groups:
                community_groups[comm_id] = []
            community_groups[comm_id].append(node)
        
        # Step 3: 为每个社区生成摘要
        community_summaries = {}
        for comm_id, nodes in community_groups.items():
            # 收集社区内所有节点的描述
            node_descriptions = self._get_node_descriptions(nodes)
            
            # 调用 LLM 生成社区摘要
            summary = self.llm.chat(
                prompt=f"总结以下户外广告案例的共同特征：\n{node_descriptions}",
                temperature=0.3
            )
            community_summaries[comm_id] = summary
        
        return community_summaries
    
    def _get_node_descriptions(self, node_names: list) -> str:
        """从 Neo4j 获取节点描述"""
        with self.neo4j.session() as session:
            result = session.run(
                "MATCH (n) WHERE n.name IN $names RETURN n.name as name, n.description as desc",
                names=node_names
            )
            return "\n".join([f"{r['name']}: {r['desc']}" for r in result])
```

### 2.5.4 社区摘要的作用

```python
class CommunitySummaryStore:
    """社区摘要存储到 MySQL（预加载到 Redis 缓存）"""
    
    def __init__(self, mysql_conn, redis_client):
        self.mysql = mysql_conn
        self.redis = redis_client
    
    def store(self, community_summaries: dict):
        """存储社区摘要"""
        for comm_id, summary in community_summaries.items():
            # 写 MySQL
            self.mysql.execute(
                "INSERT INTO community_summary (community_id, summary) VALUES (%s, %s)",
                (comm_id, summary)
            )
            # 写 Redis 缓存（预加载）
            self.redis.setex(
                f"community:{comm_id}",
                3600 * 24,  # 24小时过期
                summary
            )
    
    def get(self, comm_id: int) -> str:
        """检索时先查 Redis 缓存"""
        cached = self.redis.get(f"community:{comm_id}")
        if cached:
            return cached  # 缓存命中，直接返回
        # 缓存未命中，查 MySQL
        return self.mysql.query(
            "SELECT summary FROM community_summary WHERE community_id = %s",
            (comm_id,)
        )
```

> **效果**：检索延迟下降 60%。原因：不用遍历全图，先通过社区摘要定位相关社区，再在社区内部精细检索。

---

## 2.6 完整索引流水线

```
原始文档(PDF/Word/数据库)
  │
  ├── Step 1: 文本分块（递归分块，1200 tokens/块）
  │
  ├── Step 2: LLM 实体关系抽取（含 Gleaning 机制）
  │   ├── 实体: [Brand, City, Campaign, StoreMetric, ...]
  │   └── 关系: [(Brand, OPERATES_IN, City), (Brand, LAUNCHED, Campaign), ...]
  │
  ├── Step 3: 双路存储
  │   ├── Neo4j: 实体->节点(文本属性), 关系->边  【存图结构】
  │   └── Milvus: 文本块->向量, 关联entity_name   【存向量+文本】
  │
  ├── Step 4: Leiden 社区发现
  │   ├── 分层聚类: 图谱 -> 多个社区
  │   └── 社区摘要: LLM 为每个社区生成摘要 -> MySQL + Redis
  │
  └── 索引完成，等待查询
```

**代码：完整索引流水线**

```python
class GraphRAGIndexer:
    """GraphRAG 完整索引流水线"""
    
    def __init__(self, splitter, extractor, kg_builder, vector_builder, community_detector):
        self.splitter = splitter
        self.extractor = extractor
        self.kg_builder = kg_builder
        self.vector_builder = vector_builder
        self.community_detector = community_detector
    
    def index(self, documents: list):
        """完整索引流程"""
        all_entities = []
        all_relations = []
        all_chunks = []
        
        for doc in documents:
            # Step 1: 文本分块
            chunks = self.splitter.split_text(doc.text)
            all_chunks.extend(chunks)
            
            for chunk in chunks:
                # Step 2: LLM 实体关系抽取（含 Gleaning）
                entities, relations = self.extractor.extract_with_gleaning(chunk)
                all_entities.extend(entities)
                all_relations.extend(relations)
                
                # Step 3a: 写入 Neo4j（图结构）
                self.kg_builder.build(entities, relations, chunk.id)
            
            # Step 3b: 写入 Milvus（向量 + 文本）
            self.vector_builder.build(chunks, all_entities)
        
        # Step 4: Leiden 社区发现 + 社区摘要
        community_summaries = self.community_detector.detect_and_summarize()
        
        return {
            "total_chunks": len(all_chunks),
            "total_entities": len(all_entities),
            "total_relations": len(all_relations),
            "total_communities": len(community_summaries)
        }
```

---

## 模块二总结

| 知识点 | 一句话记忆 |
|--------|----------|
| Neo4j 存什么 | 节点(文本属性) + 边(关系)，**不存向量** |
| Milvus 存什么 | 向量 + 文本片段 + entity_name(关联桥梁) |
| 两个库怎么协作 | Milvus 向量检索定位入口实体 -> Neo4j 图遍历多跳扩展 |
| 文本分块 | 递归分块，1200 tokens/块，overlap=100 |
| Gleaning 机制 | 多轮抽取问"还有遗漏吗"，召回率+15-20% |
| Leiden 社区发现 | 分层聚类+社区摘要，检索延迟-60% |
| 完整流水线 | 分块->抽取->双路存储->社区发现 |

---

## 🔧 修正补充（基于专业反馈，2026-07-18）

> 以下修正基于微软 GraphRAG 官方文档、Neo4j GraphRAG Python 文档、Milvus Graph RAG 官方示例的专业反馈。

### 修正 1：离线索引不是"LLM 一次调用同时完成"

| 原表述 | 修正后 |
|--------|--------|
| LLM 一次调用同时生成结构化数据和文本向量 | **LLM 负责实体/关系/属性抽取，Embedding 模型负责向量化，两者是同一条流水线但不是同一个模型的一次调用** |

```
准确流程：
原始文档
  ├── 文档解析、清洗、切片
  │
  ├── Embedding 模型（独立的向量化模型）
  │      └── 文本块向量 + 原文 + 元数据 -> Milvus
  │
  └── LLM / 信息抽取模型（独立的抽取模型）
         ├── 实体、关系、属性
         └── 实体消歧、合并 -> Neo4j
```

### 修正 2：不是"必须先查 Milvus 再查 Neo4j"，有 4 种检索模式

| 原表述 | 修正后 |
|--------|--------|
| 固定流程：Milvus -> entity_name -> Neo4j | **这只是"向量优先"模式，还有图谱优先、并行、图查原文 3 种模式** |

```
模式一：向量优先（适合模糊查询）
  Query -> Milvus -> 相关文本/实体候选 -> Neo4j 扩展

模式二：图谱优先（适合实体明确"喜茶在杭州有多少门店"）
  实体识别 -> 实体链接 -> Neo4j 查询（不需要先查 Milvus）

模式三：向量与图并行（延迟更低）
  Query -> 路由 ─┬─> Milvus：检索相关原文
                 └─> Neo4j：查询实体关系
                     -> 结果融合

模式四：图谱检索后回查原文
  Query -> Neo4j 找到实体/关系/事实 -> 根据 chunk_id 回查 Milvus 获取原始证据
```

### 修正 3：Milvus 入口发现不是唯一方式

| 原表述 | 修正后 |
|--------|--------|
| Milvus 的唯一作用是入口发现 | **入口还可以来自：实体链接、Neo4j 全文索引、Neo4j 向量索引、Text-to-Cypher、关键词匹配、业务 ID。Neo4j 自带向量索引，不必须部署独立 Milvus** |

### 修正 4：Milvus 不只存文本块向量

| 原表述 | 修正后 |
|--------|--------|
| Milvus 只存文本块的向量 + entity_name | **Milvus 还可以为实体和关系建立独立集合，向量检索入口可以是文本、实体或关系** |

### 修正 5："图遍历=硬数据，文本块=软知识"边界不严格

| 原表述 | 修正后 |
|--------|--------|
| 图遍历返回硬数据，文本块返回软知识 | **图数据库也能存建议类内容（Recommendation 节点），文本块里也可能有精确硬数据** |

| Neo4j | Milvus |
|-------|--------|
| 保存被显式建模的实体、关系和属性 | 保存较完整的原始语义内容 |
| 擅长关系查询、多跳推理、路径约束 | 擅长模糊语义匹配和相关内容召回 |
| 信息结构清晰 | 信息上下文较完整 |
| 未被抽取或建模的信息可能丢失 | 跨文本关系不明确 |

> 更准确的一句话：**Neo4j 提供显式关系和可计算结构，Milvus 提供语义召回和原文上下文，两者融合形成 GraphRAG 的检索上下文。**

### 修正 6：给 LLM 的不是向量，是原始文本

```python
# ❌ 错误：把向量给 LLM
{"embedding": [0.128, -0.391, ...]}

# ✅ 正确：把原始文本给 LLM
{
    "text": "2024年茶饮品牌扩张期，地铁灯箱广告...",
    "source": "2024茶饮行业报告",
    "score": 0.89
}
```

### 修正 7：Neo4j 结果需要格式化后再给 LLM

```python
# Neo4j 返回的原始图结构 -> 格式化为：
【图谱事实】
1. 喜茶在杭州共有50家门店，统计时间为2024年
2. 喜茶历史广告活动A使用地铁灯箱渠道
3. 活动A曝光量为500万，转化率为30%

【文档证据】
2024年茶饮品牌扩张期，户外广告预算平均增长2.1倍...
```

### 修正 8：缺少实体消歧步骤（离线阶段重要补充）

```
完整离线阶段：
  实体抽取
    ↓
  实体标准化（HEYTEA -> 喜茶）
    ↓
  实体消歧与合并（判断多个别名是否指向同一实体）
    ↓
  写入图数据库
```

### 修正 9：Query 理解发生在检索之前

```
用户 Query
  ↓
意图识别 + 实体识别 + 实体链接（LLM 完成，不是查 Neo4j）
  ↓
检索规划（确定要查哪些实体、属性、关系）
  ↓
向量召回 + 图遍历
```

### 修正 10：两边通过 ID 关联（不只是 entity_name）

```python
# Milvus 文本块
{
    "chunk_id": "chunk_001",
    "entity_ids": ["brand_001", "city_0571"]  # 用 ID 关联
}

# Neo4j 节点
(:Brand {entity_id: "brand_001", name: "喜茶"})
(:Evidence {chunk_id: "chunk_001"})  # 证据节点，反向关联原文
```

### 修正后的完整架构（最终准确版）

```
离线阶段：
  文档 -> 切片 -> Embedding模型向量化 -> Milvus
       └-> LLM实体关系抽取 -> 实体标准化/消歧 -> Neo4j
  两边通过 entity_id / chunk_id 关联

在线阶段：
  Query -> 意图识别 + 实体识别 + 实体链接 -> 检索规划
       -> 向量召回 + 图遍历（4种模式按需选择）
       -> 文本证据与图谱事实融合
       -> 格式化为 LLM Context
       -> LLM 生成答案 + 引用来源
```

### 参考来源

- 微软 GraphRAG 官方：https://microsoft.github.io/graphrag/index/overview/
- Neo4j GraphRAG Python：https://neo4j.com/docs/neo4j-graphrag-python/current/user_guide_rag.html
- Neo4j 向量索引：https://neo4j.com/docs/cypher-manual/current/indexes/semantic-indexes/vector-indexes/
- Milvus Graph RAG：https://milvus.io/docs/graph_rag_with_milvus.md
