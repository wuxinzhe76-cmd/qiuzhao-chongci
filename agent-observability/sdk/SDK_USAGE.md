# Agent Insight SDK 使用指南

## 概述

Agent Insight SDK 是一个轻量级的 Python 探针库，用于自动采集 AI Agent 的调用链路和性能指标。SDK 采用非侵入式设计，支持多厂商 LLM 统一拦截（OpenAI / Anthropic / DeepSeek / vLLM / Ollama 等），覆盖同步和异步场景。

## 核心架构

```
┌─────────────────────────────────────────────────────────┐
│                    你的 Agent 应用                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │  LLM Client (OpenAI / Anthropic / DeepSeek ...)  │  │
│  └────────────┬─────────────────────────────────────┘  │
│               │                                         │
│  ┌────────────▼─────────────────────────────────────┐  │
│  │  LLMInterceptor (多厂商统一拦截)                  │  │
│  │  - 自动识别 Provider (BaseProviderAdapter)        │  │
│  │  - 捕获调用参数 (model, stream, tokens)          │  │
│  │  - 记录时间戳 (start_time, end_time)             │  │
│  │  - 监控流式响应 (prefill_ms, decode_ms)          │  │
│  └────────────┬─────────────────────────────────────┘  │
│               │                                         │
│  ┌────────────▼─────────────────────────────────────┐  │
│  │  ToolSDK (Tool 调用自动埋点)                      │  │
│  │  - @instrument 装饰器                             │  │
│  │  - 自动记录输入/输出/异常/耗时                     │  │
│  └────────────┬─────────────────────────────────────┘  │
│               │                                         │
│  ┌────────────▼─────────────────────────────────────┐  │
│  │  AsyncBatchUploader (异步批量上报)                │  │
│  │  - asyncio.Queue 有界队列 (背压保护)              │  │
│  │  - 每 500ms 或满 20 条触发上报                   │  │
│  │  - 指数退避重试 (最多 3 次)                       │  │
│  │  - httpx 异步 POST 到后端                        │  │
│  └────────────┬─────────────────────────────────────┘  │
└───────────────┼─────────────────────────────────────────┘
                │
                ▼
        FastAPI Backend → Kafka → ClickHouse
```

## 安装

```bash
cd sdk
pip install -e .
```

依赖：`httpx`、`openai`、`anthropic`

## 快速开始

### 1. 基础用法（LLM 拦截）

```python
import asyncio
from openai import OpenAI
from agent_insight_sdk import AsyncBatchUploader, LLMInterceptor

async def main():
    # 1. 初始化上报器
    uploader = AsyncBatchUploader(
        backend_url="http://localhost:8000",
        batch_size=20,        # 每 20 条触发上报
        flush_interval=0.5,   # 每 500ms 触发上报
    )
    await uploader.start()

    # 2. 创建 LLM 客户端并拦截
    client = OpenAI(api_key="your-api-key")
    interceptor = LLMInterceptor(uploader)
    wrapped_client = interceptor.wrap(client)  # 自动识别 Provider

    # 3. 正常调用（接口不变，自动采集）
    response = wrapped_client.chat.completions.create(
        model="gpt-5.4",
        messages=[{"role": "user", "content": "Hello"}],
    )
    print(response.choices[0].message.content)

    # 4. 流式调用（自动采集 prefill/decode 时间）
    stream = wrapped_client.chat.completions.create(
        model="gpt-5.4-nano",
        messages=[{"role": "user", "content": "Tell me a story"}],
        stream=True,
    )
    for chunk in stream:
        print(chunk.choices[0].delta.content, end="")

    # 5. 清理
    await asyncio.sleep(1)  # 等待上报完成
    interceptor.unwrap()
    await uploader.stop()

asyncio.run(main())
```

### 2. 异步场景（推荐）

```python
import asyncio
from openai import AsyncOpenAI
from agent_insight_sdk import AsyncBatchUploader, LLMInterceptor

async def main():
    uploader = AsyncBatchUploader(backend_url="http://localhost:8000")
    await uploader.start()

    client = AsyncOpenAI(api_key="your-api-key")
    interceptor = LLMInterceptor(uploader)
    wrapped_client = interceptor.wrap(client)

    # 并发调用多个 LLM
    tasks = [
        wrapped_client.chat.completions.create(
            model="gpt-5.4",
            messages=[{"role": "user", "content": f"Question {i}"}],
        )
        for i in range(5)
    ]
    responses = await asyncio.gather(*tasks)

    await asyncio.sleep(1)
    interceptor.unwrap()
    await uploader.stop()

asyncio.run(main())
```

