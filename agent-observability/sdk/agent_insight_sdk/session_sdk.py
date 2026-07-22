"""
Session SDK 模块 - 自动采集 Agent 会话完整生命周期

封装一次用户请求（Session）的开始、执行和结束，自动聚合：
- 总 Span 数
- 总 Token 数（input + output）
- 总成本（USD，按内置或自定义单价表计算）
- 总耗时

用法：
    session_sdk = SessionSDK(uploader)

    # 方式1：显式 start/end
    sess = session_sdk.start_session(
        name="user_query_123",
        agent_name="math_agent",
        user_input="帮我计算 2 + 3 * 4",
    )
    # ... agent 执行 ...
    session_sdk.end_session(
        sess,
        final_response="计算结果是 14",
        status="completed",
    )

    # 方式2：上下文管理器
    with session_sdk.session(
        name="user_query_123",
        agent_name="math_agent",
        user_input="帮我计算 2 + 3 * 4",
    ) as sess:
        # ... agent 执行 ...
        pass
"""

import asyncio
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, Optional

from .context import TraceContext, clear_current_context, set_current_context
from .uploader import AsyncBatchUploader, SpanData


# 默认模型单价：USD / 1M tokens
# 价格会随厂商调整，使用者可通过 pricing 参数覆盖
DEFAULT_PRICING: Dict[str, Dict[str, float]] = {
    # OpenAI GPT-5.6 系列（2026-07 发布）
    "gpt-5.6-sol": {"input": 5.00, "output": 30.00},
    "gpt-5.6-terra": {"input": 2.50, "output": 15.00},
    "gpt-5.6-luna": {"input": 1.00, "output": 6.00},
    # OpenAI GPT-5.4 系列（主流生产）
    "gpt-5.4": {"input": 2.50, "output": 15.00},
    "gpt-5.4-mini": {"input": 0.75, "output": 4.50},
    "gpt-5.4-nano": {"input": 0.20, "output": 1.25},
    # OpenAI GPT-4.1 系列（1M 长上下文）
    "gpt-4.1": {"input": 2.00, "output": 8.00},
    "gpt-4.1-mini": {"input": 0.40, "output": 1.60},
    # Anthropic Claude（2026）
    "claude-opus-4-8": {"input": 5.00, "output": 25.00},
    "claude-sonnet-5": {"input": 3.00, "output": 15.00},
    "claude-haiku-4-5": {"input": 1.00, "output": 5.00},
    # DeepSeek
    "deepseek-chat": {"input": 0.14, "output": 0.28},
    "deepseek-reasoner": {"input": 0.55, "output": 2.19},
}


@dataclass
class SessionContext:
    """Session 上下文"""

    session_id: str
    name: str
    agent_name: str
    user_input: str
    start_time: datetime
    trace_context: TraceContext


@dataclass
class _SessionState:
    """Session 运行时聚合状态"""

    context: SessionContext
    total_spans: int = 0
    total_tokens: int = 0
    total_cost_usd: float = 0.0
    perf_start: float = field(default_factory=time.perf_counter)


