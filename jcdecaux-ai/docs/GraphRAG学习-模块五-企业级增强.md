# 模块五：企业级增强能力

> 📅 2026-07-18
> 核心内容：限流 / 熔断降级 / 重试超时 / 缓存 / 异步任务 / 监控
> 目标：让 GraphRAG 达到生产级可用性

---

## 5.1 限流机制

### 5.1.1 为什么需要限流

```
场景：德高 2000+ 销售同时使用 AI 决策系统
  -> 2000 个请求同时打到豆包 API
  -> 豆包 API 限流（10 QPS）
  -> 大量请求失败
  -> 系统崩溃

解决：限流算法控制请求速率，保护下游服务
```

### 5.1.2 令牌桶算法（推荐）

```python
import time

class TokenBucket:
    """
    令牌桶算法：
    - 桶容量 capacity：最大并发数
    - 补充速率 refill_rate：每秒补充令牌数
    - 请求需要消耗1个令牌
    - 没令牌就排队/拒绝
    """
    
    def __init__(self, capacity: int, refill_rate: float):
        self.capacity = capacity          # 桶容量
        self.refill_rate = refill_rate    # 每秒补充令牌数
        self.tokens = capacity            # 当前令牌数
        self.last_refill = time.time()
    
    def acquire(self, timeout: float = 0) -> bool:
        """获取令牌"""
        now = time.time()
        # 补充令牌
        elapsed = now - self.last_refill
        self.tokens = min(self.capacity, self.tokens + elapsed * self.refill_rate)
        self.last_refill = now
        
        if self.tokens >= 1:
            self.tokens -= 1
            return True
        
        if timeout > 0:
            # 等待令牌
            wait_time = (1 - self.tokens) / self.refill_rate
            if wait_time <= timeout:
                time.sleep(wait_time)
                self.tokens = 0
                return True
        
        return False  # 拒绝请求

# 德高项目配置
llm_rate_limiter = TokenBucket(capacity=10, refill_rate=10)  # 豆包 API 10 QPS
milvus_rate_limiter = TokenBucket(capacity=100, refill_rate=100)  # Milvus 100 QPS
neo4j_rate_limiter = TokenBucket(capacity=50, refill_rate=50)  # Neo4j 50 QPS
```

### 5.1.3 滑动窗口算法（备选）

```python
from collections import deque

class SlidingWindow:
    """
    滑动窗口算法：
    - 统计过去 N 秒内的请求数
    - 超过阈值就拒绝
    - 比令牌桶更精确，但内存占用更高
    """
    
    def __init__(self, window_size: int, max_requests: int):
        self.window_size = window_size      # 窗口大小（秒）
        self.max_requests = max_requests    # 窗口内最大请求数
        self.requests = deque()             # 请求时间戳队列
    
    def allow(self) -> bool:
        now = time.time()
        # 清理过期请求
        while self.requests and self.requests[0] < now - self.window_size:
            self.requests.popleft()
        
        if len(self.requests) < self.max_requests:
            self.requests.append(now)
            return True
        return False

# 配置：每秒最多10个请求
sliding_window = SlidingWindow(window_size=1, max_requests=10)
```

### 5.1.4 德高项目限流配置

```python
RATE_LIMIT_CONFIG = {
    "doubao_llm": {
        "algorithm": "token_bucket",
        "capacity": 10,        # 桶容量
        "refill_rate": 10,     # 10 QPS
        "reason": "豆包 API 限制 10 QPS"
    },
    "milvus": {
        "algorithm": "token_bucket",
        "capacity": 100,
        "refill_rate": 100,
        "reason": "Milvus 支持高并发"
    },
    "neo4j": {
        "algorithm": "token_bucket",
        "capacity": 50,
        "refill_rate": 50,
        "reason": "Neo4j 社区版连接数有限"
    },
    "mcp_amap": {
        "algorithm": "sliding_window",
        "window_size": 1,
        "max_requests": 5,     # 高德 API 5 QPS
        "reason": "高德免费版限制"
    }
}
```

---

## 5.2 熔断与降级

### 5.2.1 为什么需要熔断

```
场景：Neo4j 图数据库突然变慢（查询3秒+）
  -> GraphRAG 查询超时
  -> 用户等待
  -> 请求堆积
  -> 系统雪崩

解决：熔断器监控失败率，达到阈值后"熔断"（直接拒绝请求）
     降级到备用方案（纯向量 RAG），保证系统可用
```

