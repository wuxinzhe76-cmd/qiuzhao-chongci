# 模块四：企业级基础设施搭建

> 📅 2026-07-18
> 核心内容：图数据库选型 / 向量数据库选型 / LLM 服务接入 / Docker 部署 / 资源规划
> 德高项目场景：京东云 4核16G + Docker Compose 单机部署

---

## 4.1 图数据库选型

### 4.1.1 主流图数据库对比

| 维度 | Neo4j | NebulaGraph | HugeGraph | Amazon Neptune |
|------|-------|-------------|-----------|---------------|
| 查询语言 | Cypher | nGQL | Gremlin | Gremlin/Cypher |
| 社区版 | ✅ 免费 | ✅ 免费 | ✅ 免费 | ❌ 仅云服务 |
| 分布式 | ❌ 社区版单机 | ✅ 原生分布式 | ✅ 分布式 | ✅ 云托管 |
| 性能（单机） | 高 | 中 | 中 | 高 |
| LLM 生态 | ✅ 最完善 | 中 | 中 | 中 |
| 原生向量索引 | ✅ 5.x 支持 | ❌ | ❌ | ✅ |
| 学习成本 | 低（Cypher 直观） | 中 | 中（Gremlin 难） | 低 |
| 适用规模 | 千万级节点 | 十亿级节点 | 亿级节点 | 亿级节点 |

### 4.1.2 德高项目选型：Neo4j 5.x 社区版

**选型理由**：

```python
# 德高项目场景：2000+ 品牌客户，节点规模约 5-10 万
# 单机部署（京东云 4核16G），不需要分布式
# LLM 生态最完善（LlamaIndex / LangChain 都原生支持）

选型决策 = {
    "图数据库": "Neo4j 5.x 社区版",
    "理由": [
        "节点规模 5-10 万，单机足够",          # 不需要 NebulaGraph 的分布式
        "Cypher 查询语言直观易学",             # 比 Gremlin 学习成本低
        "LLM 生态最完善",                     # LlamaIndex 原生支持
        "5.x 支持原生向量索引",                # 可选不部署独立 Milvus
        "社区版免费",                         # 企业版收费高
    ],
    "版本": "neo4j:5-community",
    "部署方式": "Docker 容器",
    "内存限制": "2GB（heap）"
}
```

### 4.1.3 Neo4j 核心配置

```python
# Neo4j Docker 配置（德高项目）
neo4j_config = {
    "image": "neo4j:5-community",
    "environment": {
        # 认证配置
        "NEO4J_AUTH": "neo4j/password123",
        
        # 内存配置（16GB 服务器分配 2GB 给 Neo4j）
        "NEO4J_server_memory_heap_initial__size": "1G",
        "NEO4J_server_memory_heap_max__size": "2G",
        "NEO4J_server_memory_pagecache_size": "1G",  # 页面缓存（图数据缓存）
        
        # 网络
        "NEO4J_server_bolt_listen__address": ":7687",  # Bolt 协议端口
        "NEO4J_server_http_listen__address": ":7474",  # Web 界面端口
        
        # 插件（APOC = Awesome Procedures On Cypher，扩展函数）
        "NEO4JLABS_PLUGINS": '["apoc"]',
    },
    "ports": {
        "7474": "7474",  # Web 界面
        "7687": "7687"   # Bolt 协议（Python 驱动连接）
    },
    "volumes": {
        "neo4j_data": "/data",          # 数据持久化
        "neo4j_logs": "/logs"           # 日志
    }
}
```

### 4.1.4 什么时候该换 NebulaGraph？

```python
# 判断是否需要换分布式图数据库
def should_use_distributed_graph(node_count, query_latency, budget):
    if node_count > 10_000_000:  # 千万级以上
        return "NebulaGraph"  # 需要分布式
    if query_latency > 3_000:  # 单查询超过 3 秒
        return "考虑 NebulaGraph 或优化 Neo4j"
    if budget < 0:  # 没有预算买企业版
        return "Neo4j 社区版（单机）"
    return "Neo4j 社区版够用"

# 德高项目：5-10 万节点，查询 < 1 秒 -> Neo4j 社区版够用
```

---

## 4.2 向量数据库选型

### 4.2.1 主流向量数据库对比

