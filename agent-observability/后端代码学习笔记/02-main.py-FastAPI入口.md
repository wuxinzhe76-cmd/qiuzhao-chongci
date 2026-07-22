# Agent 可观测性后端 · 学习笔记 02：main.py（FastAPI 入口）

> 📅 学习日期：2026-06-29
> 🎯 目标：逐块理解 FastAPI 应用入口的每个组成部分
> 👤 背景：Java 后端转 AI Agent，对照 Spring Boot 理解 FastAPI

---

## 文件概述

`main.py` 是整个后端的**入口文件**，类比 Java Spring Boot 的启动类（带 `@SpringBootApplication` 的 main 方法）。

启动命令：
```bash
uvicorn app.main:app --reload
# 意思：在 app.main 模块里找名为 app 的变量，启动它
```

完整代码 56 行，拆成 6 块讲。

---

## 块 1：导入部分

```python
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api.collect import router as collect_router
from .api.traces import router as traces_router
from .api.metrics import router as metrics_router
from .api.prompts import router as prompts_router
from .api.leaderboard import router as leaderboard_router
from .kafka.consumer import start_consumer, stop_consumer
from .kafka.producer import init_producer, close_producer
```

导入两类东西：
- **5 个 router**（路由，定义 API 接口）—— 类比 Spring 的 `@RestController`
- **4 个生命周期函数**（管 Kafka 启停）

### 相对导入

`from .api.collect` 的 `.` 代表"当前包"（当前目录）。`.api.collect` = 当前目录的 api 子目录里的 collect 模块。

### Router vs Controller

| 对比 | Spring Boot Controller | FastAPI Router |
|------|----------------------|----------------|
| 载体 | 一个**类**(Class) | 一个 `APIRouter` **对象** |
| 定义接口 | 方法上加 `@GetMapping` | 函数上加 `@router.get` |
| 组织单位 | 按类聚合 | 按模块聚合 |

### Spring 自动扫描 vs FastAPI 手动注册

- **Spring**：`@ComponentScan` 自动扫 `@RestController` 注解的类，自动注册
- **FastAPI**：没有自动扫描，必须手动 `import` + `include_router`

Python 生态喜欢显式，Java 生态喜欢约定优于配置。

---

## 块 2：创建 app 实例

```python
app = FastAPI(
    title="Agent-Insight Backend",
    description="AI Agent 可观测性后端服务",
    version="0.3.0",
)
```

创建 FastAPI 应用实例。`app` 是整个后端的核心对象，路由、中间件、生命周期事件都挂在它身上。

### 三个参数的作用

| 参数 | 作用 | Java 类比 |
|------|------|-----------|
| `title` | API 文档标题 | `spring.application.name` |
| `description` | API 文档描述 | README 一句话描述 |
| `version` | API 版本号 | pom.xml `<version>` |

### 自动生成 API 文档

FastAPI 自带 Swagger UI 和 ReDoc：
- `http://localhost:8000/docs` → Swagger UI（交互式测试）
- `http://localhost:8000/redoc` → ReDoc（只读文档）

Spring 要加 `springdoc-openapi` 依赖才有，FastAPI 开箱即用。

### FastAPI 手动创建 vs Spring 自动创建

```python
# FastAPI：手动 new
app = FastAPI(...)
```
```java
// Spring：框架帮你创建
SpringApplication.run(Application.class, args);
```

---

## 块 3：CORS 中间件（跨域）

```python
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
```

### 什么是跨域

"域" = 协议 + 域名 + 端口。端口不同就是不同的域。

```
前端：http://localhost:3000
后端：http://localhost:8000
端口不同 = 跨域
```

浏览器有**同源策略**：默认禁止 JS 跨域发请求。后端要在响应头加 `Access-Control-Allow-Origin` 告诉浏览器"我允许谁"。

### 四个参数

| 参数 | 值 | 含义 |
|------|-----|------|
| `allow_origins` | `["*"]` | 允许所有域名（生产要写具体域名） |
| `allow_credentials` | `True` | 允许带 Cookie |
| `allow_methods` | `["*"]` | 允许所有 HTTP 方法 |
| `allow_headers` | `["*"]` | 允许所有请求头 |

### 中间件是什么

类比 Java Servlet 的 Filter / Spring Interceptor。在每个请求前后插一脚：
```
请求进来 → [CORS 中间件] → 路由处理 → [CORS 中间件] → 响应出去
```

CORS 中间件自动给所有响应加 CORS 头，不用每个接口手动加。

### Java 解决跨域的 3 种方式