### 5.2.2 Circuit Breaker 三态机

```python
from enum import Enum
import time

class CircuitState(Enum):
    CLOSED = "closed"          # 正常：允许请求通过
    OPEN = "open"              # 熔断：拒绝请求，走降级
    HALF_OPEN = "half_open"    # 半开：试探性放行一个请求

class CircuitBreaker:
    """
    熔断器三态机：
    CLOSED --失败率超阈值--> OPEN --超时后--> HALF_OPEN --成功--> CLOSED
                                              --失败--> OPEN
    """
    
    def __init__(self, failure_threshold=5, recovery_timeout=60, success_threshold=3):
        self.failure_threshold = failure_threshold    # 连续失败5次熔断
        self.recovery_timeout = recovery_timeout      # 60秒后尝试恢复
        self.success_threshold = success_threshold    # 半开状态连续成功3次恢复
        self.failure_count = 0
        self.success_count = 0
        self.state = CircuitState.CLOSED
        self.last_failure_time = None
        self.fallback_func = None
    
    def call(self, func, *args, **kwargs):
        """带熔断保护的调用"""
        # 状态判断
        if self.state == CircuitState.OPEN:
            if time.time() - self.last_failure_time > self.recovery_timeout:
                print("熔断器半开，试探恢复...")
                self.state = CircuitState.HALF_OPEN
            else:
                # 熔断中，直接降级
                return self._fallback(*args, **kwargs)
        
        try:
            result = func(*args, **kwargs)
            self._on_success()
            return result
        except Exception as e:
            self._on_failure()
            return self._fallback(*args, **kwargs)
    
    def _on_success(self):
        if self.state == CircuitState.HALF_OPEN:
            self.success_count += 1
            if self.success_count >= self.success_threshold:
                print("熔断器恢复，回到正常状态")
                self.state = CircuitState.CLOSED
                self.failure_count = 0
        else:
            self.failure_count = 0
    
    def _on_failure(self):
        self.failure_count += 1
        self.last_failure_time = time.time()
        
        if self.state == CircuitState.HALF_OPEN:
            # 半开状态失败，重新熔断
            self.state = CircuitState.OPEN
            self.success_count = 0
        elif self.failure_count >= self.failure_threshold:
            # 连续失败超阈值，熔断
            self.state = CircuitState.OPEN
            print(f"熔断器开启，降级到备用方案")
    
    def _fallback(self, *args, **kwargs):
        """降级策略"""
        if self.fallback_func:
            return self.fallback_func(*args, **kwargs)
        return {"error": "服务降级", "fallback": True}
```

### 5.2.3 德高项目的降级策略

```python
class GraphRAGCircuitBreaker:
    """德高 GraphRAG 熔断降级"""
    
    def __init__(self):
        # GraphRAG 熔断器
        self.graphrag_cb = CircuitBreaker(
            failure_threshold=5,     # 连续失败5次
            recovery_timeout=60,     # 60秒后试探
            success_threshold=3      # 半开连续成功3次恢复
        )
        # 降级到纯向量 RAG
        self.graphrag_cb.fallback_func = self._vector_rag_fallback
        
        # LLM 熔断器
        self.llm_cb = CircuitBreaker(
            failure_threshold=10,    # LLM 失败容忍度高一些
            recovery_timeout=30,
            success_threshold=2
        )
        self.llm_cb.fallback_func = self._cache_fallback
    
    def query(self, user_query: str) -> dict:
        """带熔断保护的 GraphRAG 查询"""
        return self.graphrag_cb.call(self._graphrag_search, user_query)
    
    def _graphrag_search(self, query: str) -> dict:
        """正常 GraphRAG 检索（Neo4j + Milvus）"""
        vector_results = milvus.search(embed(query), top_k=5)
        graph_results = neo4j.traverse(extract_entities(query), hops=2)
        return llm.generate(query, merge(vector_results, graph_results))
    
    def _vector_rag_fallback(self, query: str) -> dict:
        """一级降级：纯向量 RAG（不查图）"""
        print("降级到纯向量 RAG")
        vector_results = milvus.search(embed(query), top_k=10)
        return llm.generate(query, vector_results)
    
    def _cache_fallback(self, query: str) -> dict:
        """二级降级：缓存返回（不调 LLM）"""
        print("降级到缓存返回")
        cached = semantic_cache.get(query)
        if cached:
            return {"answer": cached, "source": "cache"}
        return {"error": "服务暂不可用", "fallback": True}
```

