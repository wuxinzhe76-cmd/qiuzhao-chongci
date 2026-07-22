"""Kafka 生产者测试。

覆盖业务场景：
  - init_producer：构造 AIOKafkaProducer 并 start，缓存到模块全局
  - close_producer：已初始化时 stop 并清空；未初始化时安全 no-op
  - send_batch：未初始化抛 RuntimeError；已初始化走 send() + 回调，不阻塞 handler
  - 回调分发：future 成功 → _on_send_success；future 异常 → _on_send_error（不向上抛）
"""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.kafka import producer
from app.kafka.producer import (
    _on_send_error,
    _on_send_success,
    close_producer,
    init_producer,
    send_batch,
)


@pytest.fixture(autouse=True)
def _reset_producer():
    """每个用例前后都把模块级 _producer 复位为 None，避免互相污染"""
    producer._producer = None
    yield
    producer._producer = None


# ----------------------------- init_producer -----------------------------


@pytest.mark.asyncio
async def test_init_producer_starts_and_caches():
    fake = MagicMock()
    fake.start = AsyncMock()
    with patch("app.kafka.producer.AIOKafkaProducer", return_value=fake) as ctor:
        await init_producer()

    ctor.assert_called_once()
    # 传入关键配置
    _, kwargs = ctor.call_args
    assert kwargs["bootstrap_servers"] == "localhost:9093"
    assert kwargs["acks"] == 1
    assert kwargs["compression_type"] == "gzip"
    fake.start.assert_awaited_once()
    assert producer._producer is fake


@pytest.mark.asyncio
async def test_init_producer_reraises_start_failure():
    """start() 失败时 init_producer 应向上抛异常（不静默吞掉）"""
    fake = MagicMock()
    fake.start = AsyncMock(side_effect=RuntimeError("broker unavailable"))
    with patch("app.kafka.producer.AIOKafkaProducer", return_value=fake):
        with pytest.raises(RuntimeError, match="broker unavailable"):
            await init_producer()


# ----------------------------- close_producer -----------------------------


@pytest.mark.asyncio
async def test_close_producer_stops_and_clears():
    fake = MagicMock()
    fake.stop = AsyncMock()
    producer._producer = fake

    await close_producer()

    fake.stop.assert_awaited_once()
    assert producer._producer is None


@pytest.mark.asyncio
async def test_close_producer_is_noop_when_not_initialized():
    # 未初始化时不应抛异常
    await close_producer()
    assert producer._producer is None


# ----------------------------- send_batch -----------------------------


@pytest.mark.asyncio
async def test_send_batch_raises_when_not_initialized():
    with pytest.raises(RuntimeError, match="not initialized"):
        await send_batch([{"trace_id": "t"}])


@pytest.mark.asyncio
async def test_send_batch_calls_send_and_attaches_callback():
    """send_batch 应调用 send()（非 send_and_wait）并挂回调，handler 路径不阻塞"""
    fut = MagicMock()
    fut.add_done_callback = MagicMock()
    fake = MagicMock()
    fake.send = AsyncMock(return_value=fut)
    producer._producer = fake

    data = [{"trace_id": "t"}, {"trace_id": "u"}]
    # 不应抛异常
    await send_batch(data)

    fake.send.assert_awaited_once()
    args, kwargs = fake.send.call_args
    # send(topic, value=data)
    assert args[0] == "agent-logs"
    assert kwargs["value"] == data
    fut.add_done_callback.assert_called_once()


@pytest.mark.asyncio
async def test_send_batch_callback_dispatches_success_branch():
    """回调中 future.exception() is None → 走 _on_send_success"""
    fut = MagicMock()
    fut.add_done_callback = MagicMock()
    fake = MagicMock()
    fake.send = AsyncMock(return_value=fut)
    producer._producer = fake

    with patch("app.kafka.producer._on_send_success") as ok_cb, \
         patch("app.kafka.producer._on_send_error") as err_cb:
        await send_batch([{"a": 1}])
        # 必须在 patch 生效期间触发回调（回调在模块全局命名空间查找这两个函数）
        callback = fut.add_done_callback.call_args.args[0]
        done_future = MagicMock()
        done_future.exception.return_value = None
        done_future.result.return_value = "meta"
        callback(done_future)

    ok_cb.assert_called_once_with("meta", 1)
    err_cb.assert_not_called()


@pytest.mark.asyncio
async def test_send_batch_callback_dispatches_error_branch():
    """回调中 future.exception() 非 None → 走 _on_send_error，且不向上抛"""
    fut = MagicMock()
    fut.add_done_callback = MagicMock()
    fake = MagicMock()
    fake.send = AsyncMock(return_value=fut)
    producer._producer = fake

    with patch("app.kafka.producer._on_send_success") as ok_cb, \
         patch("app.kafka.producer._on_send_error") as err_cb:
        await send_batch([{"a": 1}, {"b": 2}])
        callback = fut.add_done_callback.call_args.args[0]
        boom = RuntimeError("kafka broker gone")
        done_future = MagicMock()
        done_future.exception.return_value = boom
        # 模拟失败回调不应抛异常
        callback(done_future)

    err_cb.assert_called_once_with(boom, 2)
    ok_cb.assert_not_called()


# ----------------------------- 回调函数自身 -----------------------------


def test_on_send_success_does_not_raise():
    meta = MagicMock(topic="t", partition=0, offset=1)
    _on_send_success(meta, 3)  # 不抛即通过


def test_on_send_error_does_not_raise():
    _on_send_error(RuntimeError("boom"), 3)  # 不抛即通过
