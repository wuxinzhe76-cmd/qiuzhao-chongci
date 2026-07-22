# 模块三：GraphRAG 检索策略

> 📅 2026-07-18
> 核心内容：Local Search / Global Search / DRIFT Search / 混合路由
> 参考：微软 GraphRAG 官方 + Neo4j GraphRAG Python 文档

---

## 3.1 三种检索模式总览

微软 GraphRAG 官方提供三种检索模式，对应不同查询场景：

| 模式 | 全称 | 适用场景 | 检索范围 | 延迟 |
|------|------|---------|---------|------|
| **Local Search** | 局部检索 | 精确事实查询（"喜茶杭州多少门店"） | 实体邻域子图（1-2跳） | 快（200-500ms） |
| **Global Search** | 全局检索 | 全局分析（"所有茶饮品牌投放趋势"） | 社区摘要层次化检索 | 慢（1-3s） |
| **DRIFT Search** | 混合检索 | 既需精确又需全局（"喜茶扩张对行业影响"） | Local + Global 融合 | 中（500ms-1.5s） |

```
查询复杂度光谱：
  简单 ──────────────────────────────────────> 复杂
  Local Search          DRIFT Search          Global Search
  （单实体事实）        （实体+全局关联）      （全语料分析）
```

---

## 3.2 Local Search（局部检索）

### 3.2.1 原理

从用户查询中识别实体，在知识图谱中找到该实体节点，获取其 1-2 跳邻域子图，融合相关文本块，生成答案。

```
用户问："喜茶在杭州有多少门店？"
  │
  ├── 实体识别：喜茶(Brand)、杭州(City)
  │
  ├── 图遍历：从喜茶节点出发，1-2跳邻域
  │   喜茶 -[OPERATES_IN]-> 杭州
  │   喜茶 -[HAS_METRIC]-> StoreMetric(50家, 20万)
  │   喜茶 -[LAUNCHED]-> Campaign(地铁广告)
  │
  ├── 文本块召回：Milvus 检索"喜茶杭州门店"相关文本
  │
  └── 融合 -> LLM 生成
```

### 3.2.2 代码实现

```python
class LocalSearch:
    """局部检索：实体邻域子图 + 文本块"""
    
    def __init__(self, neo4j_driver, milvus_client, embedder, llm):
        self.neo4j = neo4j_driver
        self.milvus = milvus_client
        self.embedder = embedder
        self.llm = llm
    
    def search(self, query: str) -> dict:
        # Step 1: 实体识别（LLM 从查询中抽取实体）
        entities = self._extract_entities(query)
        # entities = [{"name": "喜茶", "type": "Brand"}, 
        #             {"name": "杭州", "type": "City"}]
        
        # Step 2: 文本块召回（Milvus 向量检索）
        query_vector = self.embedder.embed(query)
        text_results = self.milvus.search(
            collection="text_chunks",
            data=[query_vector],
            top_k=5,
            output_fields=["text_chunk", "entity_ids"]
        )
        
        # Step 3: 图遍历（Neo4j 获取实体邻域子图）
        graph_context = []
        with self.neo4j.session() as session:
            for entity in entities:
                cypher = f"""
                MATCH path = (n:{entity['type']} {{name: $name}})-[*1..2]-(related)
                RETURN nodes(path) as nodes, relationships(path) as rels
                LIMIT 30
                """
                result = session.run(cypher, name=entity["name"])
                graph_context.extend([r.data() for r in result])
        
        # Step 4: 融合 + 格式化
        context = self._format_context(text_results, graph_context)
        
        # Step 5: LLM 生成
        answer = self.llm.generate(query, context)
        return {"answer": answer, "context": context}
    
    def _extract_entities(self, query: str) -> list:
        """LLM 从查询中抽取实体"""
        prompt = f"从以下查询中抽取实体名和类型，返回JSON：\n{query}"
        return self.llm.chat(prompt=prompt, temperature=0.0)
    
    def _format_context(self, text_results, graph_context):
        """格式化为 LLM 可读上下文"""
        return f"""
【图谱事实】
{self._format_graph(graph_context)}

【文档证据】
{self._format_text(text_results)}
"""
```

### 3.2.3 适用场景

```python
# ✅ 适合 Local Search 的查询：
"喜茶在杭州有多少门店？"           # 单实体事实
"喜茶2024年5月投了什么广告？"      # 单实体历史
"凤起路站日均人流多少？"           # 单实体属性

# ❌ 不适合 Local Search 的查询：
"所有茶饮品牌的投放趋势是什么？"    # 需要全局分析 -> Global Search
"喜茶扩张对整个行业有什么影响？"    # 需要实体+全局关联 -> DRIFT Search
```

---

## 3.3 Global Search（全局检索）

### 3.3.1 原理