| 维度 | Milvus | Qdrant | Weaviate | pgvector |
|------|--------|--------|----------|----------|
| 部署方式 | 独立服务 | 独立服务 | 独立服务 | PostgreSQL 插件 |
| 性能 | 高（十亿级） | 高（亿级） | 中（亿级） | 中（百万级） |
| 内存占用 | 高（4GB+） | 低（512MB+） | 中（1GB+） | 依赖 PG |
| 索引算法 | IVF/HNSW/DiskANN | HNSW | HNSW | HNSW/IVFFlat |
| 混合检索 | ✅ 向量+标量 | ✅ 向量+标量 | ✅ 向量+BM25 | ✅ 向量+SQL |
| 运维复杂度 | 高（etcd+minio） | 低（单二进制） | 中 | 低（PG 生态） |
| 适用规模 | 十亿级 | 亿级 | 亿级 | 百万级 |

### 4.2.2 德高项目选型：Milvus Standalone

**选型理由**：

```python
选型决策 = {
    "向量数据库": "Milvus Standalone 2.3+",
    "理由": [
        "文本块规模 10-50 万，Milvus 性能充裕",
        "支持 IVF_FLAT + HNSW 多种索引",
        "LlamaIndex / LangChain 原生支持",
        "支持向量+标量混合检索（过滤 entity_type）",
    ],
    "权衡": [
        "运维复杂度高于 Qdrant（需要 etcd + minio）",
        "内存占用高（4GB），16GB 服务器需要规划",
    ],
    "备选方案": "如果内存紧张，换 Qdrant（单二进制，512MB 起步）"
}
```

### 4.2.3 备选方案：Neo4j 原生向量索引

```python
# 补充.md 修正：Neo4j 5.x 自带向量索引，可以不部署 Milvus
# 适合小规模向量（< 10万）场景

# Neo4j 原生向量索引
CREATE VECTOR INDEX text_embedding_index
FOR (n:TextChunk) ON (n.embedding)
OPTIONS {
    indexConfig: {
        `vector.dimensions`: 1536,
        `vector.similarity_function`: 'cosine'
    }
}

# 在 Neo4j 中直接做向量检索
CALL db.index.vector.queryNodes('text_embedding_index', 5, $query_vector)
YIELD node, score
RETURN node.text_chunk, node.entity_name, score

# 优点：不部署 Milvus，运维简单
# 缺点：向量检索性能不如专业向量库，大规模（>10万）变慢
```

### 4.2.4 Milvus 核心配置

```python
# Milvus Standalone Docker 配置（德高项目）
# Milvus 依赖 etcd（配置存储）+ minio（对象存储）

milvus_config = {
    "milvus-standalone": {
        "image": "milvusdb/milvus:v2.3.3",
        "environment": {
            "ETCD_ENDPOINTS": "milvus-etcd:2379",
            "MINIO_ADDRESS": "milvus-minio:9000",
        },
        "ports": {"19530": "19530"},  # gRPC 端口
        "memory_limit": "4GB",
        "depends_on": ["milvus-etcd", "milvus-minio"]
    },
    "milvus-etcd": {
        "image": "quay.io/coreos/etcd:v3.5.5",
        "environment": {
            "ETCD_AUTO_COMPACTION_MODE": "revision",
            "ETCD_AUTO_COMPACTION_RETENTION": "1000",
        },
        "volumes": {"etcd_data": "/etcd"}
    },
    "milvus-minio": {
        "image": "minio/minio:RELEASE.2023-03-20T20-16-18Z",
        "environment": {
            "MINIO_ACCESS_KEY": "minioadmin",
            "MINIO_SECRET_KEY": "minioadmin",
        },
        "volumes": {"minio_data": "/minio"}
    }
}
```

### 4.2.5 Milvus 集合设计

