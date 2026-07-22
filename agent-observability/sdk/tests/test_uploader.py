"""
AsyncBatchUploader 单元测试
"""

import asyncio
from unittest.mock import AsyncMock, patch

import pytest

from agent_insight_sdk import AsyncBatchUploader
from agent_insight_sdk.uploader import SpanData


@pytest.fixture
async def uploader():
    u = AsyncBatchUploader(backend_url="http://localhost:9999", batch_size=2, flush_interval=60)
    await u.start()
    yield u
    await u.stop()


@pytest.mark.asyncio
async def test_uploader_submit_and_observer():
    uploader = AsyncBatchUploader(backend_url="http://localhost:9999", batch_size=10, flush_interval=60)
    await uploader.start()

    observed = []
    uploader.add_observer(lambda d: observed.append(d["span_type"]))

    await uploader.submit(
        SpanData(
            trace_id="t1",
            span_id="s1",
            name="test",
            start_time="2026-01-01T00:00:00",
            end_time="2026-01-01T00:00:01",
            span_type="trace",
        )
    )

    assert observed == ["trace"]
    assert uploader.stats["queue_size"] == 1

    await uploader.stop()


@pytest.mark.asyncio
async def test_uploader_batch_flush():
    uploader = AsyncBatchUploader(backend_url="http://localhost:9999", batch_size=2, flush_interval=60)

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
        mock_post.return_value.status_code = 202
        await uploader.start()

        for i in range(3):
            await uploader.submit(
                SpanData(
                    trace_id=f"t{i}",
                    span_id=f"s{i}",
                    name=f"span{i}",
                    start_time="2026-01-01T00:00:00",
                    end_time="2026-01-01T00:00:01",
                    span_type="trace",
                )
            )

        # 等待批量刷新
        await asyncio.sleep(0.3)

        # batch_size=2，3 条数据会触发一次 flush(2) + 剩余(1) 在 stop 时 flush
        await uploader.stop()

        assert mock_post.call_count >= 1
        # 每次调用应该发送 JSON 数组
        args, kwargs = mock_post.call_args
        assert isinstance(kwargs["json"], list)


@pytest.mark.asyncio
async def test_uploader_retry_and_drop():
    uploader = AsyncBatchUploader(backend_url="http://localhost:9999", batch_size=1, flush_interval=60)

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post, \
         patch("asyncio.sleep", new_callable=AsyncMock) as mock_sleep:
        mock_post.return_value.status_code = 500
        await uploader.start()

        await uploader.submit(
            SpanData(
                trace_id="t1",
                span_id="s1",
                name="test",
                start_time="2026-01-01T00:00:00",
                end_time="2026-01-01T00:00:01",
                span_type="trace",
            )
        )

        # mock sleep 后重试瞬间完成
        await asyncio.sleep(0.1)
        await uploader.stop()

        # 最多重试 3 次
        assert mock_post.call_count == 3
        assert uploader.stats["failed"] == 1


@pytest.mark.asyncio
async def test_uploader_remove_observer():
    uploader = AsyncBatchUploader(backend_url="http://localhost:9999", batch_size=10, flush_interval=60)
    await uploader.start()

    observed = []
    oid = uploader.add_observer(lambda d: observed.append(d))
    uploader.remove_observer(oid)

    await uploader.submit(
        SpanData(
            trace_id="t1",
            span_id="s1",
            name="test",
            start_time="2026-01-01T00:00:00",
            end_time="2026-01-01T00:00:01",
            span_type="trace",
        )
    )

    assert len(observed) == 0
    await uploader.stop()


@pytest.mark.asyncio
async def test_uploader_queue_full_backpressure():
    """队列满时 submit 应丢弃并增加 dropped 计数"""
    # queue_maxsize 固定为 10000，用未 start 的 uploader 避免 drain
    uploader = AsyncBatchUploader(backend_url="http://localhost:9999", batch_size=100, flush_interval=60)
    # 手动塞满队列
    for i in range(uploader.QUEUE_MAXSIZE):
        uploader._queue.put_nowait({"idx": i})

    assert uploader.stats["dropped"] == 0

    # 再 submit 一条应触发 QueueFull
    await uploader.submit(
        SpanData(
            trace_id="overflow",
            span_id="overflow",
            name="overflow",
            span_type="trace",
        )
    )

    assert uploader.stats["dropped"] == 1


@pytest.mark.asyncio
async def test_uploader_observer_exception_isolation():
    """observer 抛异常不应影响 submit 主流程和后续 observer"""
    uploader = AsyncBatchUploader(backend_url="http://localhost:9999", batch_size=10, flush_interval=60)
    await uploader.start()

    good_observed = []

    def bad_observer(d):
        raise RuntimeError("observer crashed")

    def good_observer(d):
        good_observed.append(d["span_type"])

    uploader.add_observer(bad_observer)
    uploader.add_observer(good_observer)

    # submit 不应抛异常
    await uploader.submit(
        SpanData(
            trace_id="t1",
            span_id="s1",
            name="test",
            start_time="2026-01-01T00:00:00",
            end_time="2026-01-01T00:00:01",
            span_type="trace",
        )
    )

    # 第二个 observer 仍然被调用
    assert good_observed == ["trace"]
    assert uploader.stats["queue_size"] == 1

    await uploader.stop()