不针对单个实体，而是基于 Leiden 社区发现的**层次化社区摘要**，对所有社区摘要做 Map-Reduce 式检索，回答全局性问题。

```
用户问："所有茶饮品牌的户外投放趋势是什么？"
  │
  ├── 不遍历具体实体节点（太多了）
  │
  ├── 遍历所有社区摘要（社区摘要数量少，快）
  │   社区1摘要："华东快消圈，扩张期投放+2.1倍"
  │   社区2摘要："华南汽车圈，新车上市周期投放"
  │   社区3摘要："华北美妆圈，季节性投放为主"
  │
  ├── Map 阶段：每个社区摘要生成局部答案
  │   社区1 -> "华东茶饮品牌扩张期投放增长"
  │   社区2 -> "华南汽车品牌新车周期投放"
  │
  ├── Reduce 阶段：聚合所有局部答案
  │   -> "茶饮品牌整体处于扩张期，投放预算+2.1倍"
  │
  └── LLM 生成最终答案
```

### 3.3.2 代码实现

```python
class GlobalSearch:
    """全局检索：社区摘要 Map-Reduce"""
    
    def __init__(self, neo4j_driver, milvus_client, embedder, llm):
        self.neo4j = neo4j_driver
        self.milvus = milvus_client
        self.embedder = embedder
        self.llm = llm
    
    def search(self, query: str) -> dict:
        # Step 1: 获取所有社区摘要
        community_summaries = self._get_all_communities()
        # [{"id": 0, "summary": "华东快消圈，扩张期投放+2.1倍"},
        #  {"id": 1, "summary": "华南汽车圈，新车周期投放"},
        #  ...]
        
        # Step 2: Map 阶段 -- 每个社区生成局部答案
        partial_answers = []
        for community in community_summaries:
            partial = self.llm.chat(
                prompt=f"""
                基于以下社区信息，回答问题。
                如果社区信息不相关，返回"无相关信息"。
                
                社区摘要：{community['summary']}
                问题：{query}
                """,
                temperature=0.3
            )
            if "无相关信息" not in partial:
                partial_answers.append(partial)
        
        # Step 3: Reduce 阶段 -- 聚合所有局部答案
        final_answer = self.llm.chat(
            prompt=f"""
            基于以下多个社区的分析结果，生成最终答案。
            
            各社区分析：
            {chr(10).join(partial_answers)}
            
            问题：{query}
            """,
            temperature=0.3
        )
        
        return {"answer": final_answer, "sources": partial_answers}
    
    def _get_all_communities(self) -> list:
        """从 Redis 缓存获取所有社区摘要"""
        # 优先查 Redis（预加载），未命中查 MySQL
        return self.redis.get("all_communities") or self.mysql.query(...)
```

### 3.3.3 适用场景

```python
# ✅ 适合 Global Search 的查询：
"所有茶饮品牌的投放趋势是什么？"        # 全局分析
"2024年户外广告市场的主要特征？"        # 全局总结
"哪些行业在扩张期投放预算最高？"        # 跨社区对比

# ❌ 不适合 Global Search 的查询：
"喜茶在杭州有多少门店？"               # 单实体事实 -> Local Search
```

> **Global Search 的成本**：需要遍历所有社区摘要，每个社区调用一次 LLM（Map 阶段），Token 消耗大。微软官方建议仅在需要全局洞察时使用。

---

## 3.4 DRIFT Search（混合检索）

### 3.4.1 原理

DRIFT = Dynamic Reasoning and Inference with Multi-level Targeting。结合 Local Search 的精确性和 Global Search 的全局性，先通过社区摘要做粗筛（定位相关社区），再在社区内做 Local Search 精细检索。

```
用户问："喜茶扩张对整个茶饮行业有什么影响？"
  │
  ├── Phase 1: Global 粗筛（定位相关社区）
  │   查询向量化 -> 在社区摘要集合中检索
  │   -> 命中社区1"华东快消圈"、社区3"茶饮行业圈"
  │   -> 排除社区2"华南汽车圈"（不相关）
  │
  ├── Phase 2: Local 精搜（在相关社区内做实体检索）
  │   在社区1内：喜茶 -> 门店数据 -> 投放记录
  │   在社区3内：行业趋势 -> 竞品对比 -> 投放策略
  │
  ├── Phase 3: 融合
  │   喜茶具体数据（Local）+ 行业整体趋势（Global）
  │
  └── LLM 生成
```

### 3.4.2 代码实现

