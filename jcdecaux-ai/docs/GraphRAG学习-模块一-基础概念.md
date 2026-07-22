# 模块一：GraphRAG 基础概念与核心原理

> 📅 2026-07-18 | 基于微软 GraphRAG 官方文档 + Neo4j 官方实践 + 2026 年最新行业落地指南
> 学习模式：先教学后考核

---

## 1.1 什么是 GraphRAG

### 1.1.1 官方定义

**GraphRAG（Graph Retrieval-Augmented Generation，图检索增强生成）** 是一种将知识图谱（Knowledge Graph）的结构化推理能力与向量检索的语义匹配能力相结合的 RAG 增强技术。

核心流程：先用 LLM 从非结构化文本中自动抽取实体和关系，构建知识图谱，再利用图结构的多跳推理能力增强检索，最后将结构化子图上下文注入 LLM 生成答案。

> 微软研究院 2024 年首次提出 "from local to global" 框架。Neo4j 将其定义为 "向量检索 + 图推理的混合架构"。

### 1.1.2 为什么需要 GraphRAG（传统 RAG 的三大结构性瓶颈）

传统 RAG（2020 年 Lewis 等人提出）核心流程：**文档分块 -> 向量嵌入 -> 语义相似度检索 -> LLM 生成回答**。

在企业级场景中暴露三大问题：

| 瓶颈 | 表现 | 根因 |
|------|------|------|
| **无法处理全局性问题** | 问"所有报告中最常提到的风险因素有哪些"，向量检索只能返回语义相近片段 | 向量空间是"扁平"的，只有距离关系，没有结构连接 |
| **多跳推理断裂** | 问"A供应商的二级子公司是否涉及B合规风险"，需要串联多段知识 | 文本分块割裂了跨段落的实体关联 |
| **语义孤岛效应** | 固定长度分块将连贯段落强行拆分，因果/时序/层级关系丢失 | 分块策略不可逆地破坏了知识网络结构 |

**代码示例：传统 RAG 的多跳推理失败场景（反例）**

```python
# ❌ 反例：传统 RAG 无法回答多跳推理问题
from langchain.vectorstores import Milvus
from langchain.embeddings import OpenAIEmbeddings

class TraditionalRAG:
    def __init__(self):
        self.embeddings = OpenAIEmbeddings()
        self.vector_store = Milvus(
            embedding_function=self.embeddings,
            connection_args={"host": "localhost", "port": "19530"}
        )
    
    def query(self, question: str) -> str:
        # Step 1: 向量相似度检索（只能找到"语义相近"的文本块）
        docs = self.vector_store.similarity_search(question, k=5)
        
        # Step 2: 拼接上下文
        context = "\n".join([doc.page_content for doc in docs])
        
        # Step 3: LLM 生成
        return llm.chat(
            system_prompt="基于以下信息回答问题",
            user_prompt=f"上下文：{context}\n问题：{question}"
        )

# 用户问："XX茶饮在杭州的门店扩张对户外广告投放有什么影响？"
# 传统 RAG 检索到：
#   - "XX茶饮是奶茶品牌"（语义相似，但没用）
#   - "奶茶行业报告"（相关，但不具体）
# 它找不到这种推理链：
#   XX茶饮 -> 杭州有12家门店 -> 坪效环比+3% -> 同类品牌扩张期户外投放+2.1倍
```

### 1.1.3 GraphRAG 的核心思想

一句话概括：**在向量检索之上，叠加知识图谱的结构化理解能力。**

GraphRAG 同时融合了 LLM 与知识图谱的两种协作模式：
1. **LLM-enhanced KG**：用 LLM 自动从文本中抽取实体和关系，动态构建知识图谱
2. **KG-enhanced LLM**：用图谱的结构化知识辅助 LLM 检索和推理

**代码示例：GraphRAG 的多跳推理成功场景（正例）**