### 3. 多厂商 LLM 支持

`LLMInterceptor` 自动识别客户端类型，无需手动指定 Provider：

```python
from agent_insight_sdk import LLMInterceptor

# OpenAI
from openai import OpenAI
openai_client = OpenAI(api_key="sk-xxx")
wrapped = LLMInterceptor(uploader).wrap(openai_client)

# Anthropic
from anthropic import Anthropic
anthropic_client = Anthropic(api_key="sk-ant-xxx")
wrapped = LLMInterceptor(uploader).wrap(anthropic_client)

# OpenAI 兼容接口（DeepSeek / vLLM / Ollama / Groq / Together 等）
from openai import OpenAI
deepseek_client = OpenAI(
    api_key="your-key",
    base_url="https://api.deepseek.com/v1",
)
wrapped = LLMInterceptor(uploader).wrap(deepseek_client)
```

### 4. Agent 工具调用场景（ToolSDK）

使用 `ToolSDK` 装饰器自动埋点 Tool 调用，无需手动构建 SpanData：

```python
import asyncio
from agent_insight_sdk import (
    AsyncBatchUploader,
    LLMInterceptor,
    ToolSDK,
    TraceContext,
    set_current_context,
)

async def main():
    uploader = AsyncBatchUploader(backend_url="http://localhost:8000")
    await uploader.start()

    # 创建根上下文
    root_ctx = TraceContext(name="agent_task")
    set_current_context(root_ctx)

    tool_sdk = ToolSDK(uploader)

    # 用装饰器自动埋点
    @tool_sdk.instrument(name="vector_search", tool_type="retrieval")
    async def vector_search(query: str) -> list:
        await asyncio.sleep(0.12)
        return [{"id": 1, "text": "result"}]

    @tool_sdk.instrument(name="code_executor", tool_type="execution")
    async def code_executor(code: str) -> str:
        await asyncio.sleep(0.35)
        return "output"

    @tool_sdk.instrument(name="result_aggregator", tool_type="aggregation")
    async def result_aggregator(results: list) -> str:
        await asyncio.sleep(0.08)
        return "final answer"

    # 模拟 Agent 执行流程
    search_results = await vector_search("machine learning")
    code_output = await code_executor("print('hello')")
    final = await result_aggregator([search_results, code_output])

    await asyncio.sleep(1)
    await uploader.stop()

asyncio.run(main())
```

### 5. 显式 Trace API

使用 `TraceAPI` 手动控制 Trace 生命周期（类似 OpenTelemetry）：

```python
import asyncio
from agent_insight_sdk import AsyncBatchUploader, TraceAPI

async def main():
    uploader = AsyncBatchUploader(backend_url="http://localhost:8000")
    await uploader.start()

    api = TraceAPI(uploader)

    # 开始 Trace
    api.start_trace("user_query_123")

    # 开始 Span
    api.start_span("vector_search", attributes={"query": "machine learning"})
    await asyncio.sleep(0.12)
    api.end_span(attributes={"results_count": 10, "status": "success"})

    # 嵌套 Span
    api.start_span("llm_call", attributes={"model": "gpt-5.4"})
    await asyncio.sleep(0.5)
    api.end_span(attributes={"tokens": 150})

    # 结束 Trace
    api.end_trace(attributes={"status": "completed"})

    await asyncio.sleep(1)
    await uploader.stop()

asyncio.run(main())
```

## 核心模块说明

### 1. TraceContext（上下文管理）

基于 Python `contextvars` 实现，保证异步环境下的上下文隔离。

```python
from agent_insight_sdk import TraceContext, get_current_context, set_current_context, clear_current_context

# 创建根上下文
root_ctx = TraceContext(name="my_agent")
set_current_context(root_ctx)

# 创建子上下文（自动继承 trace_id）
child_ctx = root_ctx.create_child("tool_call")
set_current_context(child_ctx)

# 获取当前上下文
current = get_current_context()
print(f"trace_id: {current.trace_id}")
print(f"span_id: {current.span_id}")
print(f"parent_span_id: {current.parent_span_id}")

# 清除上下文
clear_current_context()
```

