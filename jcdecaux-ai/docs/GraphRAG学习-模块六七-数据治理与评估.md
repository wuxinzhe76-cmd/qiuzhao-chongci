# 模块六+七：数据治理与安全 + 评估与成本优化

> 📅 2026-07-18
> 德高场景：1000人公司，9个城市，5-10万图谱节点

---

# 模块六：数据治理与安全

## 6.1 数据预处理与实体消歧

### 6.1.1 实体消歧流程（离线索引阶段）

```
原始文档中出现：
  "喜茶" / "HEYTEA" / "喜茶品牌" / "深圳美西西餐饮管理有限公司旗下喜茶"

不能创建4个独立节点，必须消歧为1个：

  实体抽取
    ↓
  实体标准化（HEYTEA -> 喜茶）
    ↓
  实体消歧与合并（判断多个别名是否指向同一实体）
    ↓
  写入 Neo4j（只创建1个 Brand 节点）
```

```python
class EntityResolver:
    """实体消歧：别名归一化 + 重复检测"""
    
    def __init__(self):
        # 领域词典：标准名 -> 别名列表
        self.alias_map = {
            "喜茶": ["HEYTEA", "heytea", "喜茶品牌", "深圳美西西餐饮"],
            "霸王茶姬": ["CHAGEE", "chagee", "霸王茶姬品牌"],
            "德高": ["JCDecaux", "德高集团", "JCDecaux Group"],
        }
        # 同义词映射
        self.synonyms = {
            "门店扩张": ["新开门店", "门店增长", "开店", "store expansion"],
            "广告投放": ["户外广告", "媒体投放", "advertising"],
        }
    
    def resolve(self, entity_name: str) -> str:
        """实体消歧：别名归一化到标准名"""
        # 精确匹配
        for canonical, aliases in self.alias_map.items():
            if entity_name in aliases or entity_name == canonical:
                return canonical
        
        # 模糊匹配（编辑距离）
        for canonical, aliases in self.alias_map.items():
            all_names = [canonical] + aliases
            for name in all_names:
                if self._similarity(entity_name, name) > 0.85:
                    return canonical
        
        return entity_name  # 未知实体，保留原名
    
    def _similarity(self, s1: str, s2: str) -> float:
        """计算字符串相似度（编辑距离）"""
        from difflib import SequenceMatcher
        return SequenceMatcher(None, s1, s2).ratio()
```

### 6.1.2 数据质量监控

```python
# 数据质量指标
DATA_QUALITY_METRICS = {
    "entity_duplicate_rate": "重复实体比例（应 < 5%）",
    "orphan_node_rate": "孤立节点比例（无关系的节点，应 < 10%）",
    "source_missing_rate": "缺来源标注的节点比例（应 < 5%）",
    "relation_confidence_avg": "关系平均置信度（应 > 0.7）",
    "entity_resolution_accuracy": "实体消歧准确率（应 > 90%）",
}

def check_data_quality():
    """定期数据质量检查"""
    with neo4j.session() as session:
        # 检查重复实体
        duplicates = session.run("""
            MATCH (n)
            WITH n.name as name, collect(n) as nodes
            WHERE size(nodes) > 1
            RETURN name, size(nodes) as count
        """)
        
        # 检查孤立节点
        orphans = session.run("""
            MATCH (n)
            WHERE NOT (n)--()
            RETURN count(n) as orphan_count
        """)
        
        # 检查缺来源的节点
        missing_source = session.run("""
            MATCH (n)
            WHERE n.source_doc IS NULL
            RETURN count(n) as missing_count
        """)
```

---

## 6.2 增量更新与版本管理

### 6.2.1 增量更新策略

```python
class IncrementalUpdater:
    """增量更新：只处理新增/修改的文档，不全量重建"""
    
    def update(self, new_documents: list, modified_documents: list, deleted_documents: list):
        # 新增文档：抽取实体关系，写入 Neo4j + Milvus
        for doc in new_documents:
            self._add_document(doc)
        
        # 修改文档：先删除旧的，再添加新的
        for doc in modified_documents:
            self._delete_document(doc.id)
            self._add_document(doc)
        
        # 删除文档：删除关联的节点和向量
        for doc in deleted_documents:
            self._delete_document(doc.id)
        
        # 重新生成受影响的社区摘要
        self._update_affected_communities(new_documents + modified_documents)
    
    def _add_document(self, doc):
        chunks = self.splitter.split(doc.text)
        for chunk in chunks:
            # LLM 抽取实体关系
            entities, relations = self.extractor.extract(chunk)
            # 写入 Neo4j
            self.neo4j_builder.build(entities, relations, chunk.id)
            # 写入 Milvus
            self.milvus_builder.build(chunk, entities)
    
    def _delete_document(self, doc_id):
        # 删除 Neo4j 中该文档的节点和关系
        neo4j.run("MATCH (n {source_doc: $doc_id}) DETACH DELETE n", doc_id=doc_id)
        # 删除 Milvus 中该文档的向量
        milvus.delete(f"source_doc == '{doc_id}'")
```