### 5.2.4 三级降级策略

```
正常：GraphRAG（Neo4j图遍历 + Milvus向量 + LLM生成）
  ↓ Neo4j 故障
一级降级：纯向量 RAG（只查 Milvus + LLM生成）
  ↓ LLM 故障
二级降级：缓存返回（语义缓存匹配相似问题）
  ↓ 缓存未命中
三级降级：友好提示（"服务暂不可用，请稍后重试"）
```

---

## 5.3 重试与超时策略

### 5.3.1 指数退避重试

```python
import asyncio
import random

class RetryPolicy:
    """指数退避重试策略"""
    
    def __init__(self, max_retries=3, base_delay=1.0, max_delay=30.0):
        self.max_retries = max_retries    # 最大重试次数
        self.base_delay = base_delay      # 基础延迟
        self.max_delay = max_delay        # 最大延迟
    
    async def call_with_retry(self, func, *args, **kwargs):
        """带重试的异步调用"""
        last_exception = None
        
        for attempt in range(self.max_retries + 1):
            try:
                return await func(*args, **kwargs)
            except Exception as e:
                last_exception = e
                if attempt == self.max_retries:
                    print(f"重试{self.max_retries}次后仍失败: {e}")
                    raise last_exception
                
                # 指数退避 + 抖动
                delay = min(
                    self.base_delay * (2 ** attempt) + random.uniform(0, 1),
                    self.max_delay
                )
                print(f"第{attempt+1}次失败，{delay:.1f}秒后重试: {e}")
                await asyncio.sleep(delay)
```

### 5.3.2 超时分级策略

```python
# 德高项目超时配置
TIMEOUT_CONFIG = {
    "vector_search": {
        "timeout": 2.0,      # Milvus 向量检索 2秒
        "reason": "向量检索应该很快，2秒超时说明有问题"
    },
    "graph_traverse": {
        "timeout": 5.0,      # Neo4j 图遍历 5秒
        "reason": "图遍历可能多跳，给5秒"
    },
    "llm_generation": {
        "timeout": 30.0,     # LLM 生成 30秒
        "reason": "LLM 生成可能较慢，给30秒"
    },
    "mcp_amap": {
        "timeout": 3.0,      # 高德 API 3秒
        "reason": "高德 API 应该秒级返回"
    },
    "mcp_bocha": {
        "timeout": 5.0,      # 博查 API 5秒
        "reason": "博查查询新闻可能慢一些"
    },
    "video_generation": {
        "timeout": 300.0,    # 视频生成 5分钟
        "reason": "视频生成是长耗时任务"
    }
}

# 带超时的调用
async def call_with_timeout(func, timeout, *args):
    try:
        return await asyncio.wait_for(func(*args), timeout=timeout)
    except asyncio.TimeoutError:
        raise Exception(f"{func.__name__} 超时（{timeout}秒）")
```

### 5.3.3 德高项目重试配置

```python
RETRY_CONFIG = {
    "doubao_llm": {
        "max_retries": 3,
        "base_delay": 1.0,
        "max_delay": 30.0,
        "timeout": 30.0,
        "reason": "豆包 API 偶尔限流，重试可恢复"
    },
    "neo4j": {
        "max_retries": 2,
        "base_delay": 0.5,
        "max_delay": 5.0,
        "timeout": 5.0,
        "reason": "Neo4j 偶尔慢查询，重试2次"
    },
    "milvus": {
        "max_retries": 2,
        "base_delay": 0.3,
        "max_delay": 3.0,
        "timeout": 2.0,
        "reason": "Milvus 比较稳定，重试2次"
    },
    "mcp_amap": {
        "max_retries": 3,
        "base_delay": 1.0,
        "max_delay": 10.0,
        "timeout": 3.0,
        "reason": "高德 API 偶尔超时，重试3次"
    }
}
```

---

## 5.4 缓存策略

### 5.4.1 三级缓存架构