**关键特性**：
- `trace_id`：整个链路的唯一标识，所有子 span 共享
- `span_id`：当前 span 的唯一标识
- `parent_span_id`：父 span 的标识，用于构建调用树

### 2. LLMInterceptor（多厂商 LLM 拦截器）

通过 Provider Adapter 模式统一拦截多个 LLM 厂商的客户端调用。

```python
from agent_insight_sdk import LLMInterceptor

# 自动识别 Provider 并拦截
interceptor = LLMInterceptor(uploader)
wrapped_client = interceptor.wrap(client)

# 恢复原始客户端
interceptor.unwrap()
```

**自动识别的 Provider**：
- OpenAI 官方 SDK (`openai`)
- Anthropic 官方 SDK (`anthropic`)
- 任何 OpenAI 兼容接口（vLLM / DeepSeek / Ollama / Groq / Together 等）

**自动采集的数据**：
- 模型名称（model）、Provider 名称
- 是否流式（stream）
- 输入/输出 token 数
- 调用耗时（latency_ms）
- 流式场景：prefill_ms、decode_ms、TPS
- Prompt 日志（用户最后一条消息 + 模型回复摘要）

**扩展新 Provider**：继承 `BaseProviderAdapter` 即可接入新厂商。

```python
from agent_insight_sdk import BaseProviderAdapter, register_adapter

class MyProviderAdapter(BaseProviderAdapter):
    provider_name = "my_provider"

    def supports(self, client) -> bool:
        return type(client).__name__ == "MyProviderClient"

    def _wrap_call(self, client, interceptor):
        # 实现拦截逻辑
        ...

    def _unwrap_client(self, wrapped):
        # 恢复原始方法
        ...

register_adapter(MyProviderAdapter())
```

### 3. StreamMonitor（流式监控）

专门用于监控流式响应的性能指标。

**计算公式**：
```
prefill_ms = first_chunk_time - start_time
decode_ms = last_chunk_time - first_chunk_time
TPS = output_tokens / (decode_ms / 1000)
```

**工作原理**：
1. 记录请求开始时间（`record_start()`）
2. 收到第一个 chunk 时计算 prefill_ms（`record_first_chunk()`）
3. 持续记录每个 chunk 的时间（`record_chunk(chunk)`）
4. 流结束时通过 `get_metrics()` 获取 `StreamMetrics`

```python
from agent_insight_sdk import StreamMonitor, MonitoredStream

monitor = StreamMonitor()
monitor.record_start()

# 包装流式响应
monitored = MonitoredStream(raw_stream, monitor)
for chunk in monitored:
    print(chunk)

# 获取指标
metrics = monitor.get_metrics()
print(f"prefill: {metrics.prefill_ms}ms, decode: {metrics.decode_ms}ms, TPS: {metrics.tps}")
```

### 4. ToolSDK（Tool 调用自动埋点）

通过装饰器自动记录 Tool 调用的输入、输出、异常和耗时。

```python
from agent_insight_sdk import ToolSDK

tool_sdk = ToolSDK(uploader)

# 同步 Tool
@tool_sdk.instrument(name="calculator", tool_type="calculator")
def calculator(expression: str) -> float:
    return eval(expression)

# 异步 Tool
@tool_sdk.instrument(name="weather_query", tool_type="api")
async def weather_query(city: str) -> dict:
    return await fetch_weather(city)
```

**自动记录**：
- Tool 名称和类型
- 输入参数（JSON 序列化）
- 输出结果（JSON 序列化）
- 耗时（duration_ms）
- 异常信息（如有）
- 自动创建子 span 并关联到当前 trace

### 5. TraceAPI（显式 Trace 控制）

提供类似 OpenTelemetry 的显式 API，适合需要精细控制 Trace 生命周期的场景。

```python
from agent_insight_sdk import TraceAPI

api = TraceAPI(uploader)

# 开始 Trace（创建根 span）
ctx = api.start_trace("my_task")

# 开始子 Span
span_ctx = api.start_span("step_1", attributes={"key": "value"})
# ... 执行操作 ...
api.end_span(span_ctx, attributes={"result": "ok"})

# 结束 Trace
api.end_trace(attributes={"status": "completed"})
```

