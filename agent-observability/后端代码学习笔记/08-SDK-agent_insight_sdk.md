# 08 - SDK（agent_insight_sdk）模块学习笔记

> 📅 学习日期：2026-06-30
> 🎯 模块：`sdk/agent_insight_sdk/` — AI Agent 可观测性探针 SDK
> 👤 背景：Java 后端转 AI Agent，对照 Java 生态理解 Python

---

## 一、SDK 整体架构

### 1.1 目录结构

```
sdk/agent_insight_sdk/
├── __init__.py              ← 门面，统一导出所有核心类
├── context.py               ← 上下文管理（ContextVar = ThreadLocal）
├── uploader.py              ← 异步批量上报器（数据出口）
├── trace_api.py             ← 手动埋点工具（start_trace/end_trace）
├── tool_sdk.py              ← 装饰器自动埋点（@instrument）
├── stream_monitor.py        ← 流式响应监控器
├── interceptor.py           ← 老版拦截器（已废弃，保留兼容）
└── providers/
    ├── __init__.py          ← Provider 包入口
    ├── base.py              ← 适配器基类 + LLMInterceptor 统一拦截器
    ├── openai_compatible.py ← OpenAI 兼容适配器
    └── anthropic.py         ← Anthropic 适配器
```

### 1.2 核心设计：四大组件 + 依赖注入

| 组件 | 职责 | 对应 Java |
|------|------|----------|
| `TraceAPI` | 手动标 Trace 边界 | 手动埋点工具 |
| `ToolSDK` | 装饰器自动拦自己写的函数 | Spring AOP `@Around` |
| `LLMInterceptor` | 猴子补丁拦第三方 LLM 函数 | Spring AOP 动态代理 |
| `AsyncBatchUploader` | 批量发数据到后端 | Kafka Producer |

**四个组件共享同一个 uploader 实例（依赖注入）：**

```python
uploader = AsyncBatchUploader("http://localhost:8000")
await uploader.start()

trace_api = TraceAPI(uploader)        # 注入
tool_sdk = ToolSDK(uploader)          # 注入
interceptor = LLMInterceptor(uploader) # 注入
```

---

## 二、`__init__.py` — 门面模式

### 2.1 核心作用

**统一导出所有核心类，用户不用记具体文件路径：**

```python
# 用户这样写就行
from agent_insight_sdk import TraceAPI, ToolSDK, LLMInterceptor, AsyncBatchUploader
# 不用写
# from agent_insight_sdk.trace_api import TraceAPI
```

### 2.2 历史兼容设计

```python
OpenAIInterceptor = LLMInterceptor  # deprecated alias
```

- v0.1 只支持 OpenAI，叫 `OpenAIInterceptor`
- v0.3 支持多厂商，改名 `LLMInterceptor`，但保留旧名当别名
- 老用户升级代码不用改

### 2.3 `__all__` 导出控制

- 控制 `from xxx import *` 导入什么
- 不写 `__all__` 会把内部变量 `_current_context` 也导出来

### 2.4 Python 包机制（对照 Java）

| 维度 | Python | Java |
|------|--------|------|
| 包名来源 | 目录名 | `package com.xxx` 声明 |
| 入口标识 | `__init__.py` | 没有统一入口 |
| 导入什么 | 导入「模块/包」对象 | 导入类/接口 |
| 集中导出 | `__init__.py` 里统一 import | `package-info.java` 只能写文档 |

**关键：`__init__.py` 不是包名，目录名才是包名。Python 导入目录时自动执行 `__init__.py`。**

---

## 三、`context.py` — 上下文管理（ContextVar = ThreadLocal 增强版）

### 3.1 核心机制

```python
from contextvars import ContextVar

_current_context: ContextVar[Optional[TraceContext]] = ContextVar(
    "current_trace_context", default=None
)
```