```python
# ✅ 正例：GraphRAG 通过图遍历实现多跳推理
from neo4j import GraphDatabase

class GraphRAG:
    def __init__(self, uri, user, password):
        self.driver = GraphDatabase.driver(uri, auth=(user, password))
    
    def query(self, question: str, brand: str, city: str) -> str:
        # Step 1: 向量召回（定位起始实体）
        vector_results = milvus.search(
            collection="campaigns",
            query_embedding=embed(question),
            top_k=20
        )
        
        # Step 2: 图遍历（多跳推理：品牌->城市->门店指标->历史案例）
        cypher = """
        MATCH (b:Brand {name: $brand})-[:OPERATES_IN]->(c:City {name: $city})
        MATCH (b)-[:HAS_METRIC]->(m:StoreMetric)
        MATCH (b)-[:LAUNCHED]->(camp:Campaign)-[:LOCATED_IN]->(c)
        RETURN m.store_count, m.avg_sales_per_store, 
               camp.impression, camp.budget
        ORDER BY m.date DESC LIMIT 3
        """
        with self.driver.session() as session:
            graph_results = session.run(cypher, brand=brand, city=city)
        
        # Step 3: 融合两路结果
        context = self._merge_results(vector_results, graph_results)
        
        # Step 4: LLM 生成（基于结构化上下文）
        return llm.chat(
            system_prompt="你是户外广告决策助手...",
            user_prompt=f"结构化上下文：{context}\n问题：{question}"
        )

# 用户问："XX茶饮在杭州的门店扩张对户外广告投放有什么影响？"
# GraphRAG 通过图遍历找到：
#   XX茶饮 -> OPERATES_IN -> 杭州
#   XX茶饮 -> HAS_METRIC -> 门店数50, 坪效20万, 2024-04
#   XX茶饮 -> LAUNCHED -> 杭州地铁广告, 曝光500万, 预算100万
# 推理链完整，可以给出精准建议
```

---

## 1.2 GraphRAG 的五大核心组件

```
┌─────────────────────────────────────────────────────────┐
│                  GraphRAG 五大核心组件                    │
├─────────────┬──────────────┬──────────────┬─────────────┤
│  ① 实体识别  │  ② 关系抽取  │  ③ 图谱构建  │ ④ 图检索    │
│   (NER)     │ (RE)         │ (KG Build)   │ (Graph)     │
├─────────────┼──────────────┼──────────────┼─────────────┤
│  ⑤ 答案生成  │              │              │             │
│ (Answer Gen)│              │              │             │
└─────────────┴──────────────┴──────────────┴─────────────┘
```

### ① 实体识别（Entity Recognition / NER）

从私有数据中提取核心实体。工业场景中通用 NER 模型对专业术语容易误判，需要结合领域词典和微调模型。

```python
# LLM 驱动的实体抽取（GraphRAG 的核心创新）
ENTITY_EXTRACTION_PROMPT = """
你是一个户外广告领域的实体抽取专家。从以下文本中抽取实体，返回 JSON 格式。

实体类型：
- Brand: 品牌名（如 XX茶饮、某汽车品牌）
- City: 城市名（如 杭州、上海）
- Campaign: 广告投放活动（如 杭州地铁灯箱广告202405）
- StoreMetric: 门店指标（如 坪效、门店数、销售额）
- Industry: 行业（如 快消、美妆、汽车）

文本：{text}

输出格式：
{{"entities": [{{"name": "实体名", "type": "类型", "properties": {{}}}}]}}
"""

def extract_entities(text: str, llm_client) -> list:
    """使用 LLM 从文本中抽取实体"""
    prompt = ENTITY_EXTRACTION_PROMPT.format(text=text)
    response = llm_client.chat(
        model="doubao-2.0",
        messages=[{"role": "user", "content": prompt}],
        temperature=0.1  # 低温度保证抽取稳定性
    )
    result = json.loads(response)
    return result["entities"]

# 测试
text = "2024年5月，XX茶饮在杭州地铁投放了为期1个月的灯箱广告，投放后门店客流提升15%。"
entities = extract_entities(text, llm_client)
# 输出：
# [
#   {"name": "XX茶饮", "type": "Brand", "properties": {}},
#   {"name": "杭州", "type": "City", "properties": {}},
#   {"name": "杭州地铁灯箱广告202405", "type": "Campaign", "properties": {"duration": "1个月"}},
#   {"name": "客流提升15%", "type": "StoreMetric", "properties": {"metric_type": "客流增长率", "value": 0.15}}
# ]
```