| 方法 | 说明 |
|------|------|
| `start_trace(name, trace_id="")` | 开始新 Trace，返回 TraceContext |
| `start_span(name, attributes=None)` | 开始子 Span（自动取当前 context 为父） |
| `end_span(ctx=None, attributes=None, span_type="trace")` | 结束 Span 并上报 |
| `end_trace(attributes=None)` | 结束根 Span 并清除上下文 |

### 6. AsyncBatchUploader（异步批量上报）

使用 `asyncio.Queue` 有界队列和后台任务实现高效上报，带背压保护和指数退避重试。

```python
uploader = AsyncBatchUploader(
    backend_url="http://localhost:8000",
    batch_size=20,        # 批量阈值
    flush_interval=0.5,   # 时间阈值（秒）
)

# 启动后台上报任务
await uploader.start()

# 提交数据
await uploader.submit(span_data)

# 查看统计
print(uploader.stats)  # {"queue_size": 5, "sent": 100, "failed": 0, "dropped": 0}

# 停止并刷新剩余数据
await uploader.stop()
```

**上报策略**：
- 每 500ms 检查一次队列
- 队列满 20 条时立即上报
- 调用 `stop()` 时刷新所有剩余数据
- 有界队列容量 10000，超出时丢弃并告警（背压保护）
- 失败时指数退避重试（1s → 2s → 4s，最多 3 次）

## 数据结构

### SpanData

```python
@dataclass
class SpanData:
    # 通用字段
    trace_id: str              # 链路 ID
    span_id: str               # 当前 span ID
    parent_span_id: str        # 父 span ID
    name: str                  # span 名称
    start_time: str            # ISO 格式时间戳
    end_time: str              # ISO 格式时间戳
    span_type: str             # "trace" | "llm_metrics" | "prompt" | "tool_call" | "session"
    attributes: Dict[str, Any] # 附加属性

    # prompt 类型专属字段
    model_name: Optional[str]
    prompt: Optional[str]
    response: Optional[str]
    input_tokens: int
    output_tokens: int
    latency_ms: float
    stream: bool
    status: str                # "success" | "error"
    error: str

    # tool_call 类型专属字段
    tool_name: Optional[str]
    tool_type: Optional[str]
    input_data: Optional[str]
    output_data: Optional[str]
    duration_ms: float

    # session 类型专属字段
    session_id: Optional[str]
    agent_name: Optional[str]
    user_input: Optional[str]
    final_response: Optional[str]
    total_spans: int
    total_tokens: int
    total_cost_usd: float
```

### LLMCallRecord

由 Provider Adapter 填充的 LLM 调用记录：

```python
@dataclass
class LLMCallRecord:
    model: str              # 模型名称
    provider: str           # Provider 名称
    is_stream: bool         # 是否流式
    prefill_ms: float       # 首字耗时
    decode_ms: float        # 解码耗时
    input_tokens: int       # 输入 token 数
    output_tokens: int      # 输出 token 数
    tps: float              # 每秒 token 吞吐量
    prompt_text: str        # 用户输入摘要
    response_text: str      # 模型回复摘要
    latency_ms: float       # 总耗时
    error: Optional[str]    # 错误信息
    extra: Dict[str, Any]   # 扩展字段
```

### StreamMetrics

流式响应指标：

```python
@dataclass
class StreamMetrics:
    prefill_ms: float    # 首字耗时（毫秒）
    decode_ms: float     # 解码总耗时（毫秒）
    output_tokens: int   # 输出 token 数
    tps: float           # 每秒 token 吞吐量
```

### Trace Span（链路追踪）

```json
{
  "trace_id": "550e8400-e29b-41d4-a716-446655440000",
  "span_id": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "parent_span_id": "",
  "name": "llm_call",
  "start_time": "2026-06-28T10:00:00.000",
  "end_time": "2026-06-28T10:00:02.500",
  "span_type": "trace",
  "attributes": {
    "model": "gpt-5.4",
    "provider": "openai",
    "stream": false
  }
}
```

### LLM Metrics Span（性能指标）