```python
class ThreeLevelCache:
    """
    三级缓存：
    L1: Redis 精确缓存（query 完全匹配）
    L2: Milvus 语义缓存（query 语义相似度>0.95）
    L3: 社区摘要预加载（Redis 缓存社区摘要）
    """
    
    def __init__(self, redis, milvus, embedder):
        self.redis = redis
        self.milvus = milvus
        self.embedder = embedder
    
    def get(self, query: str) -> str:
        """三级缓存查询"""
        # L1: 精确缓存（最快，<1ms）
        cache_key = f"query:{hashlib.md5(query.encode()).hexdigest()}"
        cached = self.redis.get(cache_key)
        if cached:
            return cached  # 命中 L1
        
        # L2: 语义缓存（Milvus 向量相似度，<50ms）
        query_vector = self.embedder.embed(query)
        results = self.milvus.search(
            collection="query_cache",
            data=[query_vector],
            top_k=1,
            output_fields=["query", "answer"]
        )
        if results and results[0].score >= 0.95:
            # 语义命中，写入 L1 精确缓存
            self.redis.setex(cache_key, 3600, results[0].answer)
            return results[0].answer  # 命中 L2
        
        return None  # 缓存未命中
    
    def set(self, query: str, answer: str):
        """写缓存：同时写 L1 和 L2"""
        # 写 L1: Redis 精确缓存（1小时过期）
        cache_key = f"query:{hashlib.md5(query.encode()).hexdigest()}"
        self.redis.setex(cache_key, 3600, answer)
        
        # 写 L2: Milvus 语义缓存
        query_vector = self.embedder.embed(query)
        self.milvus.insert(
            collection="query_cache",
            data=[{
                "embedding": query_vector,
                "query": query,
                "answer": answer,
                "timestamp": time.time()
            }]
        )
    
    def get_community_summary(self, community_id: int) -> str:
        """L3: 社区摘要预加载（从 Redis 读）"""
        return self.redis.get(f"community:{community_id}")
```

### 5.4.2 缓存命中率优化

```python
class CacheOptimizer:
    """缓存命中率优化策略"""
    
    # 缓存策略配置
    CACHE_STRATEGY = {
        "exact_match_ttl": 3600,          # 精确缓存1小时
        "semantic_similarity_threshold": 0.95,  # 语义相似度阈值
        "community_summary_ttl": 86400,    # 社区摘要24小时
        "hot_query_ttl": 7200,            # 热门查询2小时（延长）
        "max_cache_size": 10000           # 最多缓存1万条
    }
    
    def should_cache(self, query: str, answer: str) -> bool:
        """判断是否值得缓存"""
        # 不缓存的情况
        if len(answer) < 50:
            return False  # 答案太短，可能是错误
        if "error" in answer.lower():
            return False  # 错误答案不缓存
        if "我不确定" in answer:
            return False  # 不确定的答案不缓存
        
        # 热门查询延长缓存时间
        if self._is_hot_query(query):
            return True  # 热门查询一定缓存
        
        return True
    
    def _is_hot_query(self, query: str) -> bool:
        """判断是否热门查询（过去1小时出现>3次）"""
        count = redis.zincrby("hot_queries", 1, query)
        return count > 3
```

---

## 5.5 异步任务管理

### 5.5.1 长耗时任务架构

