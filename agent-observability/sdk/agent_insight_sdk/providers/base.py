"""
Provider Adapter 抽象层 — 定义多厂商 LLM 拦截的统一接口

设计原则：
  1. 每个 Adapter 不依赖具体 SDK 类型，只约定数据提取接口
  2. 新增大模型厂商只需实现一个 Adapter 子类
  3. LLMInterceptor 通过 supports() 自动识别客户端类型
"""

import asyncio
import logging
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional, Tuple

from ..context import TraceContext, get_current_context, set_current_context
from ..stream_monitor import MonitoredStream, StreamMonitor
from ..uploader import AsyncBatchUploader, SpanData

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# 统一数据结构
# ---------------------------------------------------------------------------

@dataclass
class LLMCallRecord:
    """一次 LLM 调用的完整记录 — 由 Adapter 填充，不依赖具体 SDK"""
    model: str = ""
    provider: str = "unknown"
    is_stream: bool = False
    prefill_ms: float = 0.0
    decode_ms: float = 0.0
    input_tokens: int = 0
    output_tokens: int = 0
    tps: float = 0.0
    prompt_text: str = ""
    response_text: str = ""
    latency_ms: float = 0.0
    error: Optional[str] = None
    extra: Dict[str, Any] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# Adapter 基类
# ---------------------------------------------------------------------------

class BaseProviderAdapter(ABC):
    """LLM Provider 适配器基类

    子类必须实现三个抽象方法：
      - supports(client)   → 是否能处理该客户端
      - _wrap_call(client, interceptor) → 返回包装后的客户端
      - _unwrap_client(wrapped) → 恢复客户端原始方法

    可选重写（带默认实现，适合 OpenAI 兼容格式）：
      - extract(kwargs, response, perf_start, is_stream) → LLMCallRecord
    """

    provider_name: str = "unknown"

    # ------------------------------------------------------------------
    # 子类必须实现
    # ------------------------------------------------------------------

    @abstractmethod
    def supports(self, client: Any) -> bool:
        """判断是否能处理该客户端对象"""
        ...

    @abstractmethod
    def _wrap_call(
        self,
        client: Any,
        interceptor: "LLMInterceptor",
    ) -> Any:
        """返回被拦截包装后的客户端"""
        ...

    @abstractmethod
    def _unwrap_client(self, wrapped: Any) -> None:
        """恢复客户端原始方法"""
        ...

    def extract(
        self,
        kwargs: Dict[str, Any],
        response: Any,
        perf_start: float,
        is_stream: bool,
    ) -> LLMCallRecord:
        """从请求参数 + 响应中提取 LLMCallRecord

        默认实现适合 OpenAI 兼容格式；Anthropic 等需重写
        """
        record = LLMCallRecord(
            model=kwargs.get("model", "unknown"),
            provider=self.provider_name,
            is_stream=is_stream,
            latency_ms=(time.perf_counter() - perf_start) * 1000,
        )

        if not is_stream and response is not None:
            record = self._extract_tokens(response, record)
            record = self._extract_prompt(kwargs, record)
            record = self._extract_response(response, record)
            record.prefill_ms = record.latency_ms * 0.2
            record.decode_ms = record.latency_ms * 0.8

        return record

    # ------------------------------------------------------------------
    # 可复用的 Token / Prompt / Response 提取 (OpenAI 兼容格式)
    # ------------------------------------------------------------------

    def _extract_tokens(self, response: Any, record: LLMCallRecord) -> LLMCallRecord:
        usage = getattr(response, "usage", None)
        if usage:
            record.input_tokens = getattr(usage, "prompt_tokens", 0)
            record.output_tokens = getattr(usage, "completion_tokens", 0)
        if record.latency_ms > 0 and record.output_tokens > 0:
            record.tps = record.output_tokens / (record.latency_ms / 1000.0)
        return record

    def _extract_prompt(self, kwargs: Dict[str, Any], record: LLMCallRecord) -> LLMCallRecord:
        messages = kwargs.get("messages", [])
        try:
            # 只保留用户最后一条消息的文本摘要（避免 Prompt 过长）
            user_msgs = [m.get("content", "") for m in messages if m.get("role") == "user"]
            record.prompt_text = user_msgs[-1] if user_msgs else ""
        except Exception:
            pass
        return record

    def _extract_response(self, response: Any, record: LLMCallRecord) -> LLMCallRecord:
        try:
            record.response_text = response.choices[0].message.content or ""
        except Exception:
            try:
                record.response_text = getattr(response, "content", "")
            except Exception:
                pass
        return record


