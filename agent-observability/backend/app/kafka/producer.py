"""
Kafka 生产者 - 用于将 SDK 上报的数据投递到 Kafka

设计要点：
  - send_and_wait() 会阻塞事件循环，高并发下退化为串行
  - 改用 send() + add_done_callback，FastAPI handler 立即返回
  - batch send 的 Kafka 投递错误只记录日志（数据已由 SDK 重试兜底）；producer 未初始化时仍会抛 RuntimeError
"""

import json
import logging
from typing import Any, Dict, List

from aiokafka import AIOKafkaProducer

from ..config import settings

logger = logging.getLogger(__name__)

_producer: AIOKafkaProducer = None


async def init_producer() -> None:
    """初始化 Kafka 生产者"""
    global _producer
    try:
        _producer = AIOKafkaProducer(
            bootstrap_servers=settings.kafka_bootstrap_servers,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            acks=1,
            max_batch_size=16384,
            linger_ms=10,
            compression_type="gzip",  # 压缩减少网络 IO 70%+
        )
        await _producer.start()
        logger.info(f"Kafka producer started, servers: {settings.kafka_bootstrap_servers}")
    except Exception as e:
        logger.error(f"Failed to start Kafka producer: {e}")
        raise


async def close_producer() -> None:
    """关闭 Kafka 生产者"""
    global _producer
    if _producer:
        await _producer.stop()
        _producer = None
        logger.info("Kafka producer stopped")


def _on_send_success(metadata, batch_size: int) -> None:
    """发送成功回调"""
    logger.debug(
        f"Kafka send OK: topic={metadata.topic}, partition={metadata.partition}, "
        f"offset={metadata.offset}, records={batch_size}"
    )


def _on_send_error(exc, batch_size: int) -> None:
    """发送失败回调（仅记录日志，SDK 侧有重试机制兜底）"""
    logger.error(
        f"Kafka send FAILED after async write, records={batch_size}: {exc}"
    )


async def send_batch(data: List[Dict[str, Any]]) -> None:
    """
    投递一批数据到 Kafka（非阻塞）

    - 使用 send() 而非 send_and_wait()
    - 回调中记录成功/失败，不阻塞 FastAPI handler
    - Kafka 投递错误仅记录日志（数据由 SDK 侧重试兜底）；但 producer 未初始化时会
      raise RuntimeError，调用方（collect.py）已用 try/except 兜底
    """
    if not _producer:
        raise RuntimeError("Kafka producer not initialized")

    batch_size = len(data)
    fut = await _producer.send(
        settings.kafka_topic,
        value=data,
    )
    fut.add_done_callback(
        lambda f: (
            _on_send_success(f.result(), batch_size)
            if f.exception() is None
            else _on_send_error(f.exception(), batch_size)
        )
    )