| Python ContextVar | Java ThreadLocal |
|-----------------|------------------|
| ✅ 线程安全 | ✅ 线程安全 |
| ✅ 协程安全 | ❌ 协程不安全 |
| ✅ 子协程自动继承父上下文 | ❌ 不支持 |
| `.get()` / `.set()` | `.get()` / `.set()` |

### 3.2 TraceContext 数据类

```python
@dataclass
class TraceContext:
    trace_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    span_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    parent_span_id: Optional[str] = None
    name: str = ""

    def create_child(self, name: str) -> "TraceContext":
        return TraceContext(
            trace_id=self.trace_id,        # 继承父 trace_id
            span_id=str(uuid.uuid4()),     # 新 span_id
            parent_span_id=self.span_id,   # 指向父 span_id
            name=name,
        )
```

**三个 ID 构建调用树（OpenTelemetry 标准）：**
- `trace_id`：整个调用链唯一，所有子 Span 共用
- `span_id`：每个步骤唯一
- `parent_span_id`：指向父节点，构建树形结构

### 3.3 `field(default_factory=...)` vs `default=...`

| 写法 | 执行时机 | 结果 |
|------|---------|------|
| `default=str(uuid.uuid4())` | 类定义时执行一次 | 所有对象共享同一个值 ❌ |
| `default_factory=lambda: str(uuid.uuid4())` | 每次 new 对象时执行 | 每个对象有自己的新值 ✅ |

**Java 对照：** `default_factory` = 构造函数里 `UUID.randomUUID()`，`default` = 静态变量初始化。

### 3.4 三个操作函数

```python
def get_current_context(): return _current_context.get()
def set_current_context(ctx): _current_context.set(ctx)
def clear_current_context(): _current_context.set(None)
```

**为什么必须 `finally clear`？** 线程池/协程池复用线程，不清会把 A 请求的 trace_id 带到 B 请求。

### 3.5 命名约定（下划线可见性）

| 命名 | 含义 | Java 对应 |
|------|------|----------|
| `name` | 公开 | `public` |
| `_name` | 内部用 | `protected` |
| `__name` | 改名防外部访问 | `private` |

---

## 四、`uploader.py` — 异步批量上报器

### 4.1 SpanData 数据结构

```python
@dataclass
class SpanData:
    # 所有类型共有
    trace_id: str
    span_id: str
    parent_span_id: Optional[str] = None
    name: str = ""
    start_time: str = ""
    end_time: str = ""
    span_type: str = "trace"
    attributes: Dict[str, Any] = None

    # prompt 类型专属
    model_name: Optional[str] = None
    prompt: Optional[str] = None
    response: Optional[str] = None
    input_tokens: int = 0
    ...
```

**`span_type` 决定进哪张表：**

| span_type | 进哪张表 |
|-----------|---------|
| `"trace"` | `agent_traces` |
| `"llm_metrics"` | `llm_metrics` |
| `"prompt"` | `prompt_logs` |
| `"tool_call"` | `tool_calls` |
| `"session"` | `sessions` |

**`__post_init__`**：dataclass 构造完后自动执行，把 None 转成空字典（防 KeyError）。

**`to_dict()`**：按 span_type 只序列化对应字段，不发多余数据。

### 4.2 AsyncBatchUploader 核心设计

| 设计 | 解决什么问题 |
|------|------------|
| 异步队列 + 后台任务 | 不阻塞用户业务代码 |
| 批量发送（数量+时间双触发） | 减少 HTTP 请求次数 |
| 有界队列 + 背压保护 | 防止内存 OOM |
| 指数退避重试 | 失败别疯狂打后端 |

**配置参数：**
- `batch_size=20`：攒够 20 条发一次
- `flush_interval=0.5`：0.5 秒没攒够也发
- `QUEUE_MAXSIZE=10000`：队列上限
- `MAX_RETRIES=3`：最多重试 3 次

### 4.3 背压（Back Pressure）