| 方式 | 写法 | 对应本项目 |
|------|------|-----------|
| ① `@CrossOrigin` 注解 | 单接口加注解 | ❌ |
| ② `WebMvcConfigurer` 全局配置 | 配置类 | ✅ 对应 `add_middleware` |
| ③ Filter 过滤器 | 最底层 | ❌ |

本质都是往响应头塞那 4 个字段，语言框架只是工具不同。

### 前端 proxy vs 后端 CORS

| 方式 | 在哪做 | 适用场景 |
|------|--------|---------|
| 前端开发代理（Vite proxy） | 前端开发服务器 | **只开发环境** |
| 后端 CORS 头 | 后端 | **生产环境必须** |

前端 proxy 只在开发环境有效，生产环境没 Vite 服务器，必须后端配 CORS。

### Nginx 反向代理消除跨域

生产环境用 Nginx 反向代理让前后端同域：
```
浏览器 → Nginx (80) → /api/ 转发到后端 8080
```
浏览器只跟 Nginx 通信，前后端同域，不跨域。这是"绕过"策略，不是"解决"CORS。

### 本项目的小瑕疵

`allow_origins=["*"]` + `allow_credentials=True` 矛盾：浏览器规范规定带 credentials 时 origin 不能用 `*`。生产要改成具体域名。

---

## 块 4：路由注册

```python
app.include_router(collect_router, prefix="/api/v1")
app.include_router(traces_router, prefix="/api/v1")
app.include_router(metrics_router, prefix="/api/v1")
app.include_router(prompts_router, prefix="/api/v1")
app.include_router(leaderboard_router, prefix="/api/v1")
```

### 路径拼接

完整路径 = prefix + router 内部定义的路径：

| router 里写的 | 加前缀后 | 完整路径 |
|--------------|---------|---------|
| `@router.post("/collect")` | `+ /api/v1` | `/api/v1/collect` |
| `@router.get("/traces")` | `+ /api/v1` | `/api/v1/traces` |
| `@router.get("/metrics/compare")` | `+ /api/v1` | `/api/v1/metrics/compare` |
| `@router.get("/prompts")` | `+ /api/v1` | `/api/v1/prompts` |
| `@router.get("/leaderboard")` | `+ /api/v1` | `/api/v1/leaderboard` |

### Java 类比

```java
@RestController
@RequestMapping("/api/v1")           // ← 类上加前缀
public class CollectController {
    @PostMapping("/collect")          // ← 方法上加相对路径
}
// 最终：/api/v1/collect
```

Spring 把前缀写在 Controller 类上，FastAPI 写在 `include_router` 的 `prefix` 参数。路径拼接逻辑完全一样。

### 为什么用 /api/v1 前缀

RESTful API 版本控制约定：`api` = API 接口，`v1` = 第一版（以后可能有 v2）。

---

## 块 5：生命周期事件 startup / shutdown

```python
@app.on_event("startup")
async def startup_event():
    await init_producer()
    await start_consumer()

@app.on_event("shutdown")
async def shutdown_event():
    await stop_consumer()
    await close_producer()
```

### 两个事件

| 事件 | 什么时候触发 | 干啥 |
|------|------------|------|
| `startup` | 应用启动时 | 初始化资源（连 Kafka） |
| `shutdown` | 应用关闭时 | 清理资源（断 Kafka） |

### Java 类比

| Spring Boot | FastAPI |
|-------------|---------|
| `@PostConstruct` | `@app.on_event("startup")` |
| `@PreDestroy` | `@app.on_event("shutdown")` |
| `CommandLineRunner.run()` | `startup_event()` |
| `DisposableBean.destroy()` | `shutdown_event()` |

### startup 顺序

```
init_producer()    → 连上 Kafka，创建生产者
start_consumer()   → 连上 Kafka，启动后台消费循环
```

为什么启动时初始化？建立 Kafka 连接要几十毫秒，每请求建一次太慢。启动时建好，全局共享。

### shutdown 顺序（跟启动相反）

```
stop_consumer()    → 停后台循环，断开消费者
close_producer()   → 断开生产者
```

为什么相反？先停消费方（不再消费），再停生产方。反过来先停 producer，此时还有请求进来想发 Kafka 会失败。类比关店：先不让新客人进店，再关门锁门。

### await 是什么

`await` = 等这个异步操作完成再往下走。保证顺序：先连上 producer，再启动 consumer。

### 为什么需要生命周期事件

有些资源不能每请求都创建，必须全局共享：
- Kafka 生产者：建 TCP 连接很慢，要复用
- Kafka 消费者：后台常驻进程，不是请求级别的
- 数据库连接池：连接要复用