**正例 vs 反例：实体识别的工程实践**

```python
# ✅ 正例：领域词典 + LLM 双重校验，提高工业场景准确率
class RobustEntityExtractor:
    def __init__(self, llm_client, domain_dict):
        self.llm = llm_client
        # 领域词典：预定义的实体别名映射
        self.domain_dict = {
            "XX茶饮": ["XX奶茶", "XX tea", "xx茶飲"],
            "杭州": ["杭州市", "Hangzhou"],
            "地铁广告": ["地铁灯箱", "地铁站厅", "地铁通道"],
        }
    
    def extract(self, text: str) -> list:
        # Step 1: LLM 初步抽取
        entities = extract_entities(text, self.llm)
        
        # Step 2: 领域词典校验（实体消歧）
        for entity in entities:
            for canonical, aliases in self.domain_dict.items():
                if entity["name"] in aliases:
                    entity["name"] = canonical  # 归一化到标准名
                    entity["verified"] = True
                    break
        
        return entities

# ❌ 反例：纯 LLM 抽取，没有领域词典校验
# 问题：同一个实体在不同文档中可能被抽成不同名字
#   文档1: "XX奶茶" -> 实体名 "XX奶茶"
#   文档2: "XX茶饮" -> 实体名 "XX茶饮"
# 结果：图谱中出现两个独立节点，本应是同一个品牌
```

### ② 关系抽取（Relation Extraction）

挖掘实体间的语义关联（如"品牌A属于行业B""品牌A在城市B有投放"）。

```python
RELATION_EXTRACTION_PROMPT = """
从以下文本中抽取实体间的关系，返回 JSON 格式。

关系类型：
- OPERATES_IN: Brand -> City（品牌在某城市有门店）
- HAS_METRIC: Brand -> StoreMetric（品牌的门店指标）
- LAUNCHED: Brand -> Campaign（品牌投放过广告）
- LOCATED_IN: Campaign -> City（广告投放在某城市）
- BELONGS_TO: Brand -> Industry（品牌属于某行业）
- SIMILAR_TO: Campaign -> Campaign（相似广告案例）

文本：{text}
已抽取实体：{entities}

输出格式：
{{"relations": [{{"head": "头实体", "relation": "关系类型", "tail": "尾实体", "properties": {{}}}}]}}
"""

def extract_relations(text: str, entities: list, llm_client) -> list:
    """使用 LLM 从文本中抽取实体间关系（三元组）"""
    prompt = RELATION_EXTRACTION_PROMPT.format(
        text=text, 
        entities=json.dumps(entities, ensure_ascii=False)
    )
    response = llm_client.chat(
        model="doubao-2.0",
        messages=[{"role": "user", "content": prompt}],
        temperature=0.1
    )
    return json.loads(response)["relations"]

# 测试
relations = extract_relations(text, entities, llm_client)
# 输出：
# [
#   {"head": "XX茶饮", "relation": "LAUNCHED", "tail": "杭州地铁灯箱广告202405", "properties": {}},
#   {"head": "杭州地铁灯箱广告202405", "relation": "LOCATED_IN", "tail": "杭州", "properties": {}},
#   {"head": "XX茶饮", "relation": "HAS_METRIC", "tail": "客流提升15%", "properties": {"date": "2024-05"}}
# ]
```

### ③ 图谱构建（Knowledge Graph Build）

将实体作为节点、关系作为边，构建属性图（Property Graph）。

