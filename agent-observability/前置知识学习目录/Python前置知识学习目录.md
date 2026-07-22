# Agent-Observability 项目 Python 前置知识学习目录

> **目标**：学完这 6 个模块，能独立读懂并修改本项目的 SDK + 后端代码。
>
> **项目代码阅读顺序**：[context.py](../sdk/agent_insight_sdk/context.py) → [uploader.py](../sdk/agent_insight_sdk/uploader.py) → [providers/base.py](../sdk/agent_insight_sdk/providers/base.py)
>
> **总投入**：约 7~13 天，每天 3~4 小时。模块 3 是重中之重。

---

## 目录

- [模块 1：Python 类型注解与 dataclass](#模块-1python-类型注解与-dataclass)
- [模块 2：装饰器、functools.wraps、ABC、生成器](#模块-2装饰器functoolswrapsabc生成器)
- [模块 3：async/await 与 asyncio 并发原语（重点）](#模块-3asyncawait-与-asyncio-并发原语重点)
- [模块 4：contextvars 异步上下文隔离](#模块-4contextvars-异步上下文隔离)
- [模块 5：猴子补丁 Monkey Patching](#模块-5猴子补丁-monkey-patching)
- [模块 6：FastAPI + Pydantic + httpx 异步 Web 栈](#模块-6fastapi--pydantic--httpx-异步-web-栈)
- [学习顺序与验收总表](#学习顺序与验收总表)

---

## 模块 1：Python 类型注解与 dataclass

### 学习目标

看懂并会写项目里 `SpanData`、`TraceContext`、`LLMCallRecord` 这类数据结构。这三个 dataclass 是整个 SDK 的数据骨架，所有 span 上报都围绕它们展开。

---

### 1.1 类型注解（Type Hints）

Python 是动态类型语言，但从 3.5 起支持类型注解。类型注解**不被运行时强制**，但能让 IDE 自动补全、让 mypy 做静态检查，在大型项目里几乎是标配。本项目全量使用类型注解。

#### 基础类型注解

```python
name: str = "agent"
count: int = 0
latency: float = 1.5
enabled: bool = True
```

#### Optional —— 表示"可以为空"

`Optional[X]` 等价于 `X | None`。当一个字段可能没有值时必须用它，否则 mypy 会报错。

```python
from typing import Optional

parent_span_id: Optional[str] = None  # 根 span 没有父级
```

项目里 `SpanData.parent_span_id` 就是 `Optional[str]`——根 span 的 parent 为 None，子 span 才有值。

#### 容器类型

```python
from typing import Dict, List, Tuple, Any

attributes: Dict[str, Any] = {}        # 键值都任意的字典
tags: List[str] = []                   # 字符串列表
coordinates: Tuple[float, float] = (0.0, 0.0)
```

`Any` 表示"任意类型"，项目里 `attributes: Dict[str, Any]` 用于存放各种附加属性（model 名、token 数等），类型不固定。

#### Callable —— 函数类型

```python
from typing import Callable

# 一个接收 str 返回 int 的函数
handler: Callable[[str], int]
```

项目里拦截器会把原始方法存成变量，理解 `Callable` 能帮你读懂回调签名。

#### 返回值注解与前向引用

```python
def create_child(self, name: str) -> "TraceContext":
    ...
```

返回类型是 `TraceContext` 本身，但类还没定义完，用字符串 `"TraceContext"` 延迟求值。项目里 [context.py](../sdk/agent_insight_sdk/context.py) 就这么写。

#### 掌握标准

能写出下面的签名并理解每一处含义：

```python
def extract(
    self,
    kwargs: Dict[str, Any],
    response: Any,
    perf_start: float,
    is_stream: bool,
) -> LLMCallRecord:
    ...
```

---

### 1.2 dataclass 数据类

#### 为什么用 dataclass

普通类要手写 `__init__`、`__repr__`、`__eq__`，字段一多就重复劳动。`@dataclass` 装饰器自动生成这些方法，还能用 `field()` 精细控制每个字段。

#### 基本用法

```python
from dataclasses import dataclass

@dataclass
class Point:
    x: float
    y: float

p = Point(1.0, 2.0)
print(p)  # Point(x=1.0, y=2.0)  自动生成 repr
```

#### field(default_factory=...) —— 可变默认值

**这是一个大坑**：直接给字段写 `tags: list = []` 会让所有实例共享同一个列表（Python 可变默认值陷阱）。必须用 `field(default_factory=list)` 让每个实例获得独立的新列表。

```python
from dataclasses import dataclass, field

@dataclass
class Task:
    name: str
    tags: list = field(default_factory=list)  # ✅ 每个实例独立的新 list

t1 = Task("a")
t2 = Task("b")
t1.tags.append("urgent")
print(t2.tags)  # []  不受影响
```

项目里 `TraceContext` 用 `field(default_factory=lambda: str(uuid.uuid4()))` 为每个 span 自动生成唯一 ID：

```python
@dataclass
class TraceContext:
    trace_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    span_id: str = field(default_factory=lambda: str(uuid.uuid4()))
```

#### __post_init__ —— 初始化后钩子

`__post_init__` 在 `__init__` 完成后自动调用，常用于校验数据、补全字段、计算派生字段。

项目里 `SpanData` 用它把 `None` 的 attributes 补成空字典：

```python
@dataclass
class SpanData:
    attributes: Dict[str, Any] = None  # 先声明为 None

    def __post_init__(self):
        if self.attributes is None:
            self.attributes = {}  # 再补成空字典
```

#### field(init=False) —— 排除出构造函数

有些字段不该让调用方传入，而是内部计算。用 `init=False` + `default_factory`：

```python
@dataclass
class User:
    username: str
    user_id: str = field(init=False, default_factory=create_uuid)
    joined_year: int = field(init=False)

    def __post_init__(self):
        self.joined_year = time.localtime().tm_year
```

#### asdict() —— 转 dict

```python
from dataclasses import asdict
print(asdict(Point(1, 2)))  # {'x': 1, 'y': 2}
```

项目里 `SpanData.to_dict()` 手写了转换逻辑（因为要按 span_type 选字段），思路类似。

#### 默认值顺序规则

**有默认值的字段不能出现在无默认值字段之前**，否则 `__init__` 参数顺序非法：

```python
@dataclass
class Bad:
    a: int = 1      # 有默认
    b: str          # 无默认  ❌ 报错

@dataclass
class Good:
    b: str          # 无默认在前
    a: int = 1      # 有默认在后  ✅
```

看 [SpanData](../sdk/agent_insight_sdk/uploader.py) 的字段排列：`trace_id`、`span_id` 无默认值在最前，其余都有默认值在后。

---

### 1.3 项目实例精读

打开 [uploader.py](../sdk/agent_insight_sdk/uploader.py) 第 14~95 行，`SpanData` 是整个 SDK 最核心的数据结构，支持 5 种 span_type：

| span_type | 写入哪张表 | 专属字段 |
|-----------|-----------|---------|
| `trace` | agent_traces | attributes |
| `llm_metrics` | llm_metrics | prefill_ms / decode_ms / tps |
| `prompt` | prompt_logs | prompt / response / tokens |
| `tool_call` | tool_calls | tool_name / input_data / duration_ms |
| `session` | sessions | session_id / total_tokens / total_cost |

`to_dict()` 方法根据 span_type 选择性输出字段，这是 dataclass + 条件逻辑的典型用法。

---

### 1.4 实践练习

1. 写一个 `@dataclass User`，含 `name: str`、`tags: List[str] = field(default_factory=list)`、`__post_init__` 校验 name 非空（空则 raise ValueError）
2. 把 [SpanData](../sdk/agent_insight_sdk/uploader.py) 完整抄一遍，分别实例化 5 种 span_type 并打印 `to_dict()` 结果
3. 故意写 `tags: list = []`，实例化两个对象互相 append，观察共享 bug

### 参考资料

- [dataclasses 官方文档](https://docs.python.org/3/library/dataclasses.html) — field、__post_init__、default_factory
- [PEP 557 Data Classes](https://peps.python.org/pep-0557/) — 设计动机
- [Real Python: Data Classes Guide](https://realpython.com/python-data-classes/) — 可变默认值陷阱、排序
- [Advanced Dataclasses](https://www.pythoncompiler.io/python/python-dataclasses-advanced/) — __post_init__ 校验实战

---

## 模块 2：装饰器、functools.wraps、ABC、生成器

### 学习目标

看懂 `ToolSDK` 装饰器自动埋点、`BaseProviderAdapter` 抽象基类、`StreamMonitor` 流式生成器。这三个特性贯穿整个 SDK 设计。

---

### 2.1 装饰器基础

#### 函数即对象

Python 里函数是一等对象，可以被赋值、传参、返回。这是装饰器存在的基础：

```python
def greet():
    print("hi")

f = greet   # 赋值
f()         # 调用

def call(func):
    func()  # 传参并调用

call(greet)
```

#### 闭包

内层函数捕获外层变量，即使外层已返回，内层仍能访问：

```python
def make_counter():
    count = 0
    def inc():
        nonlocal count
        count += 1
        return count
    return inc

c = make_counter()
print(c(), c(), c())  # 1 2 3
```

#### 装饰器语法糖

`@decorator` 等价于 `func = decorator(func)`：

```python
def log(func):
    def wrapper(*args, **kwargs):
        print(f"calling {func.__name__}")
        return func(*args, **kwargs)
    return wrapper

@log
def add(a, b):
    return a + b
# 等价于 add = log(add)
```

#### 带参数装饰器

需要三层嵌套：最外层接收参数，中间层接收函数，最内层执行包装：

```python
def repeat(times):
    def decorator(func):
        def wrapper(*args, **kwargs):
            for _ in range(times):
                result = func(*args, **kwargs)
            return result
        return wrapper
    return decorator

@repeat(3)
def say():
    print("hi")
```

#### functools.wraps —— 保留原函数元信息

装饰器会用 `wrapper` 替换原函数，导致 `wrapper.__name__` 变成 `"wrapper"`，丢失原函数名。`@functools.wraps(func)` 把原函数的 `__name__`、`__doc__`、`__dict__` 复制到 wrapper：

```python
import functools

def log(func):
    @functools.wraps(func)  # 关键
    def wrapper(*args, **kwargs):
        print(f"calling {func.__name__}")
        return func(*args, **kwargs)
    return wrapper

@log
def add(a, b):
    """两数相加"""
    return a + b

print(add.__name__)  # add（而非 wrapper）
print(add.__doc__)   # 两数相加
```

**必须会写**：带参数的异步装饰器（项目里 `ToolSDK` 就是这种形态）：

```python
import functools
import time

def tool_sdk(name: str):
    def decorator(func):
        @functools.wraps(func)
        async def wrapper(*args, **kwargs):
            start = time.perf_counter()
            result = await func(*args, **kwargs)
            duration = (time.perf_counter() - start) * 1000
            print(f"tool {name} took {duration:.2f}ms")
            # 这里上报 span
            return result
        return wrapper
    return decorator

@tool_sdk("vector_search")
async def search(query: str):
    await asyncio.sleep(0.1)
    return ["result"]
```

---

### 2.2 抽象基类 ABC + abstractmethod

#### 为什么需要 ABC

普通 Python 类无法强制子类实现某个方法。`ABC` + `@abstractmethod` 让基类变成"蓝图"，子类不实现抽象方法就不能实例化。

#### 基本用法

```python
from abc import ABC, abstractmethod

class Shape(ABC):
    @abstractmethod
    def area(self) -> float:
        ...

class Circle(Shape):
    def __init__(self, r):
        self.r = r
    def area(self):
        return 3.14 * self.r ** 2

# Shape()        # ❌ 报错：不能实例化抽象类
Circle(2).area() # ✅ 子类实现了 area
```

#### 模板方法模式

基类定义流程骨架，部分步骤留给子类填空。项目里 `BaseProviderAdapter` 就是这个模式：

```python
class BaseProviderAdapter(ABC):
    @abstractmethod
    def supports(self, client) -> bool: ...

    @abstractmethod
    def _wrap_call(self, client, interceptor): ...

    # 非抽象方法：默认实现，子类可重写
    def extract(self, kwargs, response, perf_start, is_stream) -> LLMCallRecord:
        record = LLMCallRecord(...)
        record = self._extract_tokens(response, record)
        return record

    # 子类可复用的辅助方法
    def _extract_tokens(self, response, record):
        ...
```

子类 `OpenAICompatibleAdapter` 只需实现 `supports` 和 `_wrap_call`，`extract` 用默认实现即可。`AnthropicAdapter` 如果响应结构不同，重写 `extract`。

#### 注册表模式

项目用全局列表 `_BUILTIN_ADAPTERS` 动态注册 Adapter，`LLMInterceptor.wrap(client)` 遍历列表找匹配的：

```python
_BUILTIN_ADAPTERS: List[BaseProviderAdapter] = []

def register_adapter(adapter):
    _BUILTIN_ADAPTERS.append(adapter)

class LLMInterceptor:
    def wrap(self, client):
        for adapter in self._adapters:
            if adapter.supports(client):
                return adapter._wrap_call(client, self)
        raise ValueError("未找到匹配的 Adapter")
```

这是"开闭原则"的体现——新增厂商只需写一个 Adapter 子类并注册，不改 `LLMInterceptor`。

---

### 2.3 生成器与迭代器

#### yield 基础

`yield` 让函数变成生成器：调用时不立即执行，每次 `next()` 才执行到下一个 `yield`：

```python
def counter():
    yield 1
    yield 2
    yield 3

g = counter()
print(next(g))  # 1
print(next(g))  # 2
```

#### 生成器 vs 普通函数

- 普通函数 `return` 一次返回就结束
- 生成器 `yield` 可多次返回，函数状态在 yield 之间保留

#### 包装生成器（项目核心）

OpenAI 流式响应 `stream=True` 返回一个迭代器，SDK 要包装它来记录每个 chunk 的时间戳：

```python
import time

def monitor_stream(original_stream):
    """包装一个流式响应，记录每个 chunk 时间"""
    first_chunk_time = None
    last_chunk_time = None
    chunk_count = 0

    for chunk in original_stream:
        now = time.perf_counter()
        if first_chunk_time is None:
            first_chunk_time = now
        last_chunk_time = now
        chunk_count += 1
        yield chunk  # 透传给调用方

    # 流结束后计算指标
    if first_chunk_time and last_chunk_time:
        prefill_ms = (first_chunk_time - start_time) * 1000
        decode_ms = (last_chunk_time - first_chunk_time) * 1000
        tps = output_tokens / (decode_ms / 1000)
```

这就是项目 `StreamMonitor` 的核心思路。理解"在生成器里 yield 前后插逻辑"是关键。

---

### 2.4 实践练习

1. 写带参数装饰器 `@retry(times=3)` 包装同步函数，失败自动重试
2. 定义 `ABC Animal`，子类 `Dog`/`Cat` 实现 `speak()`，试一下不实现会怎样
3. 写一个生成器 `monitor(iterable)`，yield 每个元素时打印时间戳
4. 阅读 [providers/base.py](../sdk/agent_insight_sdk/providers/base.py) 完整代码，画出类图

### 参考资料

- [functools 官方文档](https://docs.python.org/3/library/functools.html) — wraps、update_wrapper
- [PyMOTW functools](https://pymotw.com/3/functools/index.html) — wraps 在装饰器中的作用
- [Python monkey patching](https://ruivieira.dev/python-monkey-patching-for-readability.html) — ABC + abstractmethod 用法

---

## 模块 3：async/await 与 asyncio 并发原语（重点）

### 学习目标

完全看懂 `AsyncBatchUploader` 的后台批量上报循环、`_flush_batch` 指数退避重试。这是整个项目最难也最核心的部分，花最多时间。

---

### 3.1 async/await 基础

#### 什么是协程

`async def` 定义的函数叫协程函数，调用它返回一个**协程对象**，不会立即执行，必须用 `await` 或事件循环驱动：

```python
async def fetch():
    await asyncio.sleep(1)
    return "done"

coro = fetch()       # 没有执行，只是创建了协程对象
result = await coro  # 现在才开始执行
```

#### 事件循环

asyncio 的核心是事件循环——一个单线程调度器。它维护一个就绪协程队列，当某个协程 `await` 一个 I/O 操作时，事件循环切换到其他就绪协程运行，I/O 完成后再回来。这就是"单线程并发"。

#### asyncio.run —— 顶层入口

```python
async def main():
    await asyncio.sleep(1)
    print("done")

asyncio.run(main())  # 创建事件循环、运行、关闭
```

#### 协程 vs 线程

| 维度 | 协程 | 线程 |
|------|------|------|
| 调度 | 协作式（需主动 await 让出） | 抢占式（OS 调度） |
| 切换成本 | 极低（用户态） | 较高（内核态） |
| 并发数 | 可达数万 | 通常数百 |
| GIL | 单线程，无锁问题 | 受 GIL 限制 |

**关键铁律**：async 函数里不能写 `time.sleep()`、`requests.get()` 等阻塞调用，会卡死整个事件循环。必须用 `await asyncio.sleep()`、`await httpx.AsyncClient().get()`。

---

### 3.2 asyncio 并发原语（核心）

#### asyncio.create_task —— 后台调度

`create_task(coro)` 立即把协程包装成 Task 并调度执行，返回 Task 句柄。你可以"先启动，后 await"：

```python
async def main():
    task = asyncio.create_task(work())  # 立即开始后台运行
    # 这里可以做其他事，task 在后台跑
    result = await task  # 等它完成
```

项目里 `AsyncBatchUploader.start()` 就是用它启动后台上报循环：

```python
async def start(self):
    self._task = asyncio.create_task(self._upload_loop())
```

#### task.cancel() 与 CancelledError

`task.cancel()` 会让 Task 在下一个 await 处抛出 `asyncio.CancelledError`。必须在 try/except 里捕获，否则会冒泡：

```python
async def stop(self):
    self._task.cancel()
    try:
        await self._task
    except asyncio.CancelledError:
        pass  # 优雅退出
```

项目 [uploader.py stop()](../sdk/agent_insight_sdk/uploader.py) 就是这个模式。

#### asyncio.gather —— 并发运行多个

`gather(*aws)` 并发运行多个协程，按传入顺序返回结果：

```python
results = await asyncio.gather(
    fetch("a"),
    fetch("b"),
    fetch("c"),
)
# results[0] 是 fetch("a") 的结果
```

`return_exceptions=True` 让某个失败不影响其他：

```python
results = await asyncio.gather(w1(), w2(), w3(), return_exceptions=True)
for r in results:
    if isinstance(r, Exception):
        print(f"failed: {r}")
```

项目 examples 里并发调用多个 LLM 用到它。

#### asyncio.wait_for —— 带超时

`wait_for(aw, timeout)` 超时抛 `asyncio.TimeoutError`：

```python
try:
    result = await asyncio.wait_for(slow_api(), timeout=5.0)
except asyncio.TimeoutError:
    print("超时")
```

项目 `_upload_loop` 用它实现"队列要么有数据、要么超时触发批量刷新"：

```python
try:
    item = await asyncio.wait_for(
        self._queue.get(), timeout=self._flush_interval
    )
    batch.append(item)
except asyncio.TimeoutError:
    pass  # 超时了，去检查要不要刷新
```

#### asyncio.Queue —— 异步队列

`asyncio.Queue` 是异步版的生产者-消费者通道。`put` 和 `get` 都是 awaitable，队列空时 `get` 会挂起，队列满时 `put` 会挂起。

```python
queue = asyncio.Queue(maxsize=100)

# 生产者
await queue.put(item)       # 满了会等待
queue.put_nowait(item)      # 满了立即抛 QueueFull

# 消费者
item = await queue.get()    # 空了会等待
item = queue.get_nowait()   # 空了抛 QueueEmpty
```

项目用有界队列 + `put_nowait` 实现背压保护：

```python
self._queue: asyncio.Queue = asyncio.Queue(maxsize=10000)

async def submit(self, span):
    try:
        self._queue.put_nowait(span.to_dict())
    except asyncio.QueueFull:
        self._dropped += 1  # 满了就丢弃并计数
```

---

### 3.3 异常处理（项目重点）

| 异常 | 何时抛 | 项目处理方式 |
|------|-------|-------------|
| `asyncio.CancelledError` | `task.cancel()` 后在 await 处抛 | `stop()` 里 `try: await self._task except CancelledError: pass` |
| `asyncio.TimeoutError` | `wait_for` 超时 | `_upload_loop` 里 `except TimeoutError: pass` 触发批量刷新 |
| `Exception` 通用 | 网络错误等 | `_flush_batch` 指数退避重试 3 次 |

#### 指数退避重试（项目实现）

```python
async def _flush_batch(self, batch):
    for attempt in range(self.MAX_RETRIES):  # MAX_RETRIES = 3
        try:
            response = await self._client.post(url, json=batch)
            if response.status_code == 202:
                self._sent += len(batch)
                return  # 成功退出
        except Exception as e:
            last_exc = e

        # 指数退避：1s, 2s, 4s
        if attempt < self.MAX_RETRIES - 1:
            delay = 2 ** attempt
            await asyncio.sleep(delay)

    self._failed += len(batch)  # 三次都失败
```

---

### 3.4 生产者-消费者模型（项目架构）

`AsyncBatchUploader` 是教科书级的生产者-消费者：

```
生产者（业务代码）           消费者（后台 Task）
   submit(span) ──put_nowait──> [asyncio.Queue] ──get──> _upload_loop
   （不等上报完成）             （攒批 → HTTP POST → 重试）
        │                                                        │
        └──── 有界 maxsize=10000，满了丢弃并告警（背压） ────────┘
```

- **生产者**：`submit(span)` 用 `put_nowait` 入队，立即返回，不阻塞业务
- **消费者**：`_upload_loop` 后台 Task 循环 `get`，攒满 20 条或超时 500ms 触发 HTTP 上报
- **背压**：`QUEUE_MAXSIZE = 10000`，满则丢弃并计数 `_dropped`，每 100 次告警一次

这种设计让业务调用几乎零开销，上报在后台异步完成。

---

### 3.5 项目核心循环精读

打开 [uploader.py _upload_loop](../sdk/agent_insight_sdk/uploader.py)，逐行理解：

```python
async def _upload_loop(self):
    batch = []
    while self._running:
        try:
            # 尝试从队列取数据，最多等 flush_interval 秒
            try:
                item = await asyncio.wait_for(
                    self._queue.get(), timeout=self._flush_interval
                )
                batch.append(item)
            except asyncio.TimeoutError:
                pass  # 超时不报错，继续往下走

            # 攒满一批就刷新
            if len(batch) >= self._batch_size:
                await self._flush_batch(batch)
                batch = []

        except Exception as e:
            self._logger.error(f"Error in upload loop: {e}")
            await asyncio.sleep(0.1)  # 出错别忙循环

    # 退出前处理剩余
    if batch:
        await self._flush_batch(batch)
```

这个循环融合了：`create_task`（后台运行）+ `wait_for`（超时触发）+ `Queue`（数据通道）+ `CancelledError`（优雅停止）+ 指数退避重试（在 `_flush_batch` 里）。学透这一段，asyncio 基本通关。

---

### 3.6 实践练习

1. 用 `asyncio.Queue` 写一个生产者（每秒产 1 条）+ 消费者（每 3 条处理一次）
2. 给消费者加 `wait_for` 超时，超时后处理已攒数据（模拟项目 `_upload_loop`）
3. 用 `create_task` 启动一个无限循环 Task，5 秒后 `task.cancel()`，用 `try/except CancelledError` 优雅退出
4. 写一个 `fetch_with_retry(url, times=3)`，失败时指数退避 1s/2s/4s
5. 完整抄写 [uploader.py](../sdk/agent_insight_sdk/uploader.py) 的 `_upload_loop` + `_flush_batch`，把 httpx 换成 print 模拟

### 参考资料

- [Coroutines and tasks — Python 官方](https://docs.python.org/3/library/asyncio-task.html) — create_task、gather、CancelledError、timeout
- [asyncio Queue 官方](https://docs.python.org/3/library/asyncio-queue.html) — 生产者-消费者完整示例
- [Python asyncio Illustrated](https://desktechlearn.com/en/study-articles/python-advanced/python-asyncio-tasks/) — gather / Task / Queue 三大模式对比
- [Waiting in asyncio — Hynek](https://hynek.me/articles/waiting-in-asyncio/) — wait_for + gather 组合超时
- [Python Asyncio Complete Guide 2026](https://techoral.com/python/python-asyncio-guide.html) — TaskGroup、return_exceptions

---

## 模块 4：contextvars 异步上下文隔离

### 学习目标

看懂 [context.py](../sdk/agent_insight_sdk/context.py) 为什么用 `ContextVar` 而非全局变量传递 trace_id。这 47 行代码是整个链路追踪的基础。

---

### 4.1 问题背景

在异步并发下，多个请求共用一个事件循环。如果用全局变量存 trace_id：

```python
# ❌ 错误做法
current_trace_id = None

async def handle(req):
    global current_trace_id
    current_trace_id = req.trace_id  # 请求 A 设了
    await db_query()                 # 切换到请求 B，B 也设了
    # 回来时 current_trace_id 已经被 B 覆盖了！
    await report(current_trace_id)   # 串号了
```

`asyncio.gather` 并发跑 5 个请求，全局变量会被互相覆盖。

---

### 4.2 核心知识点

#### ContextVar 声明与使用

```python
from contextvars import ContextVar

# 声明一个上下文变量，带默认值
current_trace: ContextVar[Optional[str]] = ContextVar(
    "current_trace", default=None
)

# 取值
trace_id = current_trace.get()  # None（默认）

# 设值，返回 token 用于恢复
token = current_trace.set("trace-abc")
# ...
current_trace.reset(token)  # 恢复到 set 之前
```

| 方法 | 作用 |
|------|------|
| `var.get()` | 取当前上下文值，无则返回 default |
| `var.set(value)` | 设置值，返回 token |
| `var.reset(token)` | 恢复到 set 之前的值 |
| `copy_context()` | 复制当前上下文（用于跨 Task 传递） |

#### 与 threading.local 的区别

| 维度 | `threading.local` | `ContextVar` |
|------|-------------------|--------------|
| 隔离单位 | 线程 | async Task / 协程 |
| asyncio 支持 | ❌ 一个线程多协程会串 | ✅ 每个 Task 自动独立副本 |
| 推荐场景 | 多线程 | async/await 代码 |

`ContextVar` 是为 asyncio 原生设计的，每个 `create_task` 自动 copy 父上下文，子 Task 的 set 不影响父。

---

### 4.3 项目实现（全文 47 行）

打开 [context.py](../sdk/agent_insight_sdk/context.py)，核心就这几行：

```python
from contextvars import ContextVar

@dataclass
class TraceContext:
    trace_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    span_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    parent_span_id: Optional[str] = None
    name: str = ""

    def create_child(self, name: str) -> "TraceContext":
        return TraceContext(
            trace_id=self.trace_id,           # 子 span 继承 trace_id
            span_id=str(uuid.uuid4()),         # 新 span_id
            parent_span_id=self.span_id,       # 父指向当前
            name=name,
        )

# 上下文变量
_current_context: ContextVar[Optional[TraceContext]] = ContextVar(
    "current_trace_context", default=None
)

def get_current_context() -> Optional[TraceContext]:
    return _current_context.get()

def set_current_context(ctx: TraceContext) -> None:
    _current_context.set(ctx)
```

业务代码这样用：

```python
root = TraceContext(name="agent_task")
set_current_context(root)          # 设根上下文

child = root.create_child("llm_call")
set_current_context(child)         # 切到子上下文

# 在任何深层调用里都能拿到当前上下文
ctx = get_current_context()
print(ctx.trace_id, ctx.span_id)
```

---

### 4.4 进阶坑点

#### create_task 自动 copy context

`asyncio.create_task` 会 copy 当前上下文给新 Task。子 Task 里 `set` 不会影响父：

```python
async def child():
    var.set("child_value")
    print(f"child sees: {var.get()}")

async def main():
    var.set("parent_value")
    t = asyncio.create_task(child())
    await t
    print(f"parent sees: {var.get()}")  # 还是 parent_value
```

项目里 `_report` 用 `asyncio.ensure_future` 提交 span，上下文是独立的，不会串号。

#### FastAPI 中间件层的坑

Starlette/FastAPI 中间件用 `create_task` 调度，中间件层之间上下文不共享。如果要在中间件里 set ContextVar 并让路由读到，需要用 `copy_context()` 手动传递。项目后端目前没踩这个坑，但读代码时要意识到。

---

### 4.5 实践练习

1. 写一个 `request_id: ContextVar[str]`，在 `async def handler(i)` 里 set 不同值（如 `f"req-{i}"`），`gather` 并发跑 5 个，每个内部 `await asyncio.sleep(0.1)` 后打印自己的 request_id，验证互不串号
2. 故意用全局变量 `current_id = None` 替代 ContextVar，复现串号 bug
3. 用 `create_child` 模拟一个三层 span 树，打印每层的 trace_id（应该相同）和 span_id（应该不同）

### 参考资料

- [contextvars 官方文档](https://docs.python.org/3/library/contextvars.html) — ContextVar、copy_context、asyncio 原生支持
- [PyGuides: contextvars 实战](https://pyguides.dev/guides/python-contextvars/) — get/set/reset、与 threading.local 对比、async 隔离示例
- [asyncio Tasks 与 contextvars 链](https://valarmorghulis.io/tech/202408-the-asyncio-tasks-and-contextvars-in-python/) — Task 链中上下文不一致问题 + context 参数

---

## 模块 5：猴子补丁 Monkey Patching

### 学习目标

看懂 `OpenAIInterceptor.patch(client)` 如何在运行时替换 OpenAI SDK 的 `chat.completions.create`，实现"非侵入式"埋点。

---

### 5.1 核心概念

#### 什么是 Monkey Patch

运行时动态替换对象或类的方法，无需修改源码。Python 因为动态特性，随时能给对象属性赋值，这是猴子补丁的基础：

```python
class Foo:
    def bar(self):
        return "original"

f = Foo()
f.bar = lambda: "patched"  # 实例级替换
print(f.bar())             # patched
```

#### 保存原始方法 + 恢复

打补丁前必须保存原始方法，unpatch 时恢复：

```python
original_create = client.chat.completions.create

def patched_create(*args, **kwargs):
    print("before call")
    result = original_create(*args, **kwargs)
    print("after call")
    return result

client.chat.completions.create = patched_create
# ...
client.chat.completions.create = original_create  # 恢复
```

#### functools.wraps 配合

用 `@functools.wraps(original)` 保留原方法的 `__name__`、`__doc__`，方便调试：

```python
import functools

original = client.chat.completions.create

@functools.wraps(original)
def patched(*args, **kwargs):
    # 前置逻辑
    result = original(*args, **kwargs)
    # 后置逻辑
    return result
```

---

### 5.2 项目模式

项目用 `patch` / `unpatch` 显式控制补丁生命周期：

```python
class OpenAIInterceptor:
    def patch(self, client):
        # 保存原始方法
        self._original_create = client.chat.completions.create

        # 替换为包装函数
        @functools.wraps(self._original_create)
        def wrapped(*args, **kwargs):
            perf_start = time.perf_counter()
            is_stream = kwargs.get("stream", False)

            result = self._original_create(*args, **kwargs)

            # 流式响应要包装生成器
            if is_stream:
                result = self._monitor.stream_wrap(result)

            # 上报 span
            self._report(...)

            return result

        client.chat.completions.create = wrapped

    def unpatch(self, client):
        client.chat.completions.create = self._original_create
```

业务代码无感知：

```python
client = OpenAI(api_key="...")
interceptor = OpenAIInterceptor(uploader)
interceptor.patch(client)  # 打桩

# 下面调用和原来一模一样，但自动上报
response = client.chat.completions.create(model="gpt-4", ...)

interceptor.unpatch(client)  # 用完恢复
```

---

### 5.3 与 Adapter 模式结合

项目用 `BaseProviderAdapter` 把不同厂商（OpenAI / Anthropic）的 patch 细节隔离到各自子类。`LLMInterceptor.wrap(client)` 自动识别客户端类型并分发：

```python
class LLMInterceptor:
    def wrap(self, client):
        for adapter in self._adapters:
            if adapter.supports(client):
                return adapter._wrap_call(client, self)
        raise ValueError("未找到匹配的 Adapter")
```

新增厂商（如 Claude）只需写一个 `AnthropicAdapter(BaseProviderAdapter)` 子类并 `register_adapter`，不用动 `LLMInterceptor`。这是开闭原则的典型应用。

---

### 5.4 风险与最佳实践

| 风险 | 说明 | 项目如何规避 |
|------|------|-------------|
| 全局副作用 | patch 后影响所有调用 | 用 wrap/unwrap 显式控制作用域 |
| 难调试 | 调用栈里看不到原始方法 | `functools.wraps` 保留元信息 |
| 延迟 patch 失效 | 模块已 import 时 patch 可能无效 | 项目在客户端创建后立即 patch |
| 线程安全 | 多线程 patch 同一对象有竞态 | 项目是 async 单线程，无此问题 |

---

### 5.5 实践练习

1. 对 `requests.get` 打猴子补丁，每次调用打印 URL，再 unpatch 恢复
2. 对一个类的实例方法打补丁（不是类方法），验证只影响该实例
3. 写一个 `@functools.wraps` 的装饰器，验证 `__name__` 被保留
4. 阅读 [providers/openai_compatible.py](../sdk/agent_insight_sdk/providers/openai_compatible.py) 看真实实现

### 参考资料

- [Python monkey patching for readability](https://ruivieira.dev/python-monkey-patching-for-readability.html) — 动态添加方法、与 ABC 的边界
- [Safely applying monkey patches — wrapt](https://github.com/GrahamDumpleton/wrapt/blob/develop/blog/11-safely-applying-monkey-patches-in-python.md) — 安全打补丁的最佳实践、延迟 patch 陷阱

---

## 模块 6：FastAPI + Pydantic + httpx 异步 Web 栈

### 学习目标

看懂后端 [backend/app](../backend/app/main.py) 的路由注册、参数校验、Kafka 投递，以及 SDK 里用 `httpx.AsyncClient` 上报数据。能独立写一个 collect 接口 + 客户端。

---

### 6.1 FastAPI 异步路由

#### 基本路由

```python
from fastapi import FastAPI

app = FastAPI()

@app.get("/health")
async def health():
    return {"status": "ok"}

@app.post("/api/v1/collect", status_code=202)
async def collect(data: dict):
    # 投递 Kafka 后立即返回 202
    return {"accepted": True}
```

#### async vs sync 路由

| 类型 | 何时用 | 行为 |
|------|-------|------|
| `async def` | I/O 密集（DB、HTTP、Kafka） | 在事件循环里运行 |
| `def` | CPU 密集 | FastAPI 自动放线程池，不阻塞循环 |

**铁律**：async 路由里绝对不能写 `time.sleep()`、`requests.get()` 等阻塞调用。

#### APIRouter 分组

项目把路由按模块拆到 `api/` 下 5 个文件，再用 `APIRouter` 挂载：

```python
from fastapi import APIRouter

router = APIRouter(prefix="/api/v1/traces", tags=["traces"])

@router.get("")
async def list_traces(trace_id: str = None, limit: int = 100):
    ...

# main.py 里注册
app.include_router(traces.router)
app.include_router(collect.router)
# ...
```

#### lifespan 生命周期事件

启动/关闭时执行的逻辑（如初始化 Kafka producer、启动 consumer）：

```python
from contextlib import asynccontextmanager

@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动时
    await kafka_producer.start()
    yield
    # 关闭时
    await kafka_producer.stop()

app = FastAPI(lifespan=lifespan)
```

项目 [main.py](../backend/app/main.py) 用这个模式启动 Kafka 生产者和消费者。

---

### 6.2 Pydantic v2 数据校验

#### BaseModel 定义请求模型

```python
from pydantic import BaseModel, Field
from typing import Literal

class CollectRequest(BaseModel):
    trace_id: str
    span_id: str
    span_type: Literal["trace", "llm_metrics", "prompt", "tool_call", "session"]
    name: str = Field(min_length=1, max_length=200)
    attributes: dict = Field(default_factory=dict)
```

FastAPI 自动用 Pydantic 校验请求体，失败返回 422 + 详细错误。

#### Field 约束

```python
price: float = Field(gt=0, description="价格必须正数")
tags: list[str] = Field(default_factory=list, max_length=10)
email: str = Field(pattern=r'^[\w.+-]+@[\w-]+\.[\w.]+$')
```

#### field_validator 自定义校验

```python
from pydantic import field_validator

class UserCreate(BaseModel):
    password: str

    @field_validator("password")
    @classmethod
    def validate_password(cls, v):
        if len(v) < 8:
            raise ValueError("密码至少 8 位")
        return v
```

#### Literal 限定取值

项目用 `Literal` 限定 `span_type` 只能是 5 种之一，传错直接 422：

```python
span_type: Literal["trace", "llm_metrics", "prompt", "tool_call", "session"]
```

#### Pydantic v2 vs v1

- v2 用 Rust 重写核心，比 v1 快 5~50 倍
- `ConfigDict(from_attributes=True)` 替代 v1 的 `orm_mode`
- `field_validator` 替代 v1 的 `validator`

---

### 6.3 pydantic-settings 配置管理

项目 [config.py](../backend/app/config.py) 用 `BaseSettings` 从环境变量 + `.env` 文件读配置：

```python
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    KAFKA_BOOTSTRAP_SERVERS: str = "localhost:9092"
    CLICKHOUSE_HOST: str = "localhost"
    CLICKHOUSE_PORT: int = 9000
    API_HOST: str = "0.0.0.0"
    API_PORT: int = 8000

    model_config = SettingsConfigDict(env_file=".env")

settings = Settings()  # 自动读环境变量 + .env
```

`.env` 文件示例：

```
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
CLICKHOUSE_HOST=localhost
```

---

### 6.4 httpx.AsyncClient 异步 HTTP 客户端

SDK 用它把 span 批量 POST 到后端。

#### 基本用法

```python
import httpx

async with httpx.AsyncClient(timeout=10.0) as client:
    response = await client.post(
        "http://localhost:8000/api/v1/collect",
        json=[{"trace_id": "..."}, {"trace_id": "..."}],
    )
    print(response.status_code)  # 202
```

#### 连接池复用

`AsyncClient` 内部维护连接池，应该**创建一次复用**，不要每次请求都新建。项目里 `AsyncBatchUploader` 持有一个长期 client：

```python
class AsyncBatchUploader:
    async def start(self):
        self._client = httpx.AsyncClient(timeout=10.0)  # 一次创建

    async def stop(self):
        await self._client.aclose()  # 一次关闭
```

#### 超时与重试

```python
# 超时
client = httpx.AsyncClient(timeout=httpx.Timeout(10.0))

# 重试（需 transport 配置）
transport = httpx.AsyncHTTPTransport(retries=1)
client = httpx.AsyncClient(transport=transport)
```

项目没在 httpx 层重试，而是在业务层 `_flush_batch` 用指数退避手动重试 3 次。

#### 响应处理

```python
response = await client.post(url, json=batch)
if response.status_code == 202:
    # 成功
    ...
else:
    # 失败
    error_text = response.text
```

---

### 6.5 实践练习

1. 用 FastAPI 写一个 `POST /collect` 接口，用 Pydantic 校验 body，返回 202
2. 用 `httpx.AsyncClient` 写一个客户端并发 POST 10 条数据到上面的接口（用 `asyncio.gather`）
3. 用 `pydantic-settings` 从 `.env` 读配置并在 `/info` 接口返回
4. 运行项目后端 `python -m uvicorn app.main:app --reload`，访问 `http://localhost:8000/docs` 看 OpenAPI 文档
5. 把 [collect.py](../backend/app/api/collect.py) 完整抄一遍，理解 Kafka 投递逻辑

### 参考资料

- [FastAPI 官方：Settings 与环境变量](https://fastapi.tiangolo.com/advanced/settings/?h=settings) — BaseSettings 用法
- [Building Type-Safe APIs with FastAPI + Pydantic v2](https://www.gersoncalienes.com/articles/building-type-safe-apis-fastapi-pydantic-v2) — Field、field_validator、async 端点 + httpx 并发
- [FastAPI in Production](https://ilirivezaj.com/guides/fastapi-production-guide) — Pydantic v2 schema 拆分、ConfigDict
- [fastapi-best-practices](https://github.com/zhanymkanov/fastapi-best-practices) — async vs sync 路由、BaseSettings 拆分
- [httpx Async Support 官方](https://www.python-httpx.org/async/) — AsyncClient、流式请求、重试
- [httpx AsyncClient POST 教程](https://proxiesapi.com/articles/using-httpx-s-asyncclient-for-asynchronous-http-post-requests) — 超时、连接池、文件上传

---

## 学习顺序与验收总表

| 阶段 | 模块 | 建议天数 | 验收标准 |
|------|------|---------|---------|
| 1 | 模块 1 类型注解 + dataclass | 1-2 天 | 能默写 `SpanData` dataclass，理解 `field(default_factory)` 和 `__post_init__` |
| 2 | 模块 2 装饰器 + ABC + 生成器 | 1-2 天 | 能写带参异步装饰器 + ABC 子类 + 生成器包装 |
| 3 | 模块 3 async/await（重点） | 2-3 天 | 能默写 `_upload_loop` 生产者-消费者，理解 create_task/gather/wait_for/CancelledError |
| 4 | 模块 4 contextvars | 0.5-1 天 | 能解释为何不用全局变量，会写 ContextVar 隔离 trace_id |
| 5 | 模块 5 猴子补丁 | 0.5-1 天 | 能对 requests 打补丁并恢复，理解 patch/unpatch 生命周期 |
| 6 | 模块 6 FastAPI + Pydantic + httpx | 1-2 天 | 能独立写 collect 接口 + httpx 客户端，会用 pydantic-settings |
| 7 | 直接读项目代码 | 1-2 天 | 逐行读懂 context.py → uploader.py → providers/base.py |

**总投入约 7~13 天**，每天 3~4 小时。模块 3 是重中之重，花最多时间。

---

## 推荐项目代码阅读顺序

学完上述知识后，按以下顺序读项目代码，每个文件都标注了依赖的前置模块：

| 顺序 | 文件 | 难度 | 依赖模块 |
|------|------|------|---------|
| 1 | [sdk/agent_insight_sdk/context.py](../sdk/agent_insight_sdk/context.py) | ⭐ | 1, 4 |
| 2 | [sdk/agent_insight_sdk/uploader.py](../sdk/agent_insight_sdk/uploader.py) | ⭐⭐⭐⭐ | 1, 3, 6 |
| 3 | [sdk/agent_insight_sdk/stream_monitor.py](../sdk/agent_insight_sdk/stream_monitor.py) | ⭐⭐ | 2, 3 |
| 4 | [sdk/agent_insight_sdk/providers/base.py](../sdk/agent_insight_sdk/providers/base.py) | ⭐⭐⭐ | 1, 2, 3, 5 |
| 5 | [sdk/agent_insight_sdk/providers/openai_compatible.py](../sdk/agent_insight_sdk/providers/openai_compatible.py) | ⭐⭐⭐ | 2, 5 |
| 6 | [sdk/agent_insight_sdk/tool_sdk.py](../sdk/agent_insight_sdk/tool_sdk.py) | ⭐⭐ | 2, 3 |
| 7 | [sdk/agent_insight_sdk/trace_api.py](../sdk/agent_insight_sdk/trace_api.py) | ⭐⭐ | 1, 4 |
| 8 | [backend/app/main.py](../backend/app/main.py) | ⭐⭐ | 6 |
| 9 | [backend/app/api/collect.py](../backend/app/api/collect.py) | ⭐⭐ | 6 |
| 10 | [backend/app/kafka/producer.py](../backend/app/kafka/producer.py) | ⭐⭐ | 3, 6 |
| 11 | [backend/app/kafka/consumer.py](../backend/app/kafka/consumer.py) | ⭐⭐⭐ | 3 |
| 12 | [backend/app/clickhouse/client.py](../backend/app/clickhouse/client.py) | ⭐⭐ | - |

每读完一个文件，在对应行打勾。全部读完后，你就能独立修改和扩展这个项目了。