```python
class DRIFTSearch:
    """DRIFT 检索：Global 粗筛 + Local 精搜"""
    
    def __init__(self, neo4j_driver, milvus_client, embedder, llm):
        self.neo4j = neo4j_driver
        self.milvus = milvus_client
        self.embedder = embedder
        self.llm = llm
    
    def search(self, query: str) -> dict:
        # Phase 1: Global 粗筛 -- 在社区摘要集合中检索
        query_vector = self.embedder.embed(query)
        community_results = self.milvus.search(
            collection="community_summaries",  # 社区摘要向量集合
            data=[query_vector],
            top_k=3,  # 只取最相关的3个社区
            output_fields=["community_id", "summary"]
        )
        # 命中社区1"华东快消圈"、社区3"茶饮行业圈"
        
        # Phase 2: Local 精搜 -- 在相关社区内做实体检索
        local_context = []
        for community in community_results:
            # 获取社区内所有实体
            entity_names = self._get_community_entities(community["community_id"])
            
            # 在社区内做 Local Search
            for entity_name in entity_names:
                cypher = """
                MATCH path = (n {name: $name})-[*1..2]-(related)
                WHERE related.community_id = $comm_id
                RETURN nodes(path), relationships(path)
                LIMIT 20
                """
                with self.neo4j.session() as session:
                    result = session.run(
                        cypher, name=entity_name, 
                        comm_id=community["community_id"]
                    )
                    local_context.extend([r.data() for r in result])
        
        # Phase 3: 融合 Global + Local
        context = {
            "global_context": [c["summary"] for c in community_results],
            "local_context": local_context
        }
        
        # LLM 生成
        answer = self.llm.generate(query, context)
        return {"answer": answer, "context": context}
    
    def _get_community_entities(self, community_id: int) -> list:
        """获取社区内所有实体名"""
        with self.neo4j.session() as session:
            result = session.run(
                "MATCH (n) WHERE n.community_id = $id RETURN n.name as name",
                id=community_id
            )
            return [r["name"] for r in result]
```

### 3.4.3 适用场景

```python
# ✅ 适合 DRIFT Search 的查询：
"喜茶扩张对整个茶饮行业有什么影响？"     # 实体 + 全局关联
"XX茶饮的投放策略和行业趋势对比"         # 实体 + 全局对比
"哪些品牌和喜茶有相似的投放模式？"       # 实体 + 跨社区检索

# DRIFT 的优势：
# - 比 Local Search 多了全局视角
# - 比 Global Search 少了不必要的社区遍历（先粗筛）
# - Token 消耗比 Global Search 低 79%（微软官方数据）
```

---

## 3.5 四种检索模式（补充.md 修正版）

结合修正补充中的 4 种模式，完整理解 GraphRAG 的检索策略：

### 模式一：向量优先（Vector-first）

```python
# 适合：模糊查询，不知道具体实体
# "有哪些茶饮品牌适合做地铁广告？"
def vector_first_search(query):
    # Step 1: Milvus 向量检索
    results = milvus.search(embed(query), top_k=10)
    
    # Step 2: 提取 entity_ids
    entity_ids = [r.entity_ids for r in results]
    
    # Step 3: Neo4j 图遍历扩展
    graph_data = neo4j.traverse(entity_ids, hops=2)
    
    return merge(results, graph_data)
```

### 模式二：图谱优先（Graph-first）

```python
# 适合：实体明确，直接查图
# "喜茶在杭州有多少门店？"
def graph_first_search(query):
    # Step 1: LLM 实体识别 + 实体链接
    entities = llm.extract_entities(query)
    # entities = ["喜茶", "杭州"]
    
    # Step 2: 直接 Neo4j 查询（不需要先查 Milvus）
    cypher = """
    MATCH (b:Brand {name: "喜茶"})-[:OPERATES_IN]->(c:City {name: "杭州"})
    MATCH (b)-[:HAS_METRIC]->(m:StoreMetric)
    RETURN m.store_count
    """
    result = neo4j.run(cypher)
    
    return result  # 直接返回，不需要向量检索
```

### 模式三：向量与图并行

```python
# 适合：需要同时获取语义匹配和关系查询，追求低延迟
# "喜茶在杭州的投放效果和行业对比"
def parallel_search(query):
    import asyncio
    
    async def vector_search():
        return milvus.search(embed(query), top_k=5)
    
    async def graph_search():
        entities = llm.extract_entities(query)
        return neo4j.traverse(entities, hops=2)
    
    # 两路并行执行
    vector_results, graph_results = await asyncio.gather(
        vector_search(),
        graph_search()
    )
    
    return merge(vector_results, graph_results)
```

### 模式四：图谱检索后回查原文

```python
# 适合：需要原始文档证据验证
# "喜茶杭州投放的依据是什么？给我原文出处"
def graph_then_text_search(query):
    # Step 1: Neo4j 找到实体/关系/事实
    graph_data = neo4j.traverse("喜茶", hops=2)
    # 返回：喜茶 -> 广告活动A -> chunk_id="chunk_004"
    
    # Step 2: 根据 chunk_id 回查 Milvus 获取原始文本
    chunk_ids = [node.chunk_id for node in graph_data if node.has_chunk_id]
    
    original_texts = milvus.query(
        collection="text_chunks",
        filter=f"chunk_id in {chunk_ids}",
        output_fields=["text_chunk", "source_doc"]
    )
    
    return {"graph_facts": graph_data, "original_evidence": original_texts}
```