```python
from neo4j import GraphDatabase

class KnowledgeGraphBuilder:
    def __init__(self, uri, user, password):
        self.driver = GraphDatabase.driver(uri, auth=(user, password))
    
    def create_constraints(self):
        """创建唯一约束（防止重复节点）"""
        with self.driver.session() as session:
            session.run("""
                CREATE CONSTRAINT brand_name_unique 
                IF NOT EXISTS 
                FOR (b:Brand) REQUIRE b.name IS UNIQUE
            """)
            session.run("""
                CREATE CONSTRAINT city_name_unique 
                IF NOT EXISTS 
                FOR (c:City) REQUIRE c.name IS UNIQUE
            """)
    
    def add_entity(self, entity: dict):
        """将实体写入 Neo4j（MERGE 语义：存在则更新，不存在则创建）"""
        label = entity["type"]
        name = entity["name"]
        properties = entity.get("properties", {})
        
        cypher = f"""
        MERGE (n:{label} {{name: $name}})
        SET n += $properties
        """
        with self.driver.session() as session:
            session.run(cypher, name=name, properties=properties)
    
    def add_relation(self, relation: dict):
        """将关系（三元组）写入 Neo4j"""
        head_type = self._infer_type(relation["head"])
        tail_type = self._infer_type(relation["tail"])
        rel_type = relation["relation"]
        properties = relation.get("properties", {})
        
        cypher = f"""
        MATCH (h:{head_type} {{name: $head_name}})
        MATCH (t:{tail_type} {{name: $tail_name}})
        MERGE (h)-[r:{rel_type}]->(t)
        SET r += $properties
        """
        with self.driver.session() as session:
            session.run(
                cypher,
                head_name=relation["head"],
                tail_name=relation["tail"],
                properties=properties
            )
    
    def build_from_text(self, text: str, llm_client):
        """完整流程：文本 -> 实体抽取 -> 关系抽取 -> 图谱构建"""
        entities = extract_entities(text, llm_client)
        relations = extract_relations(text, entities, llm_client)
        
        for entity in entities:
            self.add_entity(entity)
        for relation in relations:
            self.add_relation(relation)
        
        return len(entities), len(relations)
```

### ④ 图检索（Graph Retrieval）

基于用户查询解析出核心实体，在知识图谱中执行子图查询。

```python
class GraphRetriever:
    def __init__(self, driver):
        self.driver = driver
    
    def local_search(self, entity_name: str, entity_type: str, hops: int = 2):
        """
        局部检索：从指定实体出发，获取 N 跳邻域子图
        hops=2 表示获取两跳内的所有关联节点
        """
        cypher = f"""
        MATCH path = (n:{entity_type} {{name: $name}})-[*1..{hops}]-(related)
        RETURN nodes(path) as nodes, relationships(path) as rels
        LIMIT 50
        """
        with self.driver.session() as session:
            result = session.run(cypher, name=entity_name)
            return [record.data() for record in result]
    
    def multi_hop_reasoning(self, brand: str, city: str):
        """
        多跳推理查询：品牌 -> 城市 -> 门店指标 -> 历史案例
        这是 GraphRAG 区别于传统 RAG 的核心能力
        """
        cypher = """
        MATCH (b:Brand {name: $brand})-[:OPERATES_IN]->(c:City {name: $city})
        MATCH (b)-[:HAS_METRIC]->(m:StoreMetric)
        MATCH (b)-[:LAUNCHED]->(camp:Campaign)-[:LOCATED_IN]->(c)
        RETURN b.name as brand, c.name as city,
               m.store_count as store_count, 
               m.avg_sales_per_store as avg_sales,
               camp.impression as impression,
               camp.budget as budget
        ORDER BY m.date DESC LIMIT 5
        """
        with self.driver.session() as session:
            result = session.run(cypher, brand=brand, city=city)
            return [record.data() for record in result]
```

### ⑤ 答案生成（Answer Generation）

将图检索得到的结构化子图信息与原始查询结合，注入 LLM 生成答案。