```python
from pymilvus import CollectionSchema, FieldSchema, DataType

# 文本块向量集合
text_chunk_fields = [
    FieldSchema(name="id", dtype=DataType.INT64, is_primary=True, auto_id=True),
    FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=1536),
    FieldSchema(name="text_chunk", dtype=DataType.VARCHAR, max_length=2000),
    FieldSchema(name="entity_ids", dtype=DataType.VARCHAR, max_length=500),  # 多个 ID 用逗号分隔
    FieldSchema(name="entity_type", dtype=DataType.VARCHAR, max_length=50),
    FieldSchema(name="source_doc", dtype=DataType.VARCHAR, max_length=200),
    FieldSchema(name="chunk_id", dtype=DataType.VARCHAR, max_length=50),
]

text_chunk_schema = CollectionSchema(
    fields=text_chunk_fields,
    description="文本块向量集合"
)

# 社区摘要向量集合
community_fields = [
    FieldSchema(name="id", dtype=DataType.INT64, is_primary=True, auto_id=True),
    FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=1536),
    FieldSchema(name="community_id", dtype=DataType.INT64),
    FieldSchema(name="summary", dtype=DataType.VARCHAR, max_length=1000),
]

community_schema = CollectionSchema(
    fields=community_fields,
    description="社区摘要向量集合（Global Search 用）"
)

# 创建索引
index_params = {
    "index_type": "IVF_FLAT",      # 倒排文件索引（适合中等规模）
    "metric_type": "COSINE",        # 余弦相似度
    "params": {"nlist": 1024}       # 聚类中心数
}
# 备选：HNSW（查询更快但内存更大）
# index_params = {"index_type": "HNSW", "metric_type": "COSINE", "params": {"M": 16, "efConstruction": 256}}
```

---

## 4.3 LLM 服务接入

### 4.3.1 LLM 选型对比

| 维度 | 火山引擎豆包 | OpenAI GPT-4 | 本地部署 vLLM |
|------|------------|-------------|-------------|
| 中文能力 | ✅ 强 | 中 | 取决于模型 |
| Function Calling | ✅ 强 | ✅ 强 | 取决于模型 |
| 成本 | 低（0.001元/千token） | 高（$0.03/千token） | 仅 GPU 成本 |
| 延迟 | 低（国内节点） | 高（海外节点） | 最低（本地） |
| 数据合规 | ✅ 国内合规 | ❌ 数据出境 | ✅ 完全私有 |
| 德高合作 | ✅ 德高与火山有合作 | ❌ | ❌ |

### 4.3.2 德高项目选型：火山引擎豆包 2.0

```python
from volcengine import VolcEngineClient

class DoubaoLLMClient:
    """火山引擎豆包 2.0 LLM 客户端"""
    
    def __init__(self):
        self.client = VolcEngineClient(
            ak=os.getenv("DOUBAO_API_KEY"),
            region="cn-beijing"
        )
        self.model = "doubao-2.0"  # 文本模型
        self.embedding_model = "doubao-embedding"  # Embedding 模型
    
    def chat(self, messages, temperature=0.3, tools=None):
        """对话接口（支持 Function Calling）"""
        response = self.client.chat(
            model=self.model,
            messages=messages,
            temperature=temperature,
            tools=tools  # Agent 工具调用
        )
        return response
    
    def embed(self, text):
        """文本向量化（Embedding）"""
        response = self.client.embedding(
            model=self.embedding_model,
            input=text
        )
        return response.embedding  # 1536 维向量
```

### 4.3.3 双模型分工

```python
# GraphRAG 索引阶段需要两个模型，分工不同

class GraphRAGLLMConfig:
    """LLM 配置：抽取用便宜模型，生成用强模型"""
    
    def __init__(self):
        # 实体关系抽取：用便宜快速的模型（索引阶段大量调用）
        self.extraction_model = "doubao-2.0-lite"  # 轻量版，成本低
        
        # 社区摘要生成：用质量好的模型
        self.summary_model = "doubao-2.0"  # 标准版
        
        # 答案生成：用最强模型
        self.generation_model = "doubao-2.0-pro"  # 专业版
        
        # 文本向量化：用 Embedding 模型
        self.embedding_model = "doubao-embedding"
    
    # 成本优化策略（微软官方建议）：
    # - 索引阶段用轻量模型（doubao-2.0-lite），降低 80% 成本
    # - 生成阶段用强模型（doubao-2.0-pro），保证答案质量
    # - 向量化用专用 Embedding 模型，不用通用对话模型
```

---

## 4.4 Docker Compose 部署方案

### 4.4.1 完整 docker-compose.yml

