"""
异步批量上报器 - 使用 asyncio.Queue 和后台任务实现高效上报
"""

import asyncio
import json
import logging
from dataclasses import asdict, dataclass
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional

import httpx


@dataclass
class SpanData:
    """Span 数据结构

    span_type 取值：
      - "trace"       → agent_traces 表
      - "llm_metrics" → llm_metrics 表
      - "prompt"      → prompt_logs 表
      - "tool_call"   → tool_calls 表
      - "session"     → sessions 表
    """
    trace_id: str
    span_id: str
    parent_span_id: Optional[str] = None
    name: str = ""
    start_time: str = ""
    end_time: str = ""
    span_type: str = "trace"
    attributes: Dict[str, Any] = None

    # prompt 类型专属字段
    model_name: Optional[str] = None
    prompt: Optional[str] = None
    response: Optional[str] = None
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: float = 0.0
    stream: bool = False
    status: str = "success"
    error: str = ""

    # tool_call 类型专属字段
    tool_name: Optional[str] = None
    tool_type: Optional[str] = None
    input_data: Optional[str] = None
    output_data: Optional[str] = None
    duration_ms: float = 0.0

    # session 类型专属字段
    session_id: Optional[str] = None
    agent_name: Optional[str] = None
    user_input: Optional[str] = None
    final_response: Optional[str] = None
    total_spans: int = 0
    total_tokens: int = 0
    total_cost_usd: float = 0.0

    def __post_init__(self):
        if self.attributes is None:
            self.attributes = {}

    def to_dict(self) -> Dict[str, Any]:
        base = {
            "trace_id": self.trace_id,
            "span_id": self.span_id,
            "parent_span_id": self.parent_span_id or "",
            "name": self.name,
            "start_time": self.start_time,
            "end_time": self.end_time,
            "span_type": self.span_type,
            "attributes": self.attributes,
        }
        # prompt 字段
        if self.span_type == "prompt":
            base.update({
                "model_name": self.model_name,
                "prompt": self.prompt,
                "response": self.response,
                "input_tokens": self.input_tokens,
                "output_tokens": self.output_tokens,
                "latency_ms": self.latency_ms,
                "stream": self.stream,
                "status": self.status,
                "error": self.error,
            })
        # tool_call 字段
        if self.span_type == "tool_call":
            base.update({
                "tool_name": self.tool_name,
                "tool_type": self.tool_type,
                "input_data": self.input_data,
                "output_data": self.output_data,
                "duration_ms": self.duration_ms,
                "status": self.status,
                "error": self.error,
            })
        # session 字段
        if self.span_type == "session":
            base.update({
                "session_id": self.session_id,
                "agent_name": self.agent_name,
                "user_input": self.user_input,
                "final_response": self.final_response,
                "total_spans": self.total_spans,
                "total_tokens": self.total_tokens,
                "total_cost_usd": self.total_cost_usd,
                "duration_ms": self.duration_ms,
                "status": self.status,
            })
        return base