### 6.2.2 索引版本管理

```python
# 简单版本化（德高规模不需要蓝绿索引）
class IndexVersionManager:
    """索引版本管理"""
    
    CURRENT_VERSION = "v1.2.3"
    
    def get_version(self) -> str:
        return redis.get("index_version") or self.CURRENT_VERSION
    
    def update_version(self, new_version: str):
        # 更新版本号
        redis.set("index_version", new_version)
        # 记录更新日志
        mysql.execute(
            "INSERT INTO index_version_log (version, updated_at) VALUES (%s, %s)",
            (new_version, datetime.now())
        )
    
    def reconcile(self):
        """对账任务：检查 Neo4j 和 Milvus 一致性"""
        # Neo4j 中的 chunk_id
        neo4j_chunks = neo4j.run("MATCH (n) WHERE n.chunk_id IS NOT NULL RETURN n.chunk_id")
        # Milvus 中的 chunk_id
        milvus_chunks = milvus.query("SELECT chunk_id FROM text_chunks")
        
        missing_in_milvus = set(neo4j_chunks) - set(milvus_chunks)
        missing_in_neo4j = set(milvus_chunks) - set(neo4j_chunks)
        
        if missing_in_milvus or missing_in_neo4j:
            alert(f"索引不一致: Milvus缺{len(missing_in_milvus)}, Neo4j缺{len(missing_in_neo4j)}")
```

---

## 6.3 访问控制与审计

### 6.3.1 按城市/部门权限隔离

```python
class AccessControl:
    """权限控制：按城市/部门隔离"""
    
    def get_user_permissions(self, user_id: str) -> dict:
        """获取用户权限"""
        return mysql.query(
            "SELECT department_id, city, role FROM user WHERE id = %s",
            (user_id,)
        )
    
    def graph_traverse_with_acl(self, entity_name, user_perm, hops=2):
        """图遍历加权限过滤"""
        cypher = f"""
        MATCH path = (n {{name: $name}})-[*1..{hops}]-(related)
        WHERE related.department_id = $dept_id
           OR related.city = $city
           OR related.is_public = true
        RETURN nodes(path), relationships(path)
        LIMIT 50
        """
        return neo4j.run(cypher, 
                         name=entity_name,
                         dept_id=user_perm["department_id"],
                         city=user_perm["city"])
    
    def vector_search_with_acl(self, query, user_perm, top_k=10):
        """向量检索加权限过滤"""
        return milvus.search(
            collection="text_chunks",
            data=[embed(query)],
            top_k=top_k,
            filter=f"department_id == '{user_perm['department_id']}' OR is_public == true",
            output_fields=["text_chunk", "entity_ids"]
        )
```

### 6.3.2 审计日志

```python
class AuditLogger:
    """审计日志：每次决策有完整记录"""
    
    def log_query(self, user_id, query, answer, sources, confidence):
        mysql.execute(
            """INSERT INTO audit_log 
            (user_id, query, answer, sources, confidence, created_at)
            VALUES (%s, %s, %s, %s, %s, %s)""",
            (user_id, query, answer, json.dumps(sources), confidence, datetime.now())
        )

# 审计日志结构
{
    "user_id": "sales_001",
    "department_id": "shanghai",
    "query": "喜茶在杭州的投放建议",
    "answer": "建议投武林广场站...",
    "sources": ["neo4j:Brand:喜茶", "milvus:chunk_005", "mcp:amap:武林广场"],
    "confidence": 0.88,
    "created_at": "2024-07-18 14:30:00"
}
```

---

## 6.4 临时数据隔离与 TTL