```yaml
# docker-compose.yml
version: '3.8'

services:
  # ========== 应用层 ==========
  app:
    build: .
    ports:
      - "8000:8000"
    environment:
      - DATABASE_URL=mysql+asyncmy://root:root123@mysql:3306/jcdecaux_ai
      - REDIS_URL=redis://redis:6379
      - NEO4J_URI=bolt://neo4j:7687
      - NEO4J_USER=neo4j
      - NEO4J_PASSWORD=password123
      - MILVUS_HOST=milvus-standalone
      - MILVUS_PORT=19530
      - DOUBAO_API_KEY=${DOUBAO_API_KEY}
      - AMAP_API_KEY=${AMAP_API_KEY}
      - BOCHA_API_KEY=${BOCHA_API_KEY}
    depends_on:
      - mysql
      - redis
      - neo4j
      - milvus-standalone
    deploy:
      resources:
        limits:
          memory: 2G
    restart: unless-stopped

  # ========== 关系数据库 ==========
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
    restart: unless-stopped

  # ========== 缓存 ==========
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    deploy:
      resources:
        limits:
          memory: 512M
    restart: unless-stopped

  # ========== 图数据库 ==========
  neo4j:
    image: neo4j:5-community
    environment:
      NEO4J_AUTH: neo4j/password123
      NEO4J_server_memory_heap_initial__size: 1G
      NEO4J_server_memory_heap_max__size: 2G
      NEO4J_server_memory_pagecache_size: 1G
      NEO4JLABS_PLUGINS: '["apoc"]'
    ports:
      - "7474:7474"  # Web 界面
      - "7687:7687"  # Bolt 协议
    volumes:
      - neo4j_data:/data
      - neo4j_logs:/logs
    deploy:
      resources:
        limits:
          memory: 2G
    restart: unless-stopped

  # ========== 向量数据库 ==========
  milvus-etcd:
    image: quay.io/coreos/etcd:v3.5.5
    environment:
      ETCD_AUTO_COMPACTION_MODE: revision
      ETCD_AUTO_COMPACTION_RETENTION: "1000"
    volumes:
      - etcd_data:/etcd
    restart: unless-stopped

  milvus-minio:
    image: minio/minio:RELEASE.2023-03-20T20-16-18Z
    environment:
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    volumes:
      - minio_data:/minio
    restart: unless-stopped

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
    restart: unless-stopped

volumes:
  mysql_data:
  neo4j_data:
  neo4j_logs:
  milvus_data:
  etcd_data:
  minio_data:
```

### 4.4.2 Dockerfile

```dockerfile
# Dockerfile
FROM python:3.10-slim

WORKDIR /app

# 安装系统依赖
RUN apt-get update && apt-get install -y \
    gcc \
    && rm -rf /var/lib/apt/lists/*

# 安装 Python 依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制代码
COPY . .

# 启动
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

```txt
# requirements.txt
fastapi==0.110.0
uvicorn==0.27.0
neo4j==5.18.0
pymilvus==2.3.7
redis==5.0.1
aiomysql==0.2.0
volcengine==1.0.100
llamaindex==0.10.0
pydantic==2.6.0
httpx==0.27.0
```

---

## 4.5 内存与资源规划

### 4.5.1 16GB 服务器资源分配

```
服务器总内存：16GB
系统 + Docker 引擎预留：2GB
可用分配：14GB