```python
import asyncio
from datetime import datetime
from enum import Enum

class TaskStatus(Enum):
    PENDING = "PENDING"         # 等待中
    PROCESSING = "PROCESSING"   # 处理中
    COMPLETED = "COMPLETED"     # 已完成
    FAILED = "FAILED"           # 失败
    RETRYING = "RETRYING"       # 重试中

class AsyncTaskManager:
    """异步任务管理：视频生成、批量索引等长耗时任务"""
    
    def __init__(self, mysql, redis):
        self.mysql = mysql
        self.redis = redis
    
    async def submit_video_task(self, brand: str, city: str, style: str) -> dict:
        """提交视频生成任务（立即返回 task_id，后台异步执行）"""
        task_id = generate_uuid()
        
        # 创建任务记录
        self.mysql.execute(
            "INSERT INTO video_task (task_id, brand, city, style, status, created_at) "
            "VALUES (%s, %s, %s, %s, %s, %s)",
            (task_id, brand, city, style, TaskStatus.PENDING.value, datetime.now())
        )
        
        # 异步执行（不阻塞当前请求）
        asyncio.create_task(self._execute_video_task(task_id, brand, city, style))
        
        return {"task_id": task_id, "status": TaskStatus.PENDING.value}
    
    async def _execute_video_task(self, task_id, brand, city, style):
        """后台执行视频生成"""
        try:
            # 更新状态为 PROCESSING
            self._update_status(task_id, TaskStatus.PROCESSING)
            
            # 调用即梦 API 生成视频（2-5分钟）
            result = await jimeng_api.generate_video(brand, city, style)
            
            # 更新状态为 COMPLETED
            self._update_status(task_id, TaskStatus.COMPLETED, 
                              video_url=result.url)
            
            # Redis 缓存视频 URL（7天过期）
            self.redis.setex(f"video:{task_id}", 7*86400, result.url)
            
        except Exception as e:
            # 失败重试（最多3次）
            await self._retry_task(task_id, brand, city, style, str(e))
    
    async def _retry_task(self, task_id, brand, city, style, error):
        """失败重试（指数退避）"""
        max_retries = 3
        
        for attempt in range(max_retries):
            self._update_status(task_id, TaskStatus.RETRYING, 
                              error=f"重试第{attempt+1}次: {error}")
            
            # 指数退避
            delay = 2 ** attempt
            await asyncio.sleep(delay)
            
            try:
                result = await jimeng_api.generate_video(brand, city, style)
                self._update_status(task_id, TaskStatus.COMPLETED, 
                                  video_url=result.url)
                self.redis.setex(f"video:{task_id}", 7*86400, result.url)
                return
            except Exception as e:
                error = str(e)
                continue
        
        # 重试耗尽，标记失败
        self._update_status(task_id, TaskStatus.FAILED, 
                          error=f"重试{max_retries}次后仍失败: {error}")
    
    def get_task_status(self, task_id: str) -> dict:
        """查询任务状态（前端轮询）"""
        # 优先查 Redis（快）
        cached = self.redis.get(f"task_status:{task_id}")
        if cached:
            return json.loads(cached)
        
        # Redis 没有，查 MySQL
        result = self.mysql.query(
            "SELECT * FROM video_task WHERE task_id = %s",
            (task_id,)
        )
        return result
    
    def _update_status(self, task_id, status, **extra):
        """更新任务状态（同时写 MySQL 和 Redis）"""
        # 写 MySQL
        updates = {"status": status.value}
        updates.update(extra)
        self.mysql.update("video_task", task_id, updates)
        
        # 写 Redis（前端轮询用，1小时过期）
        self.redis.setex(
            f"task_status:{task_id}",
            3600,
            json.dumps(updates)
        )
```

### 5.5.2 前端轮询流程

```
用户点击"生成视频"
  ↓
前端 POST /api/video/generate
  ↓
后端返回 {task_id: "xxx", status: "PENDING"}
  ↓
前端每3秒轮询 GET /api/video/status/{task_id}
  ↓
后端返回 {status: "PROCESSING", progress: 30%}
  ↓
... 重复轮询 ...
  ↓
后端返回 {status: "COMPLETED", video_url: "https://..."}
  ↓
前端展示视频
```

---

## 5.6 监控与告警

### 5.6.1 监控架构

```python
from prometheus_client import Counter, Histogram, Gauge

# ========== Prometheus 指标定义 ==========

# 请求计数器
REQUEST_COUNT = Counter(
    'graphrag_requests_total',
    'Total GraphRAG requests',
    ['intent', 'status']  # 标签：意图类型、状态
)

# 请求延迟直方图
REQUEST_LATENCY = Histogram(
    'graphrag_request_latency_seconds',
    'GraphRAG request latency',
    ['intent', 'stage'],  # 标签：意图、阶段（检索/生成）
    buckets=[0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0]
)

# LLM Token 消耗
LLM_TOKEN_USAGE = Counter(
    'graphrag_llm_tokens_total',
    'LLM token usage',
    ['model', 'type']  # 标签：模型名、类型（extraction/generation）
)

# 缓存命中率
CACHE_HIT_RATE = Gauge(
    'graphrag_cache_hit_rate',
    'Cache hit rate',
    ['level']  # 标签：L1精确/L2语义
)

# 活跃任务数
ACTIVE_TASKS = Gauge(
    'graphrag_active_tasks',
    'Active async tasks'
)
```

### 5.6.2 全链路监控

