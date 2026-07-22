"""
Agent Insight Backend 应用包

FastAPI 应用入口为 app.main:app（uvicorn app.main:app）；本文件中的 app 为历史遗留副本，版本与路由可能滞后于 main.py。
"""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api.collect import router as collect_router
from .api.traces import router as traces_router
from .api.metrics import router as metrics_router
from .kafka.consumer import start_consumer, stop_consumer
from .kafka.producer import init_producer, close_producer

app = FastAPI(
    title="Agent-Insight Backend",
    description="AI Agent 可观测性后端服务",
    version="0.1.0",
)

# CORS 配置
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注册路由
app.include_router(collect_router, prefix="/api/v1")
app.include_router(traces_router, prefix="/api/v1")
app.include_router(metrics_router, prefix="/api/v1")


@app.on_event("startup")
async def startup_event():
    """应用启动时初始化"""
    await init_producer()
    await start_consumer()


@app.on_event("shutdown")
async def shutdown_event():
    """应用关闭时清理"""
    await stop_consumer()
    await close_producer()


@app.get("/health")
async def health_check():
    """健康检查接口"""
    return {"status": "ok"}