class AsyncBatchUploader:
    """异步批量上报器 — 带背压保护 + 指数退避重试"""

    # 最大重试次数
    MAX_RETRIES = 3
    # 有界队列容量（防止 OOM）
    QUEUE_MAXSIZE = 10000

    def __init__(
        self,
        backend_url: str = "http://localhost:8000",
        batch_size: int = 20,
        flush_interval: float = 0.5,
    ):
        self._backend_url = backend_url
        self._batch_size = batch_size
        self._flush_interval = flush_interval
        # 有界队列：超出容量时 put_nowait 抛 QueueFull，由 submit() 捕获后丢弃并告警（调用方不感知）
        self._queue: asyncio.Queue = asyncio.Queue(maxsize=self.QUEUE_MAXSIZE)
        self._running = False
        self._task: Optional[asyncio.Task] = None
        self._client: Optional[httpx.AsyncClient] = None
        self._logger = logging.getLogger(__name__)
        # span 提交观察者，用于 SessionSDK 等模块本地聚合
        self._observers: List[Optional[Callable[[Dict[str, Any]], None]]] = []
        # 统计
        self._dropped = 0
        self._sent = 0
        self._failed = 0

    async def start(self) -> None:
        """启动后台上报任务"""
        if self._running:
            return

        self._running = True
        self._client = httpx.AsyncClient(timeout=10.0)
        self._task = asyncio.create_task(self._upload_loop())
        self._logger.info("AsyncBatchUploader started")

    async def stop(self) -> None:
        """停止后台上报任务"""
        if not self._running:
            return

        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass

        # 刷新剩余数据
        await self._flush()

        if self._client:
            await self._client.aclose()

        self._logger.info("AsyncBatchUploader stopped")

    async def submit(self, span: SpanData) -> None:
        """提交 span 数据到队列（非阻塞，队列满时丢弃并告警）"""
        span_dict = span.to_dict()
        try:
            self._queue.put_nowait(span_dict)
        except asyncio.QueueFull:
            self._dropped += 1
            if self._dropped % 100 == 1:
                self._logger.warning(
                    f"Uploader queue full (capacity={self.QUEUE_MAXSIZE}), "
                    f"dropped={self._dropped}. Consider increasing batch_size or flush_interval."
                )

        # 同步通知观察者，失败不影响主流程
        for observer in self._observers:
            if observer is None:
                continue
            try:
                observer(span_dict)
            except Exception:
                self._logger.exception("Span observer failed")

    def add_observer(self, callback: Callable[[Dict[str, Any]], None]) -> int:
        """注册一个 span 提交观察者，返回 observer_id 用于移除"""
        self._observers.append(callback)
        return len(self._observers) - 1

    def remove_observer(self, observer_id: int) -> None:
        """移除指定观察者"""
        if 0 <= observer_id < len(self._observers):
            self._observers[observer_id] = None

    @property
    def stats(self) -> Dict[str, int]:
        """返回上报统计"""
        return {
            "queue_size": self._queue.qsize(),
            "sent": self._sent,
            "failed": self._failed,
            "dropped": self._dropped,
        }

    async def _upload_loop(self) -> None:
        """后台上报循环"""
        batch: List[Dict[str, Any]] = []

        while self._running:
            try:
                # 尝试从队列获取数据
                try:
                    item = await asyncio.wait_for(
                        self._queue.get(), timeout=self._flush_interval
                    )
                    batch.append(item)
                except asyncio.TimeoutError:
                    pass

                # 仅达到批量阈值时刷新；未达阈值的数据在 stop() 时统一刷新
                if len(batch) >= self._batch_size:
                    await self._flush_batch(batch)
                    batch = []

            except Exception as e:
                self._logger.error(f"Error in upload loop: {e}")
                await asyncio.sleep(0.1)

        # 处理剩余的 batch
        if batch:
            await self._flush_batch(batch)

    async def _flush_batch(self, batch: List[Dict[str, Any]]) -> None:
        """刷新一批数据到后端，失败时指数退避重试"""
        if not batch or not self._client:
            return

        url = f"{self._backend_url}/api/v1/collect"
        last_exc = None

        for attempt in range(self.MAX_RETRIES):
            try:
                response = await self._client.post(url, json=batch)
                if response.status_code == 202:
                    self._sent += len(batch)
                    self._logger.debug(
                        f"Uploaded {len(batch)} spans (attempt {attempt + 1})"
                    )
                    return  # 成功，退出
                else:
                    last_exc = RuntimeError(
                        f"HTTP {response.status_code}: {response.text[:200]}"
                    )
            except Exception as e:
                last_exc = e

            # 指数退避：1s, 2s, 4s
            if attempt < self.MAX_RETRIES - 1:
                delay = 2 ** attempt
                self._logger.warning(
                    f"Upload attempt {attempt + 1} failed, retrying in {delay}s: {last_exc}"
                )
                await asyncio.sleep(delay)

        # 三次重试后仍失败
        self._failed += len(batch)
        self._logger.error(
            f"Upload failed after {self.MAX_RETRIES} attempts, "
            f"discarding {len(batch)} spans: {last_exc}"
        )

    async def _flush(self) -> None:
        """刷新队列中所有剩余数据"""
        batch = []
        while not self._queue.empty():
            try:
                item = self._queue.get_nowait()
                batch.append(item)
            except asyncio.QueueEmpty:
                break

        if batch:
            await self._flush_batch(batch)