```python
class TempDataIsolation:
    """临时数据隔离：视频生成等临时数据按用户隔离 + 7天TTL"""
    
    def store_video_result(self, user_id, project_id, task_id, video_url):
        # 按 user_id + project_id 隔离
        cache_key = f"video:{user_id}:{project_id}:{task_id}"
        redis.setex(cache_key, 7 * 86400, video_url)  # 7天自动过期
    
    def get_video_result(self, user_id, project_id, task_id):
        cache_key = f"video:{user_id}:{project_id}:{task_id}"
        return redis.get(cache_key)  # 过期自动删除
```

---

# 模块七：评估与成本优化

## 7.1 检索质量评估

### 7.1.1 评估指标

| 指标 | 含义 | 计算方式 |
|------|------|---------|
| **Recall@K** | 召回率：前K个结果中包含正确答案的比例 | 命中数 / 应命中总数 |
| **Precision@K** | 准确率：前K个结果中相关结果的比例 | 相关数 / K |
| **MRR** | 平均倒数排名：正确答案的排名倒数 | 1/rank 的平均值 |
| **NDCG** | 归一化折损累积增益 | 考虑排名位置的加权 |

```python
class RetrievalEvaluator:
    """检索质量评估"""
    
    def evaluate(self, test_cases: list) -> dict:
        """
        test_cases: [{"query": "...", "relevant_docs": ["doc1", "doc2"]}, ...]
        """
        results = {"recall@5": [], "precision@5": [], "mrr": []}
        
        for case in test_cases:
            # 执行检索
            retrieved = self.retriever.retrieve(case["query"], top_k=5)
            retrieved_ids = [r.doc_id for r in retrieved]
            relevant = set(case["relevant_docs"])
            
            # Recall@5
            hits = len(set(retrieved_ids) & relevant)
            results["recall@5"].append(hits / len(relevant))
            
            # Precision@5
            results["precision@5"].append(hits / 5)
            
            # MRR
            for rank, doc_id in enumerate(retrieved_ids, 1):
                if doc_id in relevant:
                    results["mrr"].append(1 / rank)
                    break
            else:
                results["mrr"].append(0)
        
        return {
            "recall@5": sum(results["recall@5"]) / len(results["recall@5"]),
            "precision@5": sum(results["precision@5"]) / len(results["precision@5"]),
            "mrr": sum(results["mrr"]) / len(results["mrr"])
        }
```

---

## 7.2 生成质量评估（RAGAS 框架）

```python
# RAGAS 评估指标
RAGAS_METRICS = {
    "faithfulness": "忠实度：答案是否忠于检索到的上下文（不幻觉）",
    "answer_relevancy": "相关性：答案是否回答了用户问题",
    "context_precision": "上下文精确度：检索到的上下文是否相关",
    "context_recall": "上下文召回率：是否检索到了所有必要信息"
}

# 评估示例
test_case = {
    "question": "喜茶在杭州应该投哪个地铁站？",
    "answer": "建议投武林广场站，日均人流42万，周边5个商场",
    "contexts": [
        "喜茶杭州50家门店，坪效20万",
        "武林广场站日均42万人流",
        "喜茶历史投放凤起路站曝光500万"
    ],
    "ground_truth": "武林广场站"
}

# faithfulness 评估：答案中的"42万""5个商场"是否在上下文中出现
# answer_relevancy 评估：答案是否回答了"投哪个地铁站"
# context_precision：检索到的3段上下文是否都相关
```

---

## 7.3 成本优化

### 7.3.1 索引成本

```python
# GraphRAG 索引成本构成
INDEXING_COST = {
    "LLM实体关系抽取": "占索引成本 70%",  # 每个文本块调一次 LLM
    "Embedding向量化": "占索引成本 15%",  # 每个文本块调一次 Embedding
    "社区摘要生成": "占索引成本 10%",     # 每个社区调一次 LLM
    "存储成本": "占索引成本 5%",          # Neo4j + Milvus 存储
}

# 降本策略
COST_REDUCTION = {
    "抽取用轻量模型": "doubao-2.0-lite 替代 doubao-2.0-pro，成本降 80%",
    "Gleaning限制": "max_gleaning=1，只补充1轮，不无限抽取",
    "缓存LLM结果": "相同文本块的抽取结果缓存，避免重复调用",
    "批量处理": "批量调用 LLM API，减少请求次数",
    "增量更新": "只处理新增文档，不全量重建",
}
```

### 7.3.2 查询成本