```python
class GraphRAGAnswerGenerator:
    def __init__(self, llm_client):
        self.llm = llm_client
    
    def generate(self, question: str, graph_context: list, vector_context: list = None):
        """基于结构化子图上下文 + 向量上下文生成答案"""
        graph_text = self._format_graph_context(graph_context)
        vector_text = self._format_vector_context(vector_context or [])
        
        prompt = f"""
你是户外广告智能决策助手。基于以下结构化知识图谱上下文和补充信息回答问题。

【知识图谱结构化上下文】
{graph_text}

【补充文本信息】
{vector_text}

【用户问题】
{question}

要求：
1. 基于图谱中的实体关系给出推理过程
2. 如果数据不足，明确说明缺什么数据
3. 给出置信度评估（高/中/低）
"""
        response = self.llm.chat(
            model="doubao-2.0",
            messages=[{"role": "user", "content": prompt}],
            temperature=0.3
        )
        return response
    
    def _format_graph_context(self, context: list) -> str:
        """将图检索结果格式化为自然语言"""
        lines = []
        for item in context:
            lines.append(
                f"- 品牌「{item.get('brand')}」在城市「{item.get('city')}」"
                f"有门店{item.get('store_count')}家，"
                f"月均销售额{item.get('avg_sales')}元，"
                f"历史广告曝光{item.get('impression')}次，预算{item.get('budget')}元"
            )
        return "\n".join(lines)
```

---

## 1.3 GraphRAG vs 传统 RAG vs 混合 RAG

| 维度 | 传统 RAG（Vector RAG） | GraphRAG | 混合 RAG（Hybrid） |
|------|----------------------|----------|-------------------|
| **检索方式** | 向量相似度匹配 | 图遍历 + 向量召回 | 意图路由：简单走向量，复杂走图 |
| **推理能力** | 单跳检索，无推理 | 多跳推理（A->B->C->D） | 按需多跳 |
| **上下文理解** | 语义相近但业务无关 | 保留业务逻辑关联 | 两者结合 |
| **可解释性** | 低（黑盒相似度） | 高（推理链可追溯） | 中-高 |
| **索引成本** | 低（仅向量化） | 高（LLM 抽取实体关系） | 中（按需构建图谱） |
| **查询延迟** | 低（100-500ms） | 高（图遍历 1-3s） | 动态（简单快，复杂慢） |
| **适用场景** | 文档问答、FAQ | 多跳推理、全局分析 | 企业级综合场景 |
| **数据更新** | 重新向量化 | 节点级 CRUD | 分策略更新 |

**代码示例：混合 RAG 的意图路由（生产推荐方案）**

```python
from enum import Enum

class QueryIntent(Enum):
    """查询意图分类（决定检索策略）"""
    FACT_LOOKUP = "fact"           # 精确事实查询 -> 图谱精确匹配
    SEMANTIC_SEARCH = "semantic"   # 语义模糊查询 -> 向量近似搜索
    MULTI_HOP_REASONING = "reasoning"  # 多跳推理 -> 子图抽取+路径规划
    GLOBAL_ANALYSIS = "global"     # 全局分析 -> 社区摘要检索

class HybridRAGRouter:
    """
    混合 RAG 路由器：根据查询意图动态选择检索策略
    这是生产环境的推荐方案（微软和 Particula 案例都采用此模式）
    """
    def __init__(self, graph_retriever, vector_retriever, llm_client):
        self.graph = graph_retriever
        self.vector = vector_retriever
        self.llm = llm_client
    
    def classify_intent(self, query: str) -> QueryIntent:
        """使用 LLM 对查询进行意图分类"""
        prompt = f"""
判断以下查询的意图类型，只返回类型代码：
- fact: 精确事实查询（如"XX茶饮的杭州门店数"）
- semantic: 语义模糊查询（如"奶茶行业最近有什么趋势"）
- reasoning: 多跳推理（如"XX茶饮扩张对广告投放的影响"）
- global: 全局分析（如"所有快消品牌的投放趋势汇总"）

查询：{query}
类型："""
        response = self.llm.chat(
            model="doubao-2.0",
            messages=[{"role": "user", "content": prompt}],
            temperature=0.0
        )
        return QueryIntent(response.strip())
    
    def retrieve(self, query: str) -> dict:
        """根据意图路由到不同检索策略"""
        intent = self.classify_intent(query)
        
        if intent == QueryIntent.FACT_LOOKUP:
            # 精确事实 -> 图谱精确匹配（最快）
            return {"strategy": "graph_exact", "results": self.graph.local_search(...)}
        
        elif intent == QueryIntent.SEMANTIC_SEARCH:
            # 语义模糊 -> 向量近似搜索
            return {"strategy": "vector_search", "results": self.vector.search(query)}
        
        elif intent == QueryIntent.MULTI_HOP_REASONING:
            # 多跳推理 -> 图遍历 + 向量召回融合
            graph_results = self.graph.multi_hop_reasoning(...)
            vector_results = self.vector.search(query, top_k=10)
            return {"strategy": "hybrid", "results": self._merge(graph_results, vector_results)}
        
        elif intent == QueryIntent.GLOBAL_ANALYSIS:
            # 全局分析 -> 社区摘要检索
            return {"strategy": "community_summary", "results": self.graph.global_search(...)}
```