**生活类比：水龙头 + 水池 + 下水道**
- 生产者快，消费者慢 → 水池满了
- SDK 选择：丢数据 + 告警（宁可丢监控，不能卡业务）

```python
async def submit(self, span):
    try:
        self._queue.put_nowait(span.to_dict())  # 进队列，立刻返回
    except asyncio.QueueFull:
        self._dropped += 1  # 丢数据，告警
```

### 4.4 完整数据流

```
用户业务代码 → 拦截器产生 SpanData → submit() 进队列
    ↓
asyncio.Queue（有界，最多 10000 条）
    ↓
后台 _upload_loop() 死循环
    ↓ 攒够 20 条 或 等 0.5 秒
_flush_batch() → HTTP POST /api/v1/collect
    ↓ 失败指数退避（1s/2s/4s）
    ↓ 还失败丢弃 + 告警
后端 FastAPI → Kafka → ClickHouse
```

---

## 五、`trace_api.py` — 手动埋点工具

### 5.1 四个核心方法

| 方法 | 作用 | 底层调了什么 |
|------|------|------------|
| `start_trace(name)` | 开始整个调用链 | `TraceContext()` + `set_current_context()` |
| `start_span(name)` | 开始子步骤 | `get_current_context()` + `create_child()` + `set_current_context()` |
| `end_span()` | 结束步骤 + 发数据 | 构建 SpanData + `uploader.submit()` |
| `end_trace()` | 结束调用链 | `end_span()` + `clear_current_context()` |

### 5.2 `_span_start_times` 字典

```python
self._span_start_times: Dict[str, datetime] = {}
```

**作用：** start_span 和 end_span 是两次独立调用，用字典存开始时间，end 时 pop 出来算耗时。

### 5.3 99% 场景的用法

```python
# 就这一行，其他全自动
with trace_api.start_trace("my_agent"):
    response = client.chat.completions.create(...)  # 自动拦
    docs = vector_search(query)                       # 自动拦
# with 块结束自动 end_trace + clear_context
```

**`with` = Python 上下文管理器：** 进入块前自动 set，离开块后自动 clear。

### 5.4 Trace vs Span 关系

```
Trace = 整个事件（一次 Agent 回答）
Span = 事件里的每个具体步骤（每次 LLM 调用、Tool 调用）
```

**调用树示例：**
```
Agent (span-1, parent=None)  ← start_trace 创建的根
    ├─ LLM 调用 (span-2, parent=span-1)  ← 拦截器自动创建
    ├─ Tool 搜索 (span-3, parent=span-1)  ← 装饰器自动创建
    │   └─ LLM 调用 (span-4, parent=span-3)  ← 孙子级
    └─ LLM 调用 (span-5, parent=span-1)
```

**谁调 start_span，父就是那一刻的 current_context。**

---

## 六、`tool_sdk.py` — 装饰器自动埋点

### 6.1 装饰器 = AOP 环绕通知（静态代理）

```python
@tool_sdk.instrument(name="天气搜索", tool_type="api")
def search_weather(city):
    return "晴天"
```

**`@xxx` 本质 = 把你的函数传进 xxx，xxx 返回壳函数，重新赋值：**

```python
# @ 语法糖等价于：
search_weather = tool_sdk.instrument(name="天气搜索")(search_weather)
```

### 6.2 `*args, **kwargs` = 原封不动接住所有参数

```python
def sync_wrapper(*args, **kwargs):
    # *args = ("北京",)           ← 位置参数打包成元组
    # **kwargs = {"days": 3}      ← 关键字参数打包成字典
    return self._record_sync(func, tool_name, tool_type, args, kwargs)
```

**作用：** 壳函数不知道用户传什么参数，全接住再原封不动转给真实函数。

### 6.3 `_record_sync()` 执行流程

