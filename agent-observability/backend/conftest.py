"""后端 pytest 共享配置与 fixture。

放置于 backend/ 根目录，保证 `app` 包在任意 CWD 下均可被导入。
所有外部依赖（Kafka / ClickHouse）均通过 mock 隔离，单测无需真实中间件。
"""

import os
import sys

# 把 backend/ 加入 sys.path，使 `import app.xxx` 在 `pytest` 直接调用时也成立
_BACKEND_ROOT = os.path.dirname(os.path.abspath(__file__))
if _BACKEND_ROOT not in sys.path:
    sys.path.insert(0, _BACKEND_ROOT)

import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.main import app


@pytest_asyncio.fixture
async def api_client():
    """直连 FastAPI ASGI 应用的异步 HTTP 客户端（不走网络）。

    - 不触发 lifespan，因此 Kafka producer / consumer 不会被启动；
    - 各用例按需 patch `send_batch` / `query_*` 等依赖即可。
    """
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