---

## 3.6 混合检索路由（生产推荐方案）

### 3.6.1 意图识别 + 动态路由

生产环境中，不是固定用一种检索模式，而是根据查询意图动态路由：

```python
from enum import Enum

class QueryIntent(Enum):
    FACT_LOOKUP = "fact"           # 精确事实 -> 图谱优先
    SEMANTIC_SEARCH = "semantic"   # 语义模糊 -> 向量优先
    MULTI_HOP_REASONING = "reasoning"  # 多跳推理 -> 并行模式
    GLOBAL_ANALYSIS = "global"     # 全局分析 -> Global Search
    ENTITY_WITH_CONTEXT = "drift"  # 实体+全局 -> DRIFT Search
    EVIDENCE_LOOKUP = "evidence"   # 证据查询 -> 图查原文

class HybridRAGRouter:
    """混合 RAG 路由器：意图识别 -> 动态路由"""
    
    def __init__(self, local_search, global_search, drift_search, 
                 vector_search, graph_search, parallel_search, llm):
        self.searchers = {
            "fact": graph_search,        # 图谱优先
            "semantic": vector_search,    # 向量优先
            "reasoning": parallel_search, # 并行模式
            "global": global_search,      # Global Search
            "drift": drift_search,        # DRIFT Search
            "evidence": graph_search,     # 图查原文
        }
        self.llm = llm
    
    def classify_intent(self, query: str) -> QueryIntent:
        """LLM 意图分类"""
        prompt = f"""
判断查询意图，只返回类型代码：
- fact: 精确事实查询（"喜茶杭州多少门店"）
- semantic: 语义模糊查询（"奶茶行业趋势"）
- reasoning: 多跳推理（"喜茶扩张对广告影响"）
- global: 全局分析（"所有品牌投放趋势"）
- drift: 实体+全局关联（"喜茶和行业对比"）
- evidence: 证据查询（"给我原文出处"）

查询：{query}
类型："""
        response = self.llm.chat(prompt=prompt, temperature=0.0)
        return QueryIntent(response.strip())
    
    def retrieve(self, query: str) -> dict:
        """根据意图路由到对应检索策略"""
        intent = self.classify_intent(query)
        
        searcher = self.searchers[intent.value]
        return searcher(query)
```

### 3.6.2 路由决策表

| 意图类型 | 查询示例 | 检索模式 | 延迟 | Token 消耗 |
|---------|---------|---------|------|-----------|
| fact | "喜茶杭州多少门店" | 图谱优先 | 200ms | 低 |
| semantic | "奶茶行业趋势" | 向量优先 | 300ms | 低 |
| reasoning | "喜茶扩张对广告影响" | 并行模式 | 500ms | 中 |
| global | "所有品牌投放趋势" | Global Search | 1-3s | 高 |
| drift | "喜茶和行业对比" | DRIFT Search | 500ms-1.5s | 中 |
| evidence | "给我原文出处" | 图查原文 | 300ms | 低 |

### 3.6.3 生产实践建议

```python
# 微软官方建议：GraphRAG 做增量后端，不是所有查询都用 GraphRAG
class ProductionRouter:
    """生产环境路由器：70% Vector RAG + 30% GraphRAG"""
    
    def retrieve(self, query: str):
        intent = self.classify_intent(query)
        
        # 简单查询用 Vector RAG（快、便宜）
        if intent in ["fact", "semantic", "evidence"]:
            return self.vector_rag(query)  # 70% 的查询
        
        # 复杂查询用 GraphRAG（慢、贵、但准）
        elif intent in ["reasoning", "global", "drift"]:
            return self.graph_rag(query)    # 30% 的查询
        
        # 默认走 Vector RAG
        return self.vector_rag(query)
```

> **Particula 1200 万节点案例**：GraphRAG 只处理 7% 的多跳推理查询，其余 93% 由 Vector RAG + 缓存处理。这是成本和质量的平衡点。

---

## 模块三总结

| 知识点 | 一句话记忆 |
|--------|----------|
| Local Search | 实体邻域子图检索，适合精确事实查询 |
| Global Search | 社区摘要 Map-Reduce，适合全局分析 |
| DRIFT Search | Global 粗筛 + Local 精搜，适合实体+全局关联 |
| 4 种检索模式 | 向量优先/图谱优先/并行/图查原文，按场景选择 |
| 混合路由 | 意图识别 -> 动态路由，70% Vector + 30% Graph |
| 生产建议 | GraphRAG 做增量后端，不是所有查询都用 |