```python
def _record_sync(self, func, tool_name, tool_type, args, kwargs):
    # 1. 读黑板，拿父上下文
    parent_ctx = get_current_context()
    if parent_ctx:
        ctx = parent_ctx.create_child(f"tool:{tool_name}")  # 有父 = 子 Span
    else:
        ctx = TraceContext(name=f"tool:{tool_name}")        # 没父 = 自己当根（兜底）
    
    set_current_context(ctx)
    start_time = datetime.utcnow()
    
    # 2. 记录输入
    input_data = self._safe_serialize({"args": args, "kwargs": kwargs})
    
    try:
        # 3. 调用真实函数！
        result = func(*args, **kwargs)
        
        # 4. 记录输出 + 耗时
        output_data = self._safe_serialize(result)
        
        # 5. 发数据
        self._report_tool_span(..., status="success")
        return result
    
    except Exception as e:
        # 异常也记录
        self._report_tool_span(..., status="error", error=str(e))
        raise  # 异常原封不动抛出去
```

### 6.4 `functools.wraps(func)` 

让壳函数看起来跟原函数一模一样（名字、文档、参数列表不变），别人看不出被包装了。

---

## 七、`providers/base.py` — 适配器模式 + LLMInterceptor

### 7.1 适配器模式（Adapter Pattern）

| 角色 | Java 对应 | 干啥 |
|------|----------|------|
| `BaseProviderAdapter` | `abstract class` / `interface` | 定义标准 |
| `OpenAICompatibleAdapter` | 具体实现 | 适配 OpenAI 协议 |
| `AnthropicAdapter` | 具体实现 | 适配 Anthropic 协议 |
| `LLMInterceptor` | 调度器 | 遍历找匹配的 Adapter |
| `LLMCallRecord` | DTO | 统一数据格式 |

**为什么需要适配器？OpenAI 和 Anthropic 的函数名和返回值格式完全不一样：**

```python
# OpenAI
client.chat.completions.create(...)
response.choices[0].message.content
response.usage.prompt_tokens

# Anthropic
client.messages.create(...)
response.content[0].text
response.usage.input_tokens
```

### 7.2 `BaseProviderAdapter` 三个核心方法

```python
class BaseProviderAdapter(ABC):  # ← ABC = Java abstract class
    
    @abstractmethod
    def supports(self, client) -> bool:    # 「我认识这个客户端吗？」
        ...
    
    @abstractmethod
    def _wrap_call(self, client, interceptor) -> Any:  # 「打补丁」
        ...
    
    @abstractmethod
    def _unwrap_client(self, wrapped) -> None:  # 「恢复」
        ...
    
    def extract(self, kwargs, response, perf_start, is_stream) -> LLMCallRecord:
        # 默认实现，子类可重写
```

| Python | Java |
|--------|------|
| `class XXX(ABC):` | `abstract class XXX` |
| `@abstractmethod` | `abstract` 方法 |

### 7.3 `extract()` — 总指挥 + 三个小弟

```python
def extract(self, kwargs, response, perf_start, is_stream) -> LLMCallRecord:
    record = LLMCallRecord(model=kwargs.get("model"), ...)
    
    if not is_stream and response is not None:
        record = self._extract_tokens(response, record)    # 拿 token 数
        record = self._extract_prompt(kwargs, record)      # 拿用户问什么
        record = self._extract_response(response, record)  # 拿 AI 回什么
    
    return record
```

| 方法 | 提取什么 | 从哪拿 |
|------|---------|--------|
| `_extract_tokens` | token 数 + TPS | `response.usage.prompt_tokens` |
| `_extract_prompt` | 用户问题 | `kwargs["messages"]` 最后一条 user 消息 |
| `_extract_response` | AI 回答 | `response.choices[0].message.content` |

### 7.4 内置 Adapter 注册表

```python
_BUILTIN_ADAPTERS: List[BaseProviderAdapter] = []  # 全局列表

def register_adapter(adapter):
    _BUILTIN_ADAPTERS.append(adapter)

def get_adapters():
    return list(_BUILTIN_ADAPTERS)  # 复制一份
```