```json
{
  "trace_id": "550e8400-e29b-41d4-a716-446655440000",
  "span_id": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "parent_span_id": "",
  "name": "llm_metrics",
  "start_time": "2026-06-28T10:00:00.000",
  "end_time": "2026-06-28T10:00:02.500",
  "span_type": "llm_metrics",
  "attributes": {
    "model_name": "gpt-5.4",
    "provider": "openai",
    "prefill_ms": 500.0,
    "decode_ms": 2000.0,
    "input_tokens": 1500,
    "output_tokens": 800,
    "tps": 400.0
  }
}
```

### Prompt Span（Prompt 日志）

```json
{
  "trace_id": "550e8400-e29b-41d4-a716-446655440000",
  "span_id": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "parent_span_id": "",
  "name": "prompt_log",
  "start_time": "2026-06-28T10:00:00.000",
  "end_time": "2026-06-28T10:00:02.500",
  "span_type": "prompt",
  "model_name": "gpt-5.4",
  "prompt": "Explain quantum computing",
  "response": "Quantum computing uses qubits...",
  "input_tokens": 1500,
  "output_tokens": 800,
  "latency_ms": 2500.0,
  "stream": false,
  "status": "success",
  "error": ""
}
```

## 高级用法

### 多模型对比测试

```python
async def compare_models(models: list[str], prompt: str):
    uploader = AsyncBatchUploader(backend_url="http://localhost:8000")
    await uploader.start()

    for model in models:
        ctx = TraceContext(name=f"test_{model}")
        set_current_context(ctx)

        client = OpenAI(api_key="your-key")
        interceptor = LLMInterceptor(uploader)
        wrapped = interceptor.wrap(client)

        # 流式调用
        stream = wrapped.chat.completions.create(
            model=model,
            messages=[{"role": "user", "content": prompt}],
            stream=True,
        )
        for chunk in stream:
            pass  # 消费流式响应

        interceptor.unwrap()

    await asyncio.sleep(2)
    await uploader.stop()

asyncio.run(compare_models(["gpt-5.4", "gpt-5.4-nano", "claude-opus-4-8"], "Explain quantum computing"))
```

### ToolSDK + LLMInterceptor 组合使用

```python
async def agent_with_tools():
    uploader = AsyncBatchUploader(backend_url="http://localhost:8000")
    await uploader.start()

    # 设置根上下文
    root_ctx = TraceContext(name="agent_task")
    set_current_context(root_ctx)

    # 初始化
    tool_sdk = ToolSDK(uploader)
    client = OpenAI(api_key="your-key")
    interceptor = LLMInterceptor(uploader)
    wrapped = interceptor.wrap(client)

    @tool_sdk.instrument(name="search", tool_type="retrieval")
    async def search(query: str) -> list:
        return [{"title": "result"}]

    # Agent 循环
    search_results = await search("quantum computing")
    response = wrapped.chat.completions.create(
        model="gpt-5.4",
        messages=[
            {"role": "user", "content": "Explain quantum computing"},
            {"role": "assistant", "content": "Let me search for information."},
            {"role": "user", "content": f"Search results: {search_results}"},
        ],
    )

    await asyncio.sleep(1)
    interceptor.unwrap()
    await uploader.stop()
```

### 错误处理

```python
try:
    response = wrapped_client.chat.completions.create(
        model="gpt-5.4",
        messages=[{"role": "user", "content": "Hello"}],
    )
except Exception as e:
    # SDK 会自动记录错误 span
    print(f"Error: {e}")
    raise
```

错误 span 会自动包含错误信息：
```json
{
  "attributes": {
    "model": "gpt-5.4",
    "provider": "openai",
    "error": "Rate limit exceeded"
  }
}
```

## 性能优化建议

### 1. 批量大小调优

```python
# 高并发场景：增大批量
uploader = AsyncBatchUploader(batch_size=50, flush_interval=1.0)

# 低延迟场景：减小批量
uploader = AsyncBatchUploader(batch_size=5, flush_interval=0.2)
```

### 2. 背压保护

Uploader 使用有界队列（默认容量 10000），队列满时 `submit()` 会丢弃数据并打印告警日志。
如果频繁出现 `dropped` 计数增长，说明上报速度跟不上产生速度，建议：
- 增大 `batch_size`
- 减小 `flush_interval`
- 检查后端服务和网络状况

