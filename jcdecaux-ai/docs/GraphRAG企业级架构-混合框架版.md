# 德高企业级 GraphRAG 架构设计（混合框架版）

> 📅 2026-07-18
> 核心思想：不绑定单一框架，每个框架干它擅长的事
> 对比：教学版用 LlamaIndex 自建一切，企业版混合用多个框架

---

## 一、教学版 vs 企业版对比

| 维度 | 教学版（原蓝图） | 企业版（混合框架） |
|------|---------------|-----------------|
| 图谱检索 | LlamaIndex KnowledgeGraphIndex | Neo4j GraphRAG SDK（VectorCypherRetriever） |
| 流程编排 | LlamaIndex 自建 | LangGraph StateGraph |
| 向量召回 | LlamaIndex + Milvus | Milvus 直连 + Neo4j GraphRAG |
| 社区发现 | networkx + leidenalg 自建 | 借鉴微软 GraphRAG 思想，自建实现 |
| Global Search | 自建 Map-Reduce | 借鉴微软 GraphRAG，用 LangGraph 编排 |
| 意图路由 | if-else | LangGraph 条件路由 |
| 容错降级 | 无 | 限流 + 熔断 + 降级 |
| 监控 | 无 | Prometheus + Grafana |

---

## 二、各框架分工（各取所长）

```
┌─────────────────────────────────────────────────────────────┐
│                    企业级 GraphRAG 混合架构                   │
├──────────────┬──────────────────────────────────────────────┤
│ 框架          │ 干什么（擅长的事）                             │
├──────────────┼──────────────────────────────────────────────┤
│ Neo4j        │ 业务知识图谱存储 + 精确关系查询                │
│ GraphRAG SDK │ VectorCypherRetriever（向量+图遍历一体化）     │
│              │ GraphCypherRetriever（Text-to-Cypher）        │
├──────────────┼──────────────────────────────────────────────┤
│ Milvus       │ 大规模向量召回（文本块语义匹配）               │
│              │ 独立向量库，高性能检索                         │
├──────────────┼──────────────────────────────────────────────┤
│ LangGraph    │ 检索流程编排（StateGraph）                    │
│              │ 意图分类 -> 条件路由 -> 多路检索 -> 融合 -> 生成│
│              │ 支持人在环路（HITL）                          │
├──────────────┼──────────────────────────────────────────────┤
│ 微软GraphRAG │ 借鉴思想，不自建                              │
│（借鉴思想）   │ Leiden 社区发现 + 社区摘要 + Global Search     │
│              │ DRIFT Search（Global粗筛+Local精搜）           │
├──────────────┼──────────────────────────────────────────────┤
│ 火山引擎豆包 │ LLM 服务（抽取/摘要/生成/Embedding）          │
├──────────────┼──────────────────────────────────────────────┤
│ MCP 协议     │ 外部工具调用（高德/博查/即梦）                 │
├──────────────┼──────────────────────────────────────────────┤
│ FastAPI      │ Web 服务（SSE 流式输出）                      │
├──────────────┼──────────────────────────────────────────────┤
│ Redis        │ 语义缓存 + 社区摘要预加载                     │
├──────────────┼──────────────────────────────────────────────┤
│ MySQL        │ 业务数据 + 异步任务状态                       │
└──────────────┴──────────────────────────────────────────────┘
```

---

## 三、企业级架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         用户层（CLI/API）                        │
│                    FastAPI + SSE 流式输出                        │
└────────────────────────────┬────────────────────────────────────┘
                             │
                ┌────────────▼────────────┐
                │   LangGraph 流程编排      │
                │   (StateGraph)           │
                │                          │
                │   • Query 理解           │
                │   • 意图分类             │
                │   • 条件路由             │
                │   • 多路检索             │
                │   • 结果融合             │
                │   • LLM 生成             │
                └────────────┬────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
        ▼                    ▼                    ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ Neo4j        │    │ Milvus       │    │ MCP 工具      │
│ GraphRAG SDK │    │ 向量召回      │    │ (在线调用)    │
│              │    │              │    │              │
│ • 图遍历     │    │ • 语义匹配   │    │ • 高德地图    │
│ • VectorCypher│   │ • 入口发现   │    │ • 博查舆情    │
│ • Text2Cypher│    │ • 文本召回   │    │ • 即梦生成    │
└──────────────┘    └──────────────┘    └──────────────┘
        │                    │                    │
        └────────────────────┼────────────────────┘
                             │
                ┌────────────▼────────────┐
                │   社区摘要层              │
                │   (借鉴微软 GraphRAG)     │
                │                          │
                │   • Leiden 社区发现       │
                │   • 社区摘要（Redis缓存） │
                │   • Global Search        │
                │   • DRIFT Search         │
                └────────────┬────────────┘
                             │
                ┌────────────▼────────────┐
                │   LLM 服务               │
                │   (火山引擎豆包 2.0)     │
                │                          │
                │   • 抽取（lite版）       │
                │   • 摘要（标准版）       │
                │   • 生成（专业版）       │
                │   • Embedding            │
                └─────────────────────────┘