**「导入即注册」机制：**
```
import providers → __init__.py 执行
    → from .openai_compatible import ...
    → openai_compatible.py 加载
    → 最后一行：register_adapter(OpenAICompatibleAdapter())
    → _BUILTIN_ADAPTERS 有值了
```

**Java 对照：** `_BUILTIN_ADAPTERS` = Spring Bean 容器，`register_adapter` = `@Component`。

### 7.5 LLMInterceptor

```python
class LLMInterceptor:
    def __init__(self, uploader, adapters=None):
        self._uploader = uploader
        self._adapters = adapters or list(_BUILTIN_ADAPTERS)  # 没传用内置的
        self._active_adapter = None
    
    def wrap(self, client):
        for adapter in self._adapters:
            if adapter.supports(client):          # 遍历问
                self._active_adapter = adapter
                return adapter._wrap_call(client, self)  # 打补丁
        raise ValueError("未找到匹配的 Adapter")
    
    def unwrap(self):
        if self._active_adapter:
            self._active_adapter._unwrap_client(...)  # 恢复
```

### 7.6 `_report()` — 一次 LLM 调用产生 3 条 SpanData

```python
def _report(self, ctx, record, start_time, end_time):
    trace_span = SpanData(..., span_type="trace", ...)        # → agent_traces
    metrics_span = SpanData(..., span_type="llm_metrics", ...)  # → llm_metrics
    prompt_span = SpanData(..., span_type="prompt", ...)        # → prompt_logs
    self._submit([trace_span, metrics_span, prompt_span])       # 一次发 3 条
```

**为什么要拆 3 条？查询场景不同：**
- 看调用轨迹 → `agent_traces`
- 看性能指标 → `llm_metrics`
- 看 prompt/response 全文 → `prompt_logs`

### 7.7 `_submit()` — 兼容同步/异步

```python
def _submit(self, spans):
    loop = asyncio.get_event_loop()
    if loop.is_running():
        # 异步环境：后台发，不等
        for span in spans:
            asyncio.ensure_future(self._uploader.submit(span))
    else:
        # 同步环境：阻塞发完
        loop.run_until_complete(...)
```

---

## 八、`openai_compatible.py` — OpenAI 适配器

### 8.1 `supports()` — 自动识别客户端

```python
def supports(self, client) -> bool:
    return hasattr(client, "chat") and hasattr(client.chat.completions, "create")
```

**检查有没有 `chat.completions.create` 方法。所有 OpenAI 兼容厂商都有这个接口。**

### 8.2 `_wrap_call()` — 猴子补丁核心

```python
def _wrap_call(self, client, interceptor):
    self._original = client.chat.completions.create  # 1. 存原始函数
    self._client = client
    
    @functools.wraps(self._original)
    def wrapper(*args, **kwargs):
        return self._handle(client, args, kwargs)
    
    client.chat.completions.create = wrapper  # 🔥 替换！猴子补丁！
    return client
```

### 8.3 `_handle()` — 拦截逻辑

```python
def _handle(self, client, args, kwargs):
    parent_ctx = get_current_context()     # 1. 拿 trace_id
    ctx = parent_ctx.create_child("llm_call") if parent_ctx else TraceContext(name="llm_call")
    set_current_context(ctx)
    
    try:
        response = self._original(*args, **kwargs)  # 2. 调真实函数
    except Exception as exc:
        # 异常也记录
        record.error = str(exc)
        interceptor._report(ctx, record, ...)
        raise
    
    if is_stream:
        return self._wrap_stream(...)  # 3a. 流式包装
    else:
        record = self.extract(...)     # 3b. 非流式提取
        interceptor._report(...)        # 发 3 条 SpanData
        return response
```

---

## 九、三种代理方式对比