┌─────────────────────────────────────────────┐
│           14GB 资源分配方案                    │
├──────────────┬──────────────────────────────┤
│ Milvus       │ 4GB（含 etcd + minio）       │
│ Neo4j        │ 2GB（heap）+ 1GB（pagecache）│
│ FastAPI App  │ 2GB（Python + LlamaIndex）   │
│ MySQL        │ 1GB                          │
│ Redis        │ 512MB                        │
│ 剩余 buffer  │ 3.5GB                        │
└──────────────┴──────────────────────────────┘
```

### 4.5.2 资源不足时的降级方案

```python
class ResourceOptimizer:
    """资源不足时的降级策略"""
    
    def optimize(self, available_memory_gb):
        if available_memory_gb >= 16:
            return self.full_deployment()      # 全量部署
        elif available_memory_gb >= 8:
            return self.medium_deployment()     # 中等部署
        else:
            return self.minimal_deployment()    # 最小部署
    
    def full_deployment(self):
        """16GB：全量部署（德高项目方案）"""
        return {
            "graph_db": "Neo4j 5.x (2GB)",
            "vector_db": "Milvus Standalone (4GB)",
            "cache": "Redis (512MB)",
            "rdb": "MySQL (1GB)",
            "app": "FastAPI (2GB)"
        }
    
    def medium_deployment(self):
        """8GB：中等部署，Milvus 换 Qdrant"""
        return {
            "graph_db": "Neo4j 5.x (1.5GB)",
            "vector_db": "Qdrant (512MB)",  # 换轻量向量库
            "cache": "Redis (256MB)",
            "rdb": "SQLite (0MB)",          # 换嵌入式数据库
            "app": "FastAPI (1GB)"
        }
    
    def minimal_deployment(self):
        """4GB：最小部署，用 Neo4j 原生向量索引"""
        return {
            "graph_db": "Neo4j 5.x (2GB，含原生向量索引)",
            "vector_db": None,              # 不部署独立向量库
            "cache": "Redis (256MB)",
            "rdb": "SQLite (0MB)",
            "app": "FastAPI (512MB)"
        }
```

### 4.5.3 性能基准

```python
# 德高项目预期性能（4核16G + Docker 单机）
performance_benchmark = {
    "向量检索（Milvus）": {
        "10万向量": "50-100ms",
        "50万向量": "100-200ms"
    },
    "图遍历（Neo4j）": {
        "1跳查询": "50-100ms",
        "2跳查询": "100-300ms",
        "3跳查询": "300-800ms"
    },
    "LLM 生成（豆包2.0）": {
        "简单回答": "1-2s",
        "复杂推理": "3-5s"
    },
    "完整 GraphRAG 查询": {
        "Local Search": "500ms-1s",
        "Global Search": "2-5s",
        "DRIFT Search": "1-2s"
    }
}
```

---

## 4.6 监控与运维

### 4.6.1 健康检查

```python
# 健康检查接口
@app.get("/health")
async def health_check():
    """检查所有组件健康状态"""
    status = {}
    
    # Neo4j
    try:
        with neo4j.session() as session:
            session.run("RETURN 1")
        status["neo4j"] = "healthy"
    except:
        status["neo4j"] = "unhealthy"
    
    # Milvus
    try:
        milvus_client.get_collection_stats("text_chunks")
        status["milvus"] = "healthy"
    except:
        status["milvus"] = "unhealthy"
    
    # Redis
    try:
        redis_client.ping()
        status["redis"] = "healthy"
    except:
        status["redis"] = "unhealthy"
    
    # MySQL
    try:
        mysql_conn.execute("SELECT 1")
        status["mysql"] = "healthy"
    except:
        status["mysql"] = "unhealthy"
    
    return {"status": status}
```

### 4.6.2 关键监控指标

| 组件 | 监控指标 | 告警阈值 |
|------|---------|---------|
| Neo4j | 查询延迟、堆内存使用率 | 延迟 > 1s / 内存 > 80% |
| Milvus | 检索延迟、向量数量 | 延迟 > 200ms |
| Redis | 缓存命中率、内存使用率 | 命中率 < 60% |
| LLM | API 调用延迟、Token 消耗 | 延迟 > 5s / 限流 |
| App | 请求 QPS、错误率 | 错误率 > 1% |

---

## 模块四总结

| 知识点 | 一句话记忆 |
|--------|----------|
| 图数据库选型 | Neo4j 社区版（5-10万节点够用），超千万换 NebulaGraph |
| 向量数据库选型 | Milvus（大规模）/ Qdrant（轻量）/ Neo4j 原生向量（最小部署） |
| LLM 选型 | 豆包 2.0（国内合规+德高合作），抽取用 lite 版降成本 |
| Docker 部署 | MySQL+Redis+Neo4j+Milvus+App，docker-compose 一键启动 |
| 内存规划 | 16GB = Milvus(4G)+Neo4j(3G)+App(2G)+MySQL(1G)+Redis(0.5G)+buffer(3.5G) |
| 降级方案 | 8GB 换 Qdrant+SQLite，4GB 用 Neo4j 原生向量索引 |