```

---

## 四、LangGraph 流程编排（核心）

```python
from langgraph.graph import StateGraph, END
from typing import TypedDict, List
from enum import Enum

class GraphRAGState(TypedDict):
    query: str                    # 用户原始查询
    rewritten_query: str          # 改写后的查询
    intent: str                   # 查询意图
    entities: List[dict]          # 识别的实体
    vector_results: List[dict]    # 向量检索结果
    graph_results: List[dict]     # 图遍历结果
    community_results: List[dict] # 社区摘要结果
    mcp_results: List[dict]       # MCP 在线结果
    final_context: str            # 融合后的上下文
    answer: str                   # 最终答案

class QueryIntent(Enum):
    FACT_LOOKUP = "fact"           # 精确事实
    SEMANTIC_SEARCH = "semantic"   # 语义模糊
    MULTI_HOP_REASONING = "reasoning"  # 多跳推理
    GLOBAL_ANALYSIS = "global"     # 全局分析
    DRIFT = "drift"                # 实体+全局

# ========== 构建 LangGraph StateGraph ==========

def build_graphrag_workflow():
    workflow = StateGraph(GraphRAGState)
    
    # 添加节点
    workflow.add_node("query_understanding", query_understanding)    # Query 理解
    workflow.add_node("intent_classification", intent_classification) # 意图分类
    workflow.add_node("vector_search", vector_search)                # 向量检索
    workflow.add_node("graph_search", graph_search)                  # 图遍历
    workflow.add_node("community_search", community_search)          # 社区摘要
    workflow.add_node("mcp_search", mcp_search)                      # MCP 在线
    workflow.add_node("context_fusion", context_fusion)              # 结果融合
    workflow.add_node("llm_generation", llm_generation)              # LLM 生成
    
    # 设置入口
    workflow.set_entry_point("query_understanding")
    
    # Query理解 -> 意图分类
    workflow.add_edge("query_understanding", "intent_classification")
    
    # 意图分类 -> 条件路由（核心：不同意图走不同检索路径）
    workflow.add_conditional_edges(
        "intent_classification",
        route_by_intent,  # 路由函数
        {
            "fact": "graph_search",           # 精确事实 -> 直接图遍历
            "semantic": "vector_search",       # 语义模糊 -> 向量检索
            "reasoning": "parallel_search",    # 多跳推理 -> 并行检索
            "global": "community_search",      # 全局分析 -> 社区摘要
            "drift": "drift_search",           # 实体+全局 -> DRIFT
        }
    )
    
    # 各检索节点 -> 结果融合
    workflow.add_edge("vector_search", "context_fusion")
    workflow.add_edge("graph_search", "context_fusion")
    workflow.add_edge("community_search", "context_fusion")
    
    # MCP 在线调用（异步并行，不阻塞主流程）
    workflow.add_edge("context_fusion", "mcp_search")
    
    # 结果融合 -> LLM 生成
    workflow.add_edge("mcp_search", "llm_generation")
    
    # LLM 生成 -> 结束
    workflow.add_edge("llm_generation", END)
    
    return workflow.compile()


# ========== 节点实现 ==========

def query_understanding(state: GraphRAGState) -> GraphRAGState:
    """节点1：Query 理解（改写 + 实体识别）"""
    query = state["query"]
    
    # LLM 改写查询
    rewritten = llm.chat(
        prompt=f"改写以下查询，使其更适合检索：{query}",
        temperature=0.3
    )
    
    # LLM 实体识别
    entities = llm.extract_entities(rewritten)
    
    state["rewritten_query"] = rewritten
    state["entities"] = entities
    return state


def intent_classification(state: GraphRAGState) -> GraphRAGState:
    """节点2：意图分类"""
    intent = llm.classify_intent(state["rewritten_query"])
    state["intent"] = intent
    return state