# ---------------------------------------------------------------------------
# 内置 Adapter 注册表
# ---------------------------------------------------------------------------

_BUILTIN_ADAPTERS: List[BaseProviderAdapter] = []


def register_adapter(adapter: BaseProviderAdapter) -> None:
    """注册一个 Provider Adapter（方便后续扩展）"""
    _BUILTIN_ADAPTERS.append(adapter)


def get_adapters() -> List[BaseProviderAdapter]:
    return list(_BUILTIN_ADAPTERS)


# ---------------------------------------------------------------------------
# 统一拦截器
# ---------------------------------------------------------------------------

class LLMInterceptor:
    """多厂商 LLM 统一拦截器

    用法:
        from openai import OpenAI
        client = OpenAI(api_key="sk-xxx")
        interceptor = LLMInterceptor(uploader)
        wrapped = interceptor.wrap(client)
        # wrapped 和原始 client 接口完全一致，但自动上报 trace

    自动识别:
        - OpenAI 官方 SDK (openai)
        - Anthropic 官方 SDK (anthropic)
        - 任何 OpenAI 兼容接口 (vLLM / DeepSeek / Ollama / Groq / Together 等)
    """

    def __init__(
        self,
        uploader: AsyncBatchUploader,
        adapters: Optional[List[BaseProviderAdapter]] = None,
    ):
        self._uploader = uploader
        self._adapters = adapters or list(_BUILTIN_ADAPTERS)
        self._active_adapter: Optional[BaseProviderAdapter] = None

    # ---- 公开 API ---------------------------------------------------------

    def wrap(self, client: Any) -> Any:
        """自动识别 provider 并拦截客户端"""
        for adapter in self._adapters:
            if adapter.supports(client):
                self._active_adapter = adapter
                wrapped = adapter._wrap_call(client, self)
                self._active_client = wrapped
                return wrapped
        raise ValueError(
            f"未找到匹配的 Provider Adapter，client 类型: {type(client)}。"
            f"已注册 Adapter: {[a.provider_name for a in self._adapters]}"
        )

    def unwrap(self) -> None:
        """恢复原始客户端"""
        if self._active_adapter:
            self._active_adapter._unwrap_client(self._active_client)
            self._active_client = None
            self._active_adapter = None

    # ---- 内部交付报告 -----------------------------------------------------

    def _report(
        self,
        ctx: TraceContext,
        record: LLMCallRecord,
        start_time: datetime,
        end_time: datetime,
    ) -> None:
        """将 LLMCallRecord 转为 SpanData 并提交上报"""
        trace_span = SpanData(
            trace_id=ctx.trace_id,
            span_id=ctx.span_id,
            parent_span_id=ctx.parent_span_id,
            name=ctx.name,
            start_time=start_time.isoformat(),
            end_time=end_time.isoformat(),
            span_type="trace",
            attributes={
                "model": record.model,
                "provider": record.provider,
                "stream": record.is_stream,
            },
        )

        metrics_span = SpanData(
            trace_id=ctx.trace_id,
            span_id=ctx.span_id,
            parent_span_id=ctx.parent_span_id,
            name="llm_metrics",
            start_time=start_time.isoformat(),
            end_time=end_time.isoformat(),
            span_type="llm_metrics",
            attributes={
                "model_name": record.model,
                "provider": record.provider,
                "prefill_ms": record.prefill_ms,
                "decode_ms": record.decode_ms,
                "input_tokens": record.input_tokens,
                "output_tokens": record.output_tokens,
                "tps": record.tps,
            },
        )

        prompt_span = SpanData(
            trace_id=ctx.trace_id,
            span_id=ctx.span_id,
            parent_span_id=ctx.parent_span_id,
            name="prompt_log",
            start_time=start_time.isoformat(),
            end_time=end_time.isoformat(),
            span_type="prompt",
            model_name=record.model,
            prompt=record.prompt_text,
            response=record.response_text,
            input_tokens=record.input_tokens,
            output_tokens=record.output_tokens,
            latency_ms=record.latency_ms,
            stream=record.is_stream,
            status="error" if record.error else "success",
            error=record.error or "",
        )

        self._submit([trace_span, metrics_span, prompt_span])

    def _submit(self, spans: List[SpanData]) -> None:
        """异步提交一批 span"""
        try:
            loop = asyncio.get_running_loop()
            for span in spans:
                loop.create_task(self._uploader.submit(span))
        except RuntimeError:
            async def _submit_all():
                for span in spans:
                    await self._uploader.submit(span)

            asyncio.run(_submit_all())
