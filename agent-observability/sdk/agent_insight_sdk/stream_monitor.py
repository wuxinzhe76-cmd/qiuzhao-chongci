"""
流式响应监控模块 - 拦截多厂商 LLM 流式响应，计算 prefill_ms 和 decode_ms
"""

import time
from dataclasses import dataclass
from typing import Any, Iterator, Optional


@dataclass
class StreamMetrics:
    """流式响应指标"""
    prefill_ms: float = 0.0  # 首字耗时（毫秒）
    decode_ms: float = 0.0   # 解码总耗时（毫秒）
    output_tokens: int = 0   # 输出 token 数
    tps: float = 0.0         # 每秒 token 吞吐量

    def calculate_tps(self) -> float:
        """计算 TPS = Output Tokens / Decode Time (s)"""
        if self.decode_ms > 0:
            self.tps = self.output_tokens / (self.decode_ms / 1000.0)
        return self.tps


class StreamMonitor:
    """流式响应监控器"""

    def __init__(self):
        self._first_chunk_time: Optional[float] = None
        self._last_chunk_time: Optional[float] = None
        self._start_time: Optional[float] = None
        self._output_tokens: int = 0

    def record_start(self) -> None:
        """记录流式开始时间"""
        self._start_time = time.perf_counter()

    def record_first_chunk(self) -> None:
        """记录第一个 chunk 时间（用于计算 prefill_ms）"""
        if self._first_chunk_time is None:
            self._first_chunk_time = time.perf_counter()

    def record_chunk(self, chunk: Any) -> None:
        """记录每个 chunk"""
        current_time = time.perf_counter()
        self._last_chunk_time = current_time

        if self._first_chunk_time is None:
            self._first_chunk_time = current_time

        # 尝试从 chunk 中提取 token 信息
        if hasattr(chunk, "choices") and chunk.choices:
            choice = chunk.choices[0]
            if hasattr(choice, "delta") and hasattr(choice.delta, "content"):
                content = choice.delta.content
                if content:
                    # 简单估算：按字符数 / 4 估算 token 数
                    self._output_tokens += max(1, len(content) // 4)

    def record_stream_usage(self, usage: Any) -> None:
        """从流式响应的 usage 字段记录 token 数"""
        if usage and hasattr(usage, "completion_tokens"):
            self._output_tokens = usage.completion_tokens

    def get_metrics(self) -> StreamMetrics:
        """获取流式指标"""
        metrics = StreamMetrics()

        if self._start_time and self._first_chunk_time:
            metrics.prefill_ms = (self._first_chunk_time - self._start_time) * 1000

        if self._first_chunk_time and self._last_chunk_time:
            metrics.decode_ms = (self._last_chunk_time - self._first_chunk_time) * 1000

        metrics.output_tokens = self._output_tokens
        metrics.calculate_tps()

        return metrics


class MonitoredStream:
    """包装后的流式响应，自动记录指标（同时支持同步和异步迭代）"""

    def __init__(self, stream, monitor: StreamMonitor):
        self._stream = stream
        self._monitor = monitor
        self._is_first = True

    def __iter__(self):
        return self

    def __next__(self):
        try:
            chunk = next(self._stream)

            if self._is_first:
                self._monitor.record_first_chunk()
                self._is_first = False

            self._monitor.record_chunk(chunk)

            # 检查是否有 usage 信息（某些 API 在最后一个 chunk 返回）
            if hasattr(chunk, "usage") and chunk.usage:
                self._monitor.record_stream_usage(chunk.usage)

            return chunk
        except StopIteration:
            raise

    def __aiter__(self):
        return self

    async def __anext__(self):
        try:
            chunk = await self._stream.__anext__()

            if self._is_first:
                self._monitor.record_first_chunk()
                self._is_first = False

            self._monitor.record_chunk(chunk)

            if hasattr(chunk, "usage") and chunk.usage:
                self._monitor.record_stream_usage(chunk.usage)

            return chunk
        except StopAsyncIteration:
            raise
