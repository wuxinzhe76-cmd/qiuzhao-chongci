# Agent 可观测性后端 · 学习笔记 07：API 层 + 异常处理对比

> 📅 学习日期：2026-06-29
> 🎯 目标：理解 4 个查询接口的套路、Query 参数、统一响应、Python vs Java 异常处理
> 👤 背景：Java 后端转 AI Agent，对照 Spring Boot 理解 FastAPI

---

## API 层文件概述

4 个查询 API 文件，套路完全一样：

| 文件 | 接口 | 调用的 client.py 函数 |
|------|------|---------------------|
| traces.py | GET /traces + GET /sessions | query_traces / query_sessions |
| metrics.py | GET /metrics/compare | query_metrics_compare |
| prompts.py | GET /prompts + GET /tool-calls | query_prompts / query_tool_calls |
| leaderboard.py | GET /leaderboard | query_leaderboard |

加上 collect.py 的 POST /collect，一共 7 个接口。

---

## 一、共同套路（4 个文件一模一样）

```python
# ① 导入
from fastapi import APIRouter, Query
from ..clickhouse.client import query_xxx

router = APIRouter()

# ② 定义接口
@router.get("/xxx")
async def get_xxx(
    参数: 类型 = Query(默认值, description="描述"),
):
    try:
        data = await query_xxx(参数)
        return {"status": "success", "count": len(data), "data": data}
    except Exception as e:
        logger.error(...)
        return {"status": "error", "message": str(e), "data": []}
```

### Java 等价

```java
@RestController
@RequestMapping("/api/v1")
public class TraceController {

    @Autowired
    private TraceService traceService;

    @GetMapping("/traces")
    public ResponseEntity<Map> getTraces(
        @RequestParam(required = false) String traceId,
        @RequestParam(defaultValue = "100") int limit
    ) {
        try {
            List<Map> traces = traceService.queryTraces(traceId, limit);
            return ResponseEntity.ok(Map.of("status", "success", "data", traces));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "error", "data", List.of()));
        }
    }
}
```

---

## 二、Query 参数详解

```python
trace_id: Optional[str] = Query(None, description="指定 trace_id 查询单条链路")
limit: int = Query(100, ge=1, le=1000, description="返回数量限制")
```

### Query = @RequestParam

```
GET /traces?trace_id=abc&limit=50
              ↑         ↑
         Query 取 abc   Query 取 50
```

### Query 的参数

| 参数 | 作用 | 例子 |
|------|------|------|
| 第一个值 | 默认值 | None / 100 |
| description | API 文档描述 | Swagger UI 显示 |
| ge | 最小值（greater than or equal） | ge=1 |
| le | 最大值（less than or equal） | le=1000 |

### 参数校验

FastAPI 自动校验：
- 传 limit=0 → 校验失败（ge=1）→ 返回 422
- 传 limit=5000 → 校验失败（le=1000）→ 返回 422

Java 类比：`@Min(1) @Max(1000) int limit`

---

## 三、4 个文件的区别

### 1. traces.py（链路 + 会话查询）

- GET /traces：不传 trace_id 返回最近链路，传 trace_id 返回完整 span 树
- GET /sessions：按 agent_name 筛选会话

### 2. metrics.py（多模型效能对比）

```python
# 逗号分隔转列表
model_names = [m.strip() for m in models.split(",")]
# "gpt-4,claude-3-opus" → ["gpt-4", "claude-3-opus"]
```

Java 类比：`Arrays.stream(models.split(",")).map(String::trim).collect(...)`

### 3. prompts.py（Prompt + Tool 调用查询）

- GET /prompts：看"用户问了什么 + LLM 答了什么"（Replay 回放）
- GET /tool-calls：看"Agent 调了什么工具 + 入参出参"

### 4. leaderboard.py（排行榜，3 种指标）

| metric 值 | 查什么 | 用途 |
|-----------|--------|------|
| slowest_tool | 最慢工具 | 优化哪个工具 |
| most_tokens | 最费 token 的模型 | 控成本 |
| most_failed | 失败最多的工具 | 修 bug |

---

## 四、统一响应格式

```json
{
    "status": "success",
    "count": 50,
    "data": [...]
}
```

Java 类比：统一响应体 `Result<T>`。

---

## 五、核心铁律

1. **4 个文件套路一样**：导入 → router → @router.get → try/except → 统一响应
2. **Query = @RequestParam**：从 URL 查询参数取值
3. **ge/le 校验**：FastAPI 自动校验，失败返回 422
4. **API 层是薄层**：只接收参数 + 调 client.py，不写业务逻辑
5. **逗号分隔转列表**：models.split(",") + 列表推导式
6. **查询失败返回空列表**：不抛异常，返回 {"status": "error", "data": []}

---

## 六、🔴 Python vs Java 异常处理对比（工业级）

### 本项目的做法（简单，不推荐生产）

```python
@router.get("/traces")
async def get_traces(trace_id: str):
    try:
        traces = await query_traces(trace_id)
        return {"status": "success", "data": traces}
    except Exception as e:
        return {"status": "error", "data": []}
```

**问题**：
- 没有 HTTPException，错误用 200 返回（应该用 4xx/5xx）
- 没有错误码，只有 "error" 字符串
- 异常被吞了（只记日志，不区分业务异常和系统异常）
- 没有全局异常处理器

### Java 工业级标准做法

