# Agent Insight SDK 单元测试

## 运行方式

```bash
# 在 sdk/ 目录下执行
cd sdk
python -m pytest tests/ -q

# 查看详细输出
python -m pytest tests/ -v

# 运行单个模块
python -m pytest tests/test_session_sdk.py -v

# 跳过慢测试（集成测试）
python -m pytest tests/ -q -k "not integration"
```

## 依赖

```bash
pip install pytest pytest-asyncio httpx
```

## 测试文件一览

| 文件 | 用例数 | 覆盖模块 | 说明 |
|---|---|---|---|
| `conftest.py` | — | — | 共享 fixture：`FakeUploader`（同步收集 span + observer 通知） |
| `test_context.py` | 4 | `context.TraceContext` | trace_id/span_id 生成、父子继承、contextvars 跨 asyncio Task 隔离 |
| `test_stream_monitor.py` | 7 | `stream_monitor.StreamMonitor` / `MonitoredStream` | 指标计算、usage 提取、同步/异步迭代、空流、无 start_time |
| `test_tool_sdk.py` | 7 | `tool_sdk.ToolSDK` | 同步/异步 instrument、错误捕获、默认名称、无父上下文、kwargs 序列化、不可序列化 fallback |
| `test_trace_api.py` | 6 | `trace_api.TraceAPI` | 生命周期、嵌套 span、空上下文安全、end_trace 无 start、自定义 trace_id、end_span 恢复父上下文 |
| `test_uploader.py` | 6 | `uploader.AsyncBatchUploader` | submit+observer、批量 flush、重试退避（mock sleep）、移除 observer、队列满背压、observer 异常隔离 |
| `test_providers.py` | 9 | `providers.base.LLMInterceptor` + OpenAI/Anthropic Adapter | 非流式/流式拦截、异常上报、unwrap 恢复、Anthropic 流式/多模态 prompt、自定义 adapter |
| `test_session_sdk.py` | 8 | `session_sdk.SessionSDK` | 上下文设置、聚合（spans/tokens/cost）、context manager、自定义定价、未知模型 cost=0、未知 session_id 安全、close 停止聚合、并发 session 不串扰 |
| `test_span_data.py` | 6 | `uploader.SpanData.to_dict()` | 各 span_type 字段映射（trace/prompt/tool_call/session）、默认值、parent_span_id 空字符串 |
| `test_integration.py` | 3 | 多模块协作 | 端到端 Session+LLM+Tool 聚合、跨模块 parent_span_id 链路、Session+TraceAPI+ToolSDK 混合 |
| `test_agent_simulation.py` | — | 全链路连通性 | **非 pytest 用例**，是手动运行的端到端脚本，需后端在线，见下方说明 |

**合计：56 个 pytest 用例**

## 覆盖维度

| 维度 | 覆盖情况 |
|---|---|
| **正常路径** | 33 个用例 |
| **错误路径** | 6 个用例（Tool 异常、LLM 异常、Uploader 重试、observer 崩溃） |
| **边界场景** | 17 个用例（空流、无上下文、队列满、未知 ID、并发、不可序列化等） |

## 关于 FakeUploader

测试使用 `conftest.py` 中的 `FakeUploader` 替代真实的 `AsyncBatchUploader`：

- **同步**收集 span 到 `fake_uploader.spans` 列表，无需等待异步 flush
- 同步触发 observer 回调，使 `SessionSDK` 能立即聚合
- 与真实 `AsyncBatchUploader` 接口一致（`submit` / `add_observer` / `remove_observer`）

使用方式：

```python
@pytest.mark.asyncio
async def test_xxx(fake_uploader):
    session_sdk = SessionSDK(fake_uploader)
    # ... 测试逻辑 ...
    await asyncio.sleep(0.05)  # 等待 create_task 调度的 submit 完成
    spans = fake_uploader.spans  # 直接读取已收集的 span
```

## 关于 test_agent_simulation.py

这个文件**不是 pytest 用例**，而是一个手动运行的端到端连通性脚本，用于验证 SDK → 后端 → Kafka → ClickHouse 全链路。运行它需要：

1. 后端服务在线（默认 `http://localhost:8000`）
2. 直接执行 `python tests/test_agent_simulation.py`
3. 查看前端面板 `http://localhost:3000` 确认链路瀑布图

## 已知注意事项

1. **异步时序**：`ToolSDK`、`TraceAPI`、`SessionSDK` 内部用 `loop.create_task` 提交 span，测试中需要 `await asyncio.sleep(0.05~0.1)` 等待任务完成后再断言。

2. **TraceAPI 上下文清理**：`TraceAPI.end_span()` 会清空全局 contextvars 上下文。如果在 `SessionSDK.session()` 内部使用 `TraceAPI`，需要在 `end_span` 后手动恢复 session 上下文（见 `test_session_with_trace_api_and_tool`）。

3. **DeprecationWarning**：源码中使用 `datetime.utcnow()`（36 处），Python 3.12+ 会产生 deprecation warning，不影响功能。后续可统一替换为 `datetime.now(datetime.UTC)`。

4. **MagicMock 自动属性**：测试 Anthropic 客户端时不能用裸 `MagicMock()`（会自动生成 `chat` 属性导致 OpenAI Adapter 先匹配），需用 `_FakeAnthropicClient` 只暴露 `messages.create`。