> 📌 **生产实践要点**（微软 GraphRAG 官方建议）：GraphRAG 应作为"增量后端"而非完全替代。保持 Vector RAG 作为默认方案，仅在真正需要跨实体推理的查询中使用 GraphRAG。Particula 案例显示：GraphRAG 只处理 7% 的查询（多跳推理），其余 93% 由 Vector RAG 和缓存处理。

---

## 1.4 GraphRAG 的四种实现模式（Neo4j 官方分类法）

| 模式 | 名称 | 复杂度 | 适用场景 | 代表框架 |
|------|------|--------|---------|---------|
| **Type 1** | Graph-Enhanced Vector | 低 | 向量检索 + 图谱过滤 | Neo4j 原生向量 |
| **Type 2** | Vector-Guided Graph | 中 | 向量定位入口 + 图遍历扩展 | LightRAG |
| **Type 3** | Graph-First with Vector | 中高 | 图遍历为主 + 向量补充 | Microsoft GraphRAG |
| **Type 4** | Full GraphRAG Pipeline | 高 | 完整社区发现 + 分层摘要 | Microsoft GraphRAG 1.0 |

```python
# Type 1: Graph-Enhanced Vector（最轻量，推荐入门）
# 向量检索结果通过图谱关系过滤
def type1_search(query, vector_store, graph_driver):
    # Step 1: 向量检索 top-K 候选
    candidates = vector_store.similarity_search(query, k=20)
    
    # Step 2: 用图谱关系过滤（只保留有关联的实体）
    filtered = []
    for doc in candidates:
        entities = extract_entities(doc.page_content)
        has_relations = check_graph_relations(graph_driver, entities)
        if has_relations:
            filtered.append(doc)
    
    return filtered[:5]

# Type 4: Full GraphRAG Pipeline（最完整，微软 GraphRAG 1.0）
# 文本 -> 实体抽取 -> 图谱构建 -> Leiden社区发现 -> 分层摘要 -> 双路检索
# 这是德高项目采用的方案，后续模块详细讲解
```

> 📌 **选型建议**（2026 年生产案例）：先评估 LightRAG（Type 2），它以 1/100 的成本达到 GraphRAG 70-90% 的质量。只有在基准测试显示质量差距确实影响业务时，才上 Type 4 完整方案。德高项目因为有"品牌->城市->坪效->历史案例"这种明确的多跳推理需求，选择 Type 4 是合理的。

---

## 模块一总结

| 知识点 | 一句话记忆 |
|--------|----------|
| GraphRAG 定义 | 图谱结构化推理 + 向量语义匹配的 RAG 增强 |
| 传统 RAG 三大瓶颈 | 全局问题答不了、多跳推理断链、语义孤岛 |
| 五大核心组件 | NER -> RE -> KG Build -> Graph Search -> Answer Gen |
| 三种 RAG 对比 | 传统(向量) vs Graph(图+向量) vs 混合(意图路由) |
| 四种实现模式 | Type1(最轻) -> Type4(最重)，按需选择 |
| 生产建议 | GraphRAG 做增量后端，Vector RAG 做默认，按意图路由 |
