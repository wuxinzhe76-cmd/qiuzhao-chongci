"""
Agent Insight SDK - AI Agent 可观测性探针 SDK

提供非侵入式的 LLM 调用拦截、链路追踪和性能指标采集能力。

核心功能：
- TraceContext: 上下文管理 (contextvars)
- LLMInterceptor: 多厂商 LLM 统一拦截（OpenAI / Anthropic / DeepSeek / vLLM / Ollama 等）
- StreamMonitor: 流式响应监控 (prefill/decode/TPS)
- SessionSDK: Session 生命周期自动聚合
- ToolSDK: Tool 调用自动埋点
- TraceAPI: start_trace/start_span/end_span/end_trace 显式 API
- AsyncBatchUploader: 异步批量上报

Provider 扩展：继承 BaseProviderAdapter 即可接入新厂商的 LLM SDK
"""

from .context import TraceContext, get_current_context, set_current_context, clear_current_context
from .providers import LLMInterceptor, BaseProviderAdapter, LLMCallRecord, register_adapter
from .stream_monitor import StreamMonitor, MonitoredStream
from .session_sdk import SessionContext, SessionSDK
from .tool_sdk import ToolSDK
from .trace_api import TraceAPI
from .uploader import AsyncBatchUploader, SpanData

# 为保持历史兼容，保留 OpenAIInterceptor 别名
from .providers.openai_compatible import OpenAICompatibleAdapter

OpenAIInterceptor = LLMInterceptor  # deprecated alias

__version__ = "0.3.0"
__all__ = [
    # 上下文管理
    "TraceContext",
    "get_current_context",
    "set_current_context",
    "clear_current_context",
    # LLM 拦截（多厂商统一入口）
    "LLMInterceptor",
    "BaseProviderAdapter",
    "LLMCallRecord",
    "register_adapter",
    "OpenAICompatibleAdapter",
    # 历史兼容
    "OpenAIInterceptor",
    # 流式监控
    "StreamMonitor",
    "MonitoredStream",
    # Session SDK
    "SessionSDK",
    "SessionContext",
    # Tool SDK
    "ToolSDK",
    # Trace API
    "TraceAPI",
    # 上报器
    "AsyncBatchUploader",
    "SpanData",
]