class SessionSDK:
    """Session 自动埋点 SDK

    自动聚合一次 Agent 会话内的 span、token 和成本，并在会话结束时上报 session span。
    """

    def __init__(
        self,
        uploader: AsyncBatchUploader,
        pricing: Optional[Dict[str, Dict[str, float]]] = None,
    ):
        self._uploader = uploader
        self._pricing = pricing or DEFAULT_PRICING
        self._sessions: Dict[str, _SessionState] = {}
        self._observer_id = uploader.add_observer(self._on_span_submitted)

    def start_session(
        self,
        name: str,
        agent_name: str = "",
        user_input: str = "",
        trace_id: str = "",
    ) -> SessionContext:
        """开始一个新的 Session

        用法：
            sess = session_sdk.start_session(
                name="user_query_123",
                agent_name="math_agent",
                user_input="帮我计算 2 + 3 * 4",
            )
        """
        if trace_id:
            trace_ctx = TraceContext(name=name, trace_id=trace_id)
        else:
            trace_ctx = TraceContext(name=name)
        start_time = datetime.utcnow()
        session_ctx = SessionContext(
            session_id=trace_ctx.trace_id,
            name=name,
            agent_name=agent_name,
            user_input=user_input,
            start_time=start_time,
            trace_context=trace_ctx,
        )

        self._sessions[session_ctx.session_id] = _SessionState(
            context=session_ctx,
            perf_start=time.perf_counter(),
        )

        set_current_context(trace_ctx)
        return session_ctx

    def end_session(
        self,
        session_ctx: SessionContext,
        final_response: str = "",
        status: str = "completed",
    ) -> None:
        """结束 Session 并自动上报聚合后的 session span"""
        state = self._sessions.pop(session_ctx.session_id, None)
        if state is None:
            return

        end_time = datetime.utcnow()
        duration_ms = (time.perf_counter() - state.perf_start) * 1000

        session_span = SpanData(
            trace_id=session_ctx.session_id,
            span_id=session_ctx.session_id,
            parent_span_id=None,
            name="session",
            start_time=session_ctx.start_time.isoformat(),
            end_time=end_time.isoformat(),
            span_type="session",
            session_id=session_ctx.session_id,
            agent_name=session_ctx.agent_name,
            user_input=session_ctx.user_input,
            final_response=final_response,
            total_spans=state.total_spans,
            total_tokens=state.total_tokens,
            total_cost_usd=state.total_cost_usd,
            duration_ms=duration_ms,
            status=status,
        )

        self._submit_span(session_span)
        clear_current_context()

    def _submit_span(self, span: SpanData) -> None:
        """异步提交 span，兼容同步和异步上下文"""
        try:
            loop = asyncio.get_running_loop()
            loop.create_task(self._uploader.submit(span))
        except RuntimeError:
            asyncio.run(self._uploader.submit(span))

    @contextmanager
    def session(
        self,
        name: str,
        agent_name: str = "",
        user_input: str = "",
        trace_id: str = "",
    ):
        """Session 上下文管理器

        用法：
            with session_sdk.session(name="user_query", agent_name="math_agent") as sess:
                # agent 执行
                pass
        """
        sess = self.start_session(
            name=name,
            agent_name=agent_name,
            user_input=user_input,
            trace_id=trace_id,
        )
        try:
            yield sess
        finally:
            self.end_session(sess)

    def close(self) -> None:
        """移除 uploader 观察者，清理资源"""
        self._uploader.remove_observer(self._observer_id)
        self._sessions.clear()

    def _on_span_submitted(self, span_dict: Dict[str, Any]) -> None:
        """Uploader 观察者回调：按 trace_id 聚合 session 内指标"""
        trace_id = span_dict.get("trace_id")
        if not trace_id or trace_id not in self._sessions:
            return

        # session span 自身不参与聚合
        if span_dict.get("span_type") == "session":
            return

        state = self._sessions[trace_id]
        state.total_spans += 1

        attrs = span_dict.get("attributes") or {}
        span_type = span_dict.get("span_type")

        if span_type == "llm_metrics":
            input_tokens = int(attrs.get("input_tokens", 0) or 0)
            output_tokens = int(attrs.get("output_tokens", 0) or 0)
            model_name = attrs.get("model_name", "unknown")

            state.total_tokens += input_tokens + output_tokens
            state.total_cost_usd += self._estimate_cost(
                model_name, input_tokens, output_tokens
            )
        elif span_type == "prompt":
            # prompt span 携带的 token 与 llm_metrics 重复，这里直接忽略；
            # token 与成本统计以 llm_metrics span 为准
            pass

    def _estimate_cost(
        self, model_name: str, input_tokens: int, output_tokens: int
    ) -> float:
        """根据模型单价估算本次调用成本（USD）"""
        price = self._pricing.get(model_name) or self._pricing.get("unknown")
        if price is None:
            return 0.0

        input_cost = input_tokens * price.get("input", 0.0) / 1_000_000
        output_cost = output_tokens * price.get("output", 0.0) / 1_000_000
        return input_cost + output_cost
