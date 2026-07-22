"""
FastAPI 后端应用入口
"""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api.collect import router as collect_router
from .api.traces import router as traces_router
from .api.metrics import router as metrics_router
from .api.prompts import router as prompts_router
from .api.leaderboard import router as leaderboard_router
from .kafka.consumer import start_consumer, stop_consumer
from .kafka.producer import init_producer, close_producer

app = FastAPI(
    title="Agent-Insight Backend",
    description="AI Agent 可观测性后端服务",
    version="0.3.0",
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
app.include_router(prompts_router, prefix="/api/v1")
app.include_router(leaderboard_router, prefix="/api/v1")


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