```python
class GraphRAGMonitor:
    """全链路监控"""
    
    def monitor_query(self, func):
        """装饰器：监控 GraphRAG 查询"""
        async def wrapper(query, *args, **kwargs):
            start_time = time.time()
            intent = "unknown"
            status = "success"
            
            try:
                # 监控查询理解阶段
                with REQUEST_LATENCY.labels(intent="unknown", stage="understanding").time():
                    result = await func(query, *args, **kwargs)
                    intent = result.get("intent", "unknown")
                
                # 记录成功
                REQUEST_COUNT.labels(intent=intent, status="success").inc()
                return result
                
            except Exception as e:
                status = "error"
                REQUEST_COUNT.labels(intent=intent, status="error").inc()
                raise
            finally:
                # 记录总延迟
                total_time = time.time() - start_time
                REQUEST_LATENCY.labels(intent=intent, stage="total").observe(total_time)
        
        return wrapper
    
    def monitor_llm_call(self, model, token_type):
        """监控 LLM 调用"""
        def decorator(func):
            async def wrapper(*args, **kwargs):
                start = time.time()
                result = await func(*args, **kwargs)
                
                # 记录 Token 消耗
                LLM_TOKEN_USAGE.labels(
                    model=model, 
                    type=token_type
                ).inc(result.usage.total_tokens)
                
                return result
            return wrapper
        return decorator
```

### 5.6.3 告警规则

```python
ALERT_RULES = {
    "high_error_rate": {
        "condition": "rate(graphrag_requests_total{status='error'}[5m]) > 0.1",
        "threshold": "错误率 > 10%",
        "action": "发送钉钉告警",
        "severity": "critical"
    },
    "high_latency": {
        "condition": "histogram_quantile(0.95, graphrag_request_latency_seconds) > 5",
        "threshold": "P95 延迟 > 5秒",
        "action": "发送钉钉告警",
        "severity": "warning"
    },
    "llm_rate_limit": {
        "condition": "rate(graphrag_llm_tokens_total[1m]) > 50000",
        "threshold": "Token 消耗 > 5万/分钟",
        "action": "触发限流",
        "severity": "warning"
    },
    "cache_hit_rate_low": {
        "condition": "graphrag_cache_hit_rate < 0.3",
        "threshold": "缓存命中率 < 30%",
        "action": "检查缓存策略",
        "severity": "info"
    },
    "circuit_breaker_open": {
        "condition": "graphrag_circuit_breaker_state == 1",
        "threshold": "熔断器开启",
        "action": "立即告警 + 自动降级",
        "severity": "critical"
    }
}
```

### 5.6.4 Grafana 仪表盘

```
GraphRAG 监控仪表盘：
┌─────────────────────────────────────────────────────┐
│  📊 请求概览                                         │
│  ┌──────────┬──────────┬──────────┬──────────┐     │
│  │ 总请求数  │ 成功率   │ P95延迟  │ QPS      │     │
│  │ 12,345   │ 98.5%   │ 1.2s    │ 15       │     │
│  └──────────┴──────────┴──────────┴──────────┘     │
├─────────────────────────────────────────────────────┤
│  📈 意图分布                                         │
│  fact: 45% | semantic: 30% | reasoning: 15%        │
│  global: 7% | drift: 3%                             │
├─────────────────────────────────────────────────────┤
│  💰 LLM Token 消耗                                   │
│  今日：1,234,567 tokens (¥1.23)                     │
│  本月：45,678,901 tokens (¥45.68)                   │
├─────────────────────────────────────────────────────┤
│  🔧 组件健康状态                                     │
│  Neo4j: ✅ 健康 | Milvus: ✅ 健康 | 豆包: ✅ 健康   │
│  Redis: ✅ 健康  | MySQL: ✅ 健康                    │
├─────────────────────────────────────────────────────┤
│  ⚡ 缓存命中率                                       │
│  L1精确: 35% | L2语义: 28% | 总命中: 63%            │
└─────────────────────────────────────────────────────┘
```

---

## 5.7 企业级增强能力总结

| 增强能力 | 方案 | 德高项目应用 | 面试要点 |
|---------|------|------------|---------|
| **限流** | 令牌桶 | 豆包 API 10 QPS | 保护下游不被打爆 |
| **熔断降级** | Circuit Breaker 三态机 | GraphRAG挂了->纯向量RAG->缓存 | 防止雪崩 |
| **重试超时** | 指数退避 + 超时分级 | LLM 30s / 图遍历 5s / 向量 2s | 临时故障自恢复 |
| **缓存** | 三级缓存 | L1精确+L2语义+L3社区摘要 | 省Token、降延迟 |
| **异步任务** | asyncio + MySQL | 视频生成不阻塞主请求 | 长耗时任务管理 |
| **监控告警** | Prometheus + Grafana | 延迟/错误率/Token消耗 | 全链路可观测 |