def route_by_intent(state: GraphRAGState) -> str:
    """路由函数：根据意图选择检索路径"""
    intent = state["intent"]
    
    if intent == QueryIntent.FACT_LOOKUP.value:
        return "fact"          # 图谱优先
    elif intent == QueryIntent.SEMANTIC_SEARCH.value:
        return "semantic"      # 向量优先
    elif intent == QueryIntent.MULTI_HOP_REASONING.value:
        return "reasoning"     # 并行模式
    elif intent == QueryIntent.GLOBAL_ANALYSIS.value:
        return "global"        # Global Search
    elif intent == QueryIntent.DRIFT.value:
        return "drift"         # DRIFT Search
    
    return "semantic"  # 默认向量检索


def vector_search(state: GraphRAGState) -> GraphRAGState:
    """节点3a：Milvus 向量检索"""
    query_vector = embedder.embed(state["rewritten_query"])
    
    results = milvus.search(
        collection="text_chunks",
        data=[query_vector],
        top_k=10,
        output_fields=["text_chunk", "entity_ids", "source_doc"]
    )
    
    state["vector_results"] = results
    return state


def graph_search(state: GraphRAGState) -> GraphRAGState:
    """节点3b：Neo4j GraphRAG 图遍历"""
    from neo4j_graphrag.retrievers import VectorCypherRetriever
    
    # 使用 Neo4j GraphRAG SDK 的 VectorCypherRetriever
    retriever = VectorCypherRetriever(
        driver=neo4j_driver,
        index_name="text_embedding_index",  # Neo4j 向量索引
        embedder=doubao_embedder,
        retrieval_query="""
        MATCH (node)-[r]->(related)
        RETURN node.name, node.type, r.type, related.name, related.type
        LIMIT 50
        """
    )
    
    results = retriever.search(state["rewritten_query"], top_k=5)
    state["graph_results"] = results
    return state


def community_search(state: GraphRAGState) -> GraphRAGState:
    """节点3c：社区摘要检索（借鉴微软 GraphRAG 的 Global Search）"""
    query_vector = embedder.embed(state["rewritten_query"])
    
    # 在社区摘要集合中检索
    community_results = milvus.search(
        collection="community_summaries",
        data=[query_vector],
        top_k=3,
        output_fields=["community_id", "summary"]
    )
    
    # Map 阶段：每个社区生成局部答案
    partial_answers = []
    for community in community_results:
        partial = llm.chat(
            prompt=f"社区摘要：{community['summary']}\n问题：{state['query']}",
            temperature=0.3
        )
        partial_answers.append(partial)
    
    # Reduce 阶段：聚合
    final_summary = llm.chat(
        prompt=f"各社区分析：{partial_answers}\n问题：{state['query']}",
        temperature=0.3
    )
    
    state["community_results"] = [{"summary": final_summary}]
    return state


def mcp_search(state: GraphRAGState) -> GraphRAGState:
    """节点4：MCP 在线调用（高德/博查，异步并行）"""
    import asyncio
    
    async def parallel_mcp():
        # 并行调用高德和博查
        amap_task = asyncio.create_task(amap_mcp.search(state["entities"]))
        bocha_task = asyncio.create_task(bocha_mcp.search(state["entities"]))
        
        amap_result, bocha_result = await asyncio.gather(
            amap_task, bocha_task
        )
        return [amap_result, bocha_result]
    
    state["mcp_results"] = asyncio.run(parallel_mcp())
    return state


def context_fusion(state: GraphRAGState) -> GraphRAGState:
    """节点5：结果融合（RRF + 格式化）"""
    # RRF 融合
    fused = rrf_fuse([
        state["vector_results"],
        state["graph_results"],
        state.get("community_results", [])
    ])
    
    # 格式化为 LLM 上下文
    context = format_context(fused, state.get("mcp_results", []))
    state["final_context"] = context
    return state


def llm_generation(state: GraphRAGState) -> GraphRAGState:
    """节点6：LLM 生成最终答案"""
    answer = llm.chat(
        prompt=f"""
        基于以下上下文回答问题。
        
        {state['final_context']}
        
        问题：{state['query']}
        """,
        temperature=0.3
    )
    state["answer"] = answer
    return state
```

---

## 五、Neo4j GraphRAG SDK 的角色（主干）

```python
from neo4j_graphrag.retrievers import (
    VectorCypherRetriever,    # 向量+图遍历一体化
    GraphCypherRetriever,     # Text-to-Cypher
    Text2CypherRetriever      # 自然语言转 Cypher
)

# ========== 1. VectorCypherRetriever（向量优先模式）==========
# Milvus 向量检索 -> Neo4j 图遍历扩展（一体化）
vector_cypher_retriever = VectorCypherRetriever(
    driver=neo4j_driver,
    index_name="text_embedding_index",
    embedder=doubao_embedder,
    retrieval_query="""
    // 向量检索找到入口节点后，图遍历扩展
    MATCH (node)-[r:OPERATES_IN|LAUNCHED|HAS_METRIC*1..2]-(related)
    RETURN node, r, related
    LIMIT 50
    """
)

