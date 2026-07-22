"""
StreamMonitor / MonitoredStream 单元测试
"""

from unittest.mock import MagicMock

import pytest

from agent_insight_sdk import MonitoredStream, StreamMonitor


class _MockChunk:
    """模拟 OpenAI 流式 chunk"""

    def __init__(self, content: str):
        self.choices = [MagicMock(delta=MagicMock(content=content))]


def test_stream_monitor_metrics():
    monitor = StreamMonitor()
    monitor.record_start()

    # 模拟第一个 chunk
    monitor.record_first_chunk()
    monitor.record_chunk(_MockChunk("hello"))
    monitor.record_chunk(_MockChunk(" world"))

    metrics = monitor.get_metrics()

    assert metrics.prefill_ms >= 0
    assert metrics.decode_ms >= 0
    assert metrics.output_tokens > 0
    assert metrics.tps >= 0


def test_stream_monitor_with_usage():
    monitor = StreamMonitor()
    monitor.record_start()
    monitor.record_first_chunk()

    usage = MagicMock(completion_tokens=42)
    monitor.record_stream_usage(usage)

    metrics = monitor.get_metrics()
    assert metrics.output_tokens == 42


def test_monitored_stream_iteration():
    raw_stream = iter([_MockChunk("a"), _MockChunk("b"), _MockChunk("c")])
    monitor = StreamMonitor()
    monitor.record_start()

    monitored = MonitoredStream(raw_stream, monitor)
    chunks = list(monitored)

    assert len(chunks) == 3
    metrics = monitor.get_metrics()
    assert metrics.output_tokens > 0
    assert metrics.prefill_ms >= 0


def test_monitored_stream_empty():
    raw_stream = iter([])
    monitor = StreamMonitor()
    monitor.record_start()

    monitored = MonitoredStream(raw_stream, monitor)
    chunks = list(monitored)

    assert chunks == []
    metrics = monitor.get_metrics()
    assert metrics.output_tokens == 0


@pytest.mark.asyncio
async def test_monitored_stream_async_iteration():
    """异步迭代 __aiter__/__anext__"""

    class _AsyncStream:
        def __init__(self, chunks):
            self._chunks = chunks
            self._idx = 0

        def __aiter__(self):
            return self

        async def __anext__(self):
            if self._idx >= len(self._chunks):
                raise StopAsyncIteration
            chunk = self._chunks[self._idx]
            self._idx += 1
            return chunk

    raw_stream = _AsyncStream([_MockChunk("a"), _MockChunk("b")])
    monitor = StreamMonitor()
    monitor.record_start()

    monitored = MonitoredStream(raw_stream, monitor)
    chunks = []
    async for chunk in monitored:
        chunks.append(chunk)

    assert len(chunks) == 2
    metrics = monitor.get_metrics()
    assert metrics.output_tokens > 0


def test_monitored_stream_usage_in_last_chunk():
    """最后一个 chunk 带 usage 时应自动提取 output_tokens"""
    chunks = [
        _MockChunk("hello"),
        _MockChunk(" world"),
    ]
    # 给最后一个 chunk 添加 usage
    last_chunk = chunks[-1]
    last_chunk.usage = MagicMock(completion_tokens=42)

    monitor = StreamMonitor()
    monitor.record_start()

    monitored = MonitoredStream(iter(chunks), monitor)
    list(monitored)

    metrics = monitor.get_metrics()
    # usage 覆盖了字符估算
    assert metrics.output_tokens == 42


def test_stream_monitor_no_start_time():
    """未调用 record_start 时 metrics 应全为 0"""
    monitor = StreamMonitor()
    monitor.record_first_chunk()
    monitor.record_chunk(_MockChunk("hello"))

    metrics = monitor.get_metrics()
    # prefill_ms 依赖 _start_time，未 record_start 时应为 0
    assert metrics.prefill_ms == 0.0