```python
# 监控统计
stats = uploader.stats
# {"queue_size": 5, "sent": 1000, "failed": 0, "dropped": 0}
```

### 3. 避免阻塞事件循环

```python
# ✅ 推荐：异步提交
await uploader.submit(span)

# ❌ 避免：同步提交（会阻塞）
loop = asyncio.get_event_loop()
loop.run_until_complete(uploader.submit(span))
```

## 故障排查

### 问题 1：数据未上报

**检查点**：
1. 后端服务是否运行：`curl http://localhost:8000/health`
2. Kafka 是否运行：`docker ps | grep kafka`
3. 查看上报统计：`print(uploader.stats)`
4. 查看 SDK 日志：
```python
import logging
logging.basicConfig(level=logging.DEBUG)
```

### 问题 2：流式指标不准确

**原因**：网络延迟或 chunk 大小不均

**解决**：
- 确保使用 `stream=True`
- 检查 OpenAI API 响应是否包含 usage 字段

### 问题 3：上下文丢失

**原因**：异步任务未正确传递上下文

**解决**：
```python
# ✅ 推荐：显式传递上下文
async def task(ctx: TraceContext):
    set_current_context(ctx)
    # ...

# ❌ 避免：依赖隐式传递
async def task():
    ctx = get_current_context()  # 可能为 None
```

### 问题 4：Provider 未识别

**原因**：使用了未注册的 LLM Provider

**解决**：
```python
# 检查已注册的 Adapter
from agent_insight_sdk.providers.base import get_adapters
for adapter in get_adapters():
    print(adapter.provider_name)

# 注册自定义 Adapter
from agent_insight_sdk import BaseProviderAdapter, register_adapter
register_adapter(MyCustomAdapter())
```

## API 参考

### AsyncBatchUploader

| 方法/属性 | 说明 |
|------|------|
| `__init__(backend_url, batch_size, flush_interval)` | 初始化上报器 |
| `start()` | 启动后台上报任务 |
| `stop()` | 停止并刷新剩余数据 |
| `submit(span: SpanData)` | 提交 span 到队列（非阻塞，队列满时丢弃） |
| `stats` (property) | 返回上报统计 `{queue_size, sent, failed, dropped}` |

### LLMInterceptor

| 方法 | 说明 |
|------|------|
| `__init__(uploader, adapters=None)` | 初始化拦截器，可选指定 Adapter 列表 |
| `wrap(client)` | 自动识别 Provider 并返回拦截后的客户端 |
| `unwrap()` | 恢复原始客户端 |

### TraceAPI

| 方法 | 说明 |
|------|------|
| `__init__(uploader)` | 初始化 Trace API |
| `start_trace(name, trace_id="")` | 开始新 Trace |
| `start_span(name, attributes=None)` | 开始子 Span |
| `end_span(ctx=None, attributes=None, span_type="trace")` | 结束 Span 并上报 |
| `end_trace(attributes=None)` | 结束 Trace 并清除上下文 |

### ToolSDK

| 方法 | 说明 |
|------|------|
| `__init__(uploader)` | 初始化 Tool SDK |
| `instrument(name="", tool_type="generic")` | 装饰器，自动记录 Tool 调用 |

### TraceContext

| 方法 | 说明 |
|------|------|
| `__init__(trace_id, span_id, parent_span_id, name)` | 创建上下文 |
| `create_child(name)` | 创建子上下文 |

### StreamMonitor

| 方法 | 说明 |
|------|------|
| `record_start()` | 记录流式开始时间 |
| `record_first_chunk()` | 记录第一个 chunk 时间 |
| `record_chunk(chunk)` | 记录每个 chunk |
| `record_stream_usage(usage)` | 从 usage 字段记录 token 数 |
| `get_metrics()` | 返回 StreamMetrics |

### 全局函数

| 函数 | 说明 |
|------|------|
| `get_current_context()` | 获取当前上下文 |
| `set_current_context(ctx)` | 设置当前上下文 |
| `clear_current_context()` | 清除当前上下文 |
| `register_adapter(adapter)` | 注册自定义 Provider Adapter |

## 许可证

MIT