# ========== 2. Text2CypherRetriever（图谱优先模式）==========
# 自然语言直接转 Cypher（适合实体明确的查询）
text2cypher_retriever = Text2CypherRetriever(
    driver=neo4j_driver,
    llm=doubao_llm,
    neo4j_schema=graph_schema  # 图谱 Schema
)

# 用户问"喜茶在杭州有多少门店" -> 自动生成 Cypher
result = text2cypher_retriever.search("喜茶在杭州有多少门店")
# 自动生成：MATCH (b:Brand {name:"喜茶"})-[:OPERATES_IN]->(c:City {name:"杭州"})
#           MATCH (b)-[:HAS_METRIC]->(m:StoreMetric) RETURN m.store_count
```

---

## 六、借鉴微软 GraphRAG 的社区发现（自建实现）

```python
import networkx as nx
from community import community_louvain

class CommunityBuilder:
    """借鉴微软 GraphRAG 的社区发现，用 LangGraph 编排"""
    
    def build_communities(self, neo4j_driver, llm):
        # Step 1: 从 Neo4j 加载图结构
        G = self._load_graph_from_neo4j(neo4j_driver)
        
        # Step 2: Leiden 社区发现
        communities = community_louvain.best_partition(G, resolution=1.0)
        
        # Step 3: LLM 生成社区摘要
        community_summaries = {}
        for comm_id, nodes in self._group_by_community(communities).items():
            # 获取社区内所有节点描述
            node_descriptions = self._get_node_descriptions(neo4j_driver, nodes)
            
            # LLM 生成摘要
            summary = llm.chat(
                prompt=f"总结以下节点的共同特征：\n{node_descriptions}",
                temperature=0.3
            )
            community_summaries[comm_id] = summary
        
        # Step 4: 社区摘要向量化存入 Milvus + Redis 缓存
        for comm_id, summary in community_summaries.items():
            milvus.insert(
                collection="community_summaries",
                data=[{
                    "embedding": embedder.embed(summary),
                    "community_id": comm_id,
                    "summary": summary
                }]
            )
            redis.setex(f"community:{comm_id}", 86400, summary)
        
        return community_summaries
```

---

## 七、企业级增强能力

### 7.1 限流（令牌桶算法）

```python
import time
from collections import deque

class TokenBucket:
    """令牌桶限流：保护 LLM API 不被打爆"""
    
    def __init__(self, capacity: int, refill_rate: float):
        self.capacity = capacity        # 桶容量（最大并发）
        self.refill_rate = refill_rate  # 每秒补充令牌数
        self.tokens = capacity          # 当前令牌数
        self.last_refill = time.time()
    
    def acquire(self) -> bool:
        """获取令牌，返回是否成功"""
        now = time.time()
        # 补充令牌
        elapsed = now - self.last_refill
        self.tokens = min(self.capacity, self.tokens + elapsed * self.refill_rate)
        self.last_refill = now
        
        if self.tokens >= 1:
            self.tokens -= 1
            return True
        return False

# 德高项目配置：
# 豆包 API 限流：10 QPS -> capacity=10, refill_rate=10
llm_rate_limiter = TokenBucket(capacity=10, refill_rate=10)
```

### 7.2 熔断降级（Circuit Breaker）

```python
from enum import Enum

class CircuitState(Enum):
    CLOSED = "closed"      # 正常
    OPEN = "open"          # 熔断（拒绝请求）
    HALF_OPEN = "half_open" # 半开（试探恢复）

class CircuitBreaker:
    """熔断器：GraphRAG 服务故障时降级到简单 RAG"""
    
    def __init__(self, failure_threshold=5, recovery_timeout=60):
        self.failure_threshold = failure_threshold  # 连续失败5次熔断
        self.recovery_timeout = recovery_timeout    # 60秒后尝试恢复
        self.failure_count = 0
        self.state = CircuitState.CLOSED
        self.last_failure_time = None
    
    def call(self, func, *args, **kwargs):
        """调用函数，带熔断保护"""
        if self.state == CircuitState.OPEN:
            if time.time() - self.last_failure_time > self.recovery_timeout:
                self.state = CircuitState.HALF_OPEN
            else:
                # 熔断中，降级到简单 RAG
                return self._fallback(*args, **kwargs)
        
        try:
            result = func(*args, **kwargs)
            self._on_success()
            return result
        except Exception as e:
            self._on_failure()
            return self._fallback(*args, **kwargs)
    
    def _fallback(self, *args, **kwargs):
        """降级策略：GraphRAG 挂了 -> 用纯向量 RAG"""
        return vector_rag_search(*args, **kwargs)
    
    def _on_success(self):
        self.failure_count = 0
        self.state = CircuitState.CLOSED
    
    def _on_failure(self):
        self.failure_count += 1
        self.last_failure_time = time.time()
        if self.failure_count >= self.failure_threshold:
            self.state = CircuitState.OPEN
