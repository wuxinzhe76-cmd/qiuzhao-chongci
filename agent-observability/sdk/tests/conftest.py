"""
测试共享 fixture
"""

from typing import Any, Dict, List

import pytest

from agent_insight_sdk.uploader import SpanData


class FakeUploader:
    """模拟上报器，同步调用观察者，便于单元测试"""

    def __init__(self):
        self.spans: List[Dict[str, Any]] = []
        self._observers: List[Any] = []

    async def submit(self, span: SpanData) -> None:
        d = span.to_dict()
        self.spans.append(d)
        for obs in self._observers:
            if obs:
                obs(d)

    def add_observer(self, callback):
        self._observers.append(callback)
        return len(self._observers) - 1

    def remove_observer(self, observer_id: int) -> None:
        if 0 <= observer_id < len(self._observers):
            self._observers[observer_id] = None


@pytest.fixture
def fake_uploader():
    return FakeUploader()