| 方式 | 什么时候介入 | 怎么用 | 适合什么 | Java 对应 |
|------|------------|--------|---------|----------|
| **装饰器** | 函数定义时 | `@xxx` | 自己写的函数 | AspectJ 编译时织入（静态代理） |
| **猴子补丁** | 程序启动时 | `client.xxx = wrapper` | 别人的函数 | Spring AOP（动态代理） |
| **包装器** | 调用返回后 | `Wrapper(original_obj)` | 别人的对象 | 装饰器模式 |

**关键区别：**
- 装饰器要求你能在源码上加 `@`，只能套自己写的函数
- 猴子补丁运行时替换函数引用，能套别人的函数
- 包装器运行时包对象，适合迭代器/流这种对象

**流式响应用包装器，因为返回的是迭代器对象，不是函数！**

---

## 十、`stream_monitor.py` — 流式响应监控

### 10.1 三个角色

| 类 | 是什么 | 干啥 |
|------|--------|------|
| `StreamMonitor` | 工具（不是包装层） | 记时间、算指标 |
| `MonitoredStream` | 第 1 层包装 | 包原始 stream，每个 chunk 记时间 |
| `StreamWrapper` | 第 2 层包装 | 包 MonitoredStream，结束时发数据 |

### 10.2 StreamMetrics 指标

| 字段 | 含义 | 为什么重要 |
|------|------|----------|
| `prefill_ms` | 首 token 耗时 | 用户等多久看到第一个字 |
| `decode_ms` | 解码总耗时 | 从第一个字到最后一个字 |
| `output_tokens` | 输出 token 数 | 算成本 |
| `tps` | 每秒生成 token 数 | 模型生成速度 |

### 10.3 用户不用管这些

**用户只管 `wrap(client)` 一次，流式监控全自动：**

```python
# 用户代码完全不变
response = client.chat.completions.create(..., stream=True)
for chunk in response:  # ← SDK 内部自动包装了
    print(chunk)
```

---

## 十一、`interceptor.py` — 老版本拦截器（已废弃）

| 对比 | 老版本 `interceptor.py` | 新版本 `providers/base.py` |
|------|------------------------|--------------------------|
| 类名 | `OpenAIInterceptor` | `LLMInterceptor` |
| 支持厂商 | 只支持 OpenAI | 支持多厂商 |
| 方法名 | `patch(client)` | `wrap(client)` |
| 有 Adapter | ❌ 没有 | ✅ 有 |
| 能扩展 | 不能 | 能 |

**`__init__.py` 保留兼容：** `OpenAIInterceptor = LLMInterceptor  # deprecated alias`

---

## 十二、完整使用流程（用户视角）

```python
# 1. 初始化（只做一次）
uploader = AsyncBatchUploader("http://localhost:8000")
await uploader.start()

trace_api = TraceAPI(uploader)
tool_sdk = ToolSDK(uploader)
interceptor = LLMInterceptor(uploader)

# 2. 包 client（只做一次）
client = OpenAI(api_key="sk-xxx")
interceptor.wrap(client)  # 内部自动：选 Adapter → 打补丁 → 流式/非流式都自动处理

# 3. 定义 Tool（加个 @）
@tool_sdk.instrument(name="搜索", tool_type="retrieval")
def vector_search(query):
    return db.query(query)

# 4. 写 Agent（用 with 包一层）
with trace_api.start_trace("my_agent"):
    docs = vector_search(query)              # 自动拦
    response = client.chat.completions.create(...)  # 自动拦
# with 块结束自动 end_trace + clear_context
```

**用户要写的就 4 行：初始化、wrap、@、with。其他全自动。**

---

## 十三、⚠️ 代码缺陷清单

### 缺陷 1：ToolSDK 的 span_type 用错

**位置：** `tool_sdk.py` 第 188 行 `_report_tool_span()`

**问题：** `span_type="trace"`，Tool 数据只进 `agent_traces` 表，没进 `tool_calls` 表。