```

### 7.3 语义缓存

```python
class SemanticCache:
    """语义缓存：相似问题复用答案，不重复调用 LLM"""
    
    def __init__(self, redis_client, embedder, threshold=0.95):
        self.redis = redis_client
        self.embedder = embedder
        self.threshold = threshold  # 相似度阈值
    
    def get(self, query: str) -> str:
        """查缓存：如果缓存中有相似度>0.95的问题，直接返回"""
        query_vector = self.embedder.embed(query)
        
        # 在缓存向量集合中检索
        results = milvus.search(
            collection="query_cache",
            data=[query_vector],
            top_k=1,
            output_fields=["query", "answer"]
        )
        
        if results and results[0].score >= self.threshold:
            return results[0].answer  # 缓存命中
        return None  # 缓存未命中
    
    def set(self, query: str, answer: str):
        """写缓存"""
        query_vector = self.embedder.embed(query)
        milvus.insert(
            collection="query_cache",
            data=[{
                "embedding": query_vector,
                "query": query,
                "answer": answer,
                "timestamp": time.time()
            }]
        )
```

### 7.4 异步任务管理（视频生成等长耗时任务）

```python
import asyncio
from datetime import datetime

class AsyncTaskManager:
    """异步任务管理：视频生成等长耗时任务"""
    
    async def submit_video_task(self, brand: str, city: str, style: str):
        # 创建任务记录
        task_id = generate_uuid()
        mysql.execute(
            "INSERT INTO video_task (task_id, brand, city, style, status, created_at) "
            "VALUES (%s, %s, %s, %s, 'PENDING', %s)",
            (task_id, brand, city, style, datetime.now())
        )
        
        # 异步执行（不阻塞主请求）
        asyncio.create_task(self._execute_video_task(task_id, brand, city, style))
        
        return {"task_id": task_id, "status": "PENDING"}
    
    async def _execute_video_task(self, task_id, brand, city, style):
        try:
            # 更新状态为 PROCESSING
            self._update_status(task_id, "PROCESSING")
            
            # 调用即梦 API 生成视频（2-5分钟）
            result = await jimeng_api.generate(brand, city, style)
            
            # 更新状态为 COMPLETED
            self._update_status(task_id, "COMPLETED", video_url=result.url)
            
            # Redis 缓存 7 天 TTL
            redis.setex(f"video:{task_id}", 7*86400, result.url)
            
        except Exception as e:
            # 失败重试（最多3次）
            self._update_status(task_id, "FAILED", error=str(e))
            await self._retry(task_id, brand, city, style)
```

---

## 八、与教学版的关键区别

| 维度 | 教学版 | 企业版 |
|------|--------|--------|
| 检索编排 | if-else 硬编码 | LangGraph StateGraph 状态机 |
| 图谱检索 | LlamaIndex KnowledgeGraphIndex | Neo4j GraphRAG SDK |
| 容错 | 无 | 熔断 + 降级 + 重试 |
| 缓存 | 无 | 语义缓存（相似问题复用） |
| 限流 | 无 | 令牌桶保护 LLM API |
| 异步任务 | 无 | 任务队列 + 状态轮询 |
| 监控 | 无 | Prometheus + Grafana |
| 可观测性 | 无 | 全链路追踪 |

---

## 九、面试怎么讲

> "我们用的是混合框架架构：Neo4j GraphRAG SDK 做图谱检索主干，LangGraph 做流程编排，Milvus 做向量召回，借鉴微软 GraphRAG 的社区摘要和 Global Search。
>
> 选 Neo4j GraphRAG SDK 是因为它的 VectorCypherRetriever 把向量检索和图遍历一体化了，不需要自己拼接。选 LangGraph 做编排是因为检索流程有条件路由（不同意图走不同路径），StateGraph 天然支持。社区发现和 Global Search 借鉴微软 GraphRAG 的思想，但自己用 networkx + leidenalg 实现，因为微软 GraphRAG 框架太重，默认用 OpenAI，换豆包成本高。
>
> 企业级还加了限流（令牌桶保护豆包 API）、熔断降级（GraphRAG 挂了降级到纯向量 RAG）、语义缓存（相似问题复用答案）、异步任务管理（视频生成不阻塞主请求）。"