启动时创建一次，全局共享；关闭时统一释放。

### 不写 shutdown 会怎样

资源泄漏：Kafka 连接没断、文件描述符泄漏、内存数据丢失。

---

## 块 6：健康检查接口

```python
@app.get("/health")
async def health_check():
    return {"status": "ok"}
```

### 作用

给运维监控系统用（K8s liveness probe、Nginx、Prometheus）：
```
监控系统每隔 5 秒调 GET /health
  返回 200 → 服务正常
  返回 500/超时 → 服务挂了，报警/重启
```

### 注意没加 /api/v1 前缀

健康检查是基础设施接口，不属于业务 API。K8s/Nginx 调 `/health` 不用加 `/api/v1`。

### Java 类比

Spring Boot Actuator 自带 `/actuator/health`，FastAPI 没自带，手动写。

---

## 装饰器详解

### 装饰器本质

装饰器是一个函数，接收一个函数，返回一个新函数：

```python
def log_time(func):
    def wrapper():
        print("开始")
        func()
        print("结束")
    return wrapper

@log_time
def hello():
    print("hello")

# @log_time 等价于：
hello = log_time(hello)
```

### 装饰器 vs Java 注解

| 维度 | Python 装饰器 | Java 注解 |
|------|--------------|----------|
| 本质 | 函数（能执行代码） | 标记（存进 class 文件） |
| 生效方式 | 运行时自己执行 | 需要框架扫描处理 |
| 自己能干活吗 | ✅ 能 | ❌ 不能，只是标签 |
| 类比 | 主动型 | 被动型 |

### 装饰器来源

| 来源 | 例子 | Java 类比 |
|------|------|-----------|
| 框架自带 | `@app.get`、`@app.on_event` | `@GetMapping`、`@PostConstruct` |
| Python 内置 | `@property`、`@dataclass` | Lombok |
| 第三方库 | `@lru_cache`、`@retry` | `@Cacheable`、`@Retryable` |
| 自己写 | `@log_time`、`@require_auth` | 自己写 AOP |

本项目所有装饰器都是 FastAPI 自带的，没自己写。

### 装饰器 vs AOP

装饰器能实现 AOP 的效果（方法前后插逻辑），但装饰器 ≠ AOP：
- **AOP**：思想，"不改原方法，在前后插逻辑"
- **装饰器**：工具，能干 AOP，还能干注册事件、注册路由

| 装饰器用途 | 是不是 AOP |
|-----------|-----------|
| 方法前后插日志/计时/事务 | ✅ 是 AOP |
| 注册事件（`@app.on_event`） | ❌ 不是 |
| 注册路由（`@app.get`） | ❌ 不是 |
| 权限校验/缓存 | ✅ 是 AOP |

### Java AOP vs Python 装饰器

| 维度 | Java Spring AOP | Python 装饰器 |
|------|---------------|---------------|
| 怎么找目标方法 | 切点表达式（批量） | 显式贴 `@`（一个个） |
| 被增强方法知道吗 | ❌ 不知道（透明） | ⚠️ 知道（手动贴） |
| 批量能力 | ✅ 一个切点切一片 | ❌ 一个 @ 贴一个函数 |

---

## Spring Boot 对照表（完整）

| main.py 做的事 | Spring Boot 对应 |
|----------------|-----------------|
| `app = FastAPI(...)` | `SpringApplication.run()` |
| `app.add_middleware(CORSMiddleware)` | `WebMvcConfigurer.addCorsMappings()` |
| `app.include_router(router, prefix)` | `@ComponentScan` 自动扫描 |
| `@app.on_event("startup")` | `@PostConstruct` / `CommandLineRunner` |
| `@app.on_event("shutdown")` | `@PreDestroy` / `DisposableBean` |
| `@app.get("/health")` | `@GetMapping("/health")` |
| 自动生成 /docs | 加 springdoc-openapi 依赖 |

---

## 一句话总结

1. **main.py = Spring Boot 启动类**，创建 app + 加中间件 + 注册路由 + 管生命周期。
2. **Router ≈ Controller**，Spring 自动扫描，FastAPI 手动 include_router。
3. **CORS 解决跨域**，后端在响应头告诉浏览器"我允许谁"，前端 proxy 只开发环境有效。
4. **生命周期事件 = `@PostConstruct`/`@PreDestroy`**，启动时建 Kafka 连接，关闭时释放。
5. **装饰器是"自己干活的函数"**，Java 注解是"需要框架扫描的标签"。装饰器能实现 AOP 但不止干 AOP。