**对比 LLMInterceptor（正确做法）：** 一次 LLM 调用产生 3 条 SpanData，分别进 3 张表。ToolSDK 应该产生 2 条（trace + tool_call），但只产生了 1 条。

**正确改法：**
```python
trace_span = SpanData(..., span_type="trace", ...)
tool_span = SpanData(..., span_type="tool_call", tool_name=..., input_data=..., ...)
self._submit([trace_span, tool_span])
```

### 缺陷 2：client.py 的 insert_* 是 async 函数被当同步函数跑

**位置：** `backend/app/clickhouse/client.py`

**调用链：**
```
consumer.py: await _retry_insert(insert_traces, batch, "traces")
                           ↑ 传的是 async 函数
client.py:   await loop.run_in_executor(None, insert_fn, data)
                           ↑ 把 async 函数丢线程池当同步函数跑
                           ↑ 会返回 coroutine 对象，不执行，静默失败
```

**根因：** `insert_traces` 是 `async def`，内部已用 `run_in_executor` 处理，外面又被 `_retry_insert` 用 `run_in_executor` 包了一层。

---

## 十四、关键认知总结

1. **「无侵入」的真实含义**：不用改核心业务逻辑，只加少量启动代码（start_trace + wrap + @）
2. **猴子补丁拦最底层的**：LangChain/LangGraph 内部最终都调 openai SDK，补丁打在 openai 上，上层全自动受益
3. **Python 模块是全局单例**：整个程序里 import 多少次都是同一个对象，改了它的函数引用，所有人调用的都是改后的
4. **Adapter 模式**：每个厂商一个 Adapter，各自实现 supports + _wrap_call + extract，LLMInterceptor 遍历找匹配的
5. **三种代理方式**：装饰器（静态，套自己函数）、猴子补丁（动态，套别人函数）、包装器（套对象）
6. **依赖注入**：四大组件共享同一个 uploader，产生数据都扔进同一个队列
7. **批量上报**：攒够 20 条或等 0.5 秒发一次，有界队列防 OOM，指数退避保护后端
8. **5 种 span_type 对应 5 张表**：trace/llm_metrics/prompt/tool_call/session
9. **MCP 本质就是远程 Tool**：对 SDK 来说跟本地 Tool 没区别，用 `@tool_sdk.instrument(tool_type="mcp")` 包一层
10. **当前 SDK 是教学版**：只监控 LLM 和 Tool，Agent 跟 Agent 交互要手动 start_span，工业级 SDK（如 LangSmith）会集成 LangChain 回调全自动

---

## 十五、Python vs Java 对照表

| Python | Java |
|--------|------|
| `@dataclass` | Lombok `@Data` |
| `ContextVar` | `ThreadLocal<T>`（但支持协程） |
| `@abstractmethod` + `ABC` | `abstract class` / `interface` |
| `@functools.wraps(func)` | 让代理对象看起来跟原对象一样 |
| `*args, **kwargs` | `Object... args` |
| `hasattr(obj, "xxx")` | `obj.getClass().getMethod("xxx")` |
| `getattr(obj, "xxx", default)` | 反射 + 默认值 |
| 猴子补丁 | JDK 动态代理 / CGLIB |
| 装饰器 | Spring AOP `@Around` |
| `_BUILTIN_ADAPTERS` 全局列表 | Spring Bean 容器 |
| `register_adapter()` | `@Component` 注册 Bean |
| `asyncio.Queue` | `LinkedBlockingQueue` |
| `asyncio.create_task()` | `CompletableFuture.runAsync()` |
| `loop.run_in_executor()` | `ExecutorService.submit()` |
| `async/await` | `CompletableFuture` / Reactor |
| `with` 上下文管理器 | `try-with-resources` |
| `__init__.py` | `package-info.java` |
| `__version__` | `pom.xml` 的 `<version>` |
| `__all__` | 包的 `public` 类 vs 内部类 |