```python
# GraphRAG 查询成本构成
QUERY_COST = {
    "Query理解": "1次LLM调用（意图分类+实体识别）",
    "向量检索": "0次LLM调用（Milvus原生）",
    "图遍历": "0次LLM调用（Neo4j Cypher）",
    "Global Search": "N次LLM调用（N=社区数，最贵）",
    "LLM生成": "1次LLM调用（最终答案生成）",
}

# 降本策略
QUERY_COST_REDUCTION = {
    "混合路由": "70%走Vector RAG（便宜），30%走GraphRAG（贵）",
    "语义缓存": "相似问题复用答案，命中率60%省60%Token",
    "Global Search限制": "只在全局分析时用，不滥用",
    "社区摘要预加载": "Redis缓存社区摘要，不每次重新生成",
    "流式输出": "首Token时间<3秒，用户感知快",
}
```

### 7.3.3 德高项目成本估算

```python
# 德高项目月度成本估算
MONTHLY_COST = {
    "索引成本（一次性）": {
        "5万文本块 * LLM抽取": "5万 * 0.001元/千token * 500token/块 = 25元",
        "5万文本块 * Embedding": "5万 * 0.0001元/千token = 5元",
        "100社区 * LLM摘要": "100 * 0.001元/千token * 1000token = 0.1元",
        "总计": "约30元（一次性）"
    },
    "查询成本（月度）": {
        "日均1000查询 * 30天": "3万查询/月",
        "70% Vector RAG": "2.1万 * 0.01元/查询 = 210元",
        "30% GraphRAG": "0.9万 * 0.05元/查询 = 450元",
        "缓存省60%": "实际 264元",
        "总计": "约264元/月"
    },
    "基础设施": {
        "京东云4核16G": "约200元/月",
        "总计": "约200元/月"
    },
    "月度总成本": "约500元/月"
}
```

---

## 7.4 性能优化

### 7.4.1 延迟优化

```python
LATENCY_OPTIMIZATION = {
    "向量检索": {
        "索引选择": "HNSW（查询快，内存大）替代 IVF_FLAT",
        "预加载": "热点向量预加载到内存",
        "预期": "50ms -> 20ms"
    },
    "图遍历": {
        "索引优化": "节点属性加索引（name/department_id）",
        "跳数限制": "最多2跳，3跳以上走社区摘要",
        "预期": "300ms -> 150ms"
    },
    "LLM生成": {
        "流式输出": "SSE首Token < 3秒",
        "轻量模型": "简单查询用lite版，复杂用pro版",
        "预期": "5s -> 3s首Token"
    },
    "并行化": {
        "向量+图并行": "Milvus和Neo4j同时查询",
        "MCP并行": "高德和博查同时调用",
        "预期": "总延迟降30%"
    }
}
```

### 7.4.2 吞吐量优化

```python
THROUGHPUT_OPTIMIZATION = {
    "批处理": "多个查询合并成批，减少LLM调用次数",
    "连接池": "Neo4j/Milvus/Redis连接池复用",
    "异步IO": "FastAPI异步处理，单机1000+并发",
    "缓存": "三级缓存命中率60%，减少60%下游调用",
}
```

---

## 模块六+七总结

### 模块六：数据治理与安全

| 知识点 | 一句话记忆 |
|--------|----------|
| 实体消歧 | 别名归一化（HEYTEA->喜茶）+ 模糊匹配 |
| 增量更新 | 只处理新增/修改/删除的文档，不全量重建 |
| 索引版本 | 版本号 + 对账任务（德高不需要蓝绿索引） |
| 权限隔离 | 按城市/部门过滤，图遍历和向量检索都加ACL |
| 审计日志 | 每次决策有完整记录（用户/查询/答案/来源） |
| 临时数据 | user_id+project_id 隔离 + 7天TTL |

### 模块七：评估与成本优化

| 知识点 | 一句话记忆 |
|--------|----------|
| 检索质量 | Recall@K / Precision@K / MRR |
| 生成质量 | RAGAS：忠实度/相关性/上下文精确度 |
| 索引成本 | LLM抽取占70%，用lite版降80% |
| 查询成本 | 70% Vector RAG + 30% GraphRAG + 缓存省60% |
| 德高月成本 | 约500元/月（索引30元+查询264元+服务器200元） |
| 延迟优化 | HNSW索引+2跳限制+流式输出+并行检索 |