```java
// 1. 错误码枚举
public enum ErrorCode {
    TRACE_NOT_FOUND(40401, "Trace not found"),
    INVALID_PARAM(40001, "Invalid parameter"),
    INTERNAL_ERROR(50000, "Internal error");
}

// 2. 自定义异常
public class BusinessException extends RuntimeException {
    private ErrorCode errorCode;
}

// 3. 统一响应体
public class Result<T> {
    private int code;
    private String message;
    private T data;
    public static <T> Result<T> success(T data) {...}
    public static Result<Void> error(ErrorCode errorCode) {...}
}

// 4. 全局异常处理器
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.error(e.getErrorCode());
    }
}

// 5. Controller 用
@GetMapping("/traces")
public Result<List<Trace>> getTraces(@RequestParam String traceId) {
    List<Trace> traces = traceService.query(traceId);
    return Result.success(traces);
}
```

### Python 工业级标准做法（跟 Java 完全对应）

```python
# 1. 错误码枚举（Python 用 Enum）
from enum import Enum

class ErrorCode(Enum):
    TRACE_NOT_FOUND = (40401, "Trace not found")
    INVALID_PARAM = (40001, "Invalid parameter")
    INTERNAL_ERROR = (50000, "Internal error")

# 2. 自定义异常
class BusinessException(Exception):
    def __init__(self, error_code: ErrorCode):
        self.error_code = error_code

# 3. 统一响应体（Pydantic Model）
from pydantic import BaseModel

class Result(BaseModel, Generic[T]):
    code: int
    message: str
    data: Optional[T] = None

    @classmethod
    def success(cls, data: T = None) -> "Result[T]":
        return cls(code=200, message="success", data=data)

    @classmethod
    def error(cls, error_code: ErrorCode) -> "Result":
        return cls(code=error_code.code, message=error_code.message, data=None)

# 4. 全局异常处理器（= Java @RestControllerAdvice）
@app.exception_handler(BusinessException)
async def business_exception_handler(request: Request, exc: BusinessException):
    return JSONResponse(
        status_code=200,
        content=Result.error(exc.error_code).dict()
    )

@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    return JSONResponse(
        status_code=500,
        content=Result.error(ErrorCode.INTERNAL_ERROR).dict()
    )

# 5. Controller 用
@router.get("/traces")
async def get_traces(trace_id: str):
    traces = await query_traces(trace_id)
    if not traces:
        raise BusinessException(ErrorCode.TRACE_NOT_FOUND)
    return Result.success(traces)
```

### 完整对应表

| 概念 | Java | Python |
|------|------|--------|
| 错误码 | `enum ErrorCode` | `class ErrorCode(Enum)` |
| 业务异常 | `BusinessException extends RuntimeException` | `class BusinessException(Exception)` |
| 统一响应 | `Result<T>` | `Result(BaseModel, Generic[T])` |
| 全局处理 | `@RestControllerAdvice` + `@ExceptionHandler` | `@app.exception_handler` |
| 成功返回 | `Result.success(data)` | `Result.success(data)` |
| 抛异常 | `throw new BusinessException` | `raise BusinessException` |

### 机制完全一样

```
Controller
  ↓ raise/throw BusinessException
异常处理器拦截（@app.exception_handler / @RestControllerAdvice）
  ↓ 转成 Result.error(ErrorCode)
返回 JSON
```

---

## 七、🔴 Service 层：Python 也有分层

### 本项目没有 Service 层（规模小）

```
API 层（traces.py）
  ↓ 直接调
ClickHouse 客户端（client.py）
```

### 工业级 Python 项目也分层

```python
# Controller 层（api/traces.py）
@router.get("/traces")
async def get_traces(trace_id: str, trace_service: TraceService = Depends()):
    return trace_service.get_traces(trace_id)

# Service 层（service/trace_service.py）
class TraceService:
    def __init__(self, trace_repo: TraceRepository):
        self.trace_repo = trace_repo

    async def get_traces(self, trace_id: str):
        traces = await self.trace_repo.find_by_id(trace_id)
        return self._build_tree(traces)  # 业务逻辑

# Repository 层（repository/trace_repo.py）
class TraceRepository:
    async def find_by_id(self, trace_id: str):
        return await query_traces(trace_id=trace_id)
```

### 三层对应表

| 层 | Java | Python |
|----|------|--------|
| Controller | `@RestController` | `@router.get` |
| Service | `@Service` + `@Autowired` | `Depends()` 注入 |
| Repository | `@Repository` / MyBatis Mapper | 直接调 client.py |

**本项目没分 Service 层是因为规模小，不是 Python 不分层。**

---

## 八、本项目的做法 vs 工业级

| 对比 | 本项目 | 工业级 |
|------|--------|--------|
| 错误码 | 无，用 "success"/"error" 字符串 | ErrorCode(Enum) |
| 异常类型 | HTTPException + Exception | BusinessException + SystemException |
| 全局处理 | 无，每个接口自己 try/except | @app.exception_handler |
| 响应格式 | {"status": "success", "data": []} | Result(code, message, data) |
| 错误 HTTP 状态码 | 不用，全 200 | 4xx/5xx |
| Service 层 | 无 | 有 |

**本项目是"够用就行"，工业级要规范得多。**

---

## Python vs Java 语法对照

| 概念 | Python | Java |
|------|--------|------|
| 查询参数 | `Query(None, description="...")` | `@RequestParam(required=false)` |
| 参数校验 | `Query(100, ge=1, le=1000)` | `@Min(1) @Max(1000)` |
| 路由 | `@router.get("/traces")` | `@GetMapping("/traces")` |
| 异常抛出 | `raise HTTPException` | `throw new RuntimeException` |
| 全局异常处理 | `@app.exception_handler` | `@RestControllerAdvice` |
| 错误码 | `class ErrorCode(Enum)` | `enum ErrorCode` |
| 统一响应 | `Result(BaseModel)` | `Result<T>` |
| 依赖注入 | `Depends()` | `@Autowired` |
