# Docker 指南

---

## 一、Docker 为什么会出现

### 一个经典问题

你在自己电脑上写了一个 Python 项目，跑得好好的。

```bash
python main.py
# 运行成功
```

然后发给同事：

```bash
python main.py
# ModuleNotFoundError: No module named 'xxx'
```

为什么跑不起来？

- Python 版本不一样（你 3.10，他 3.8）
- 依赖包没装，或者版本不对
- 操作系统不一样（你 Mac，他 Windows）
- 环境变量没配

这就是"在我电脑上能跑"的问题。

### 更麻烦的场景

假设你的项目需要：
- Python 3.10
- Kafka
- ClickHouse
- Redis
- Nginx

每个都要单独装、单独配。新人入职第一天，光搭环境就要搞半天。

换个电脑？重新来一遍。

### Docker 怎么解决

Docker 的思路是：把应用和它的所有依赖打包在一起，做成一个"集装箱"。

不管在哪台机器，只要有 Docker，这个集装箱就能跑起来，环境完全一致。

```
你的电脑：docker run my-app    → 跑起来了
同事电脑：docker run my-app    → 也跑起来了
服务器：  docker run my-app    → 还是跑起来了
```

### 本章小结

Docker 解决的是环境问题。把应用和依赖打包在一起，到哪都能跑。

---

## 二、两个核心概念

Docker 有两个最基本的概念：镜像和容器。

### 镜像（Image）

镜像就是一个只读的模板，包含运行应用需要的一切：代码、运行时、依赖、配置。

可以理解为"安装包"。

```
python:3.10        → Python 运行环境
nginx:latest       → Nginx 服务器
clickhouse:latest  → ClickHouse 数据库
```

### 容器（Container）

容器是镜像运行起来的实例。

镜像是"安装包"，容器是"安装后跑起来的程序"。

```bash
# 用 nginx 镜像启动一个容器
docker run nginx

# 这时候 Nginx 就跑起来了
```

一个镜像可以启动多个容器，每个容器之间互不影响。

### 类比

```
镜像 = 类（Class）
容器 = 对象（Instance）

你可以用同一个类 new 出多个对象。
也可以用同一个镜像 run 出多个容器。
```

### 本章小结

镜像是模板，容器是运行起来的实例。

---

## 三、常用命令

### 镜像相关

```bash
# 查看本地镜像
docker images

# 从远程拉取镜像
docker pull python:3.10

# 删除镜像
docker rmi python:3.10
```

### 容器相关

```bash
# 启动容器
docker run python:3.10

# 查看运行中的容器
docker ps

# 查看所有容器（包括已停止的）
docker ps -a

# 停止容器
docker stop <container_id>

# 删除容器
docker rm <container_id>

# 进入容器内部
docker exec -it <container_id> bash
```

### 常用参数

```bash
# 后台运行
docker run -d nginx

# 端口映射（宿主机 8080 → 容器 80）
docker run -p 8080:80 nginx

# 挂载目录（宿主机 ./data → 容器 /data）
docker run -v ./data:/data nginx

# 设置环境变量
docker run -e MYSQL_ROOT_PASSWORD=123456 mysql

# 给容器起个名字
docker run --name my-nginx nginx
```

### 本章小结

`docker run` 启动容器，`docker ps` 查看容器，`docker exec` 进入容器。

---

## 四、Dockerfile

### 什么是 Dockerfile

Dockerfile 是一个文本文件，里面写了一系列指令，告诉 Docker 怎么构建镜像。

比如你要打包一个 Python 应用：

```dockerfile
FROM python:3.10

WORKDIR /app

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY . .

CMD ["python", "main.py"]
```

每一行都是一个指令：

| 指令 | 作用 |
|------|------|
| FROM | 基于哪个基础镜像 |
| WORKDIR | 设置工作目录 |
| COPY | 把文件复制进镜像 |
| RUN | 构建时执行命令 |
| CMD | 容器启动时执行命令 |

### 构建镜像

```bash
# 根据 Dockerfile 构建镜像，-t 指定名字和标签
docker build -t my-app:1.0 .
```

### 运行镜像

```bash
docker run my-app:1.0
```

### 本章小结

Dockerfile 定义怎么打包，`docker build` 生成镜像，`docker run` 启动容器。

---

## 五、Docker Compose

### 一个问题

一个项目通常不止一个容器。比如 Agent-Insight 需要：

- Kafka
- ClickHouse
- FastAPI 后端
- React 前端

一个个 `docker run` 太麻烦，而且容器之间的网络、依赖关系不好处理。

### Docker Compose 是什么

Docker Compose 用一个 YAML 文件定义多个容器，一条命令全部启动。

```yaml
# docker-compose.yml
version: '3.8'

services:
  kafka:
    image: confluentinc/cp-kafka:7.5.0
    ports:
      - "9092:9092"
  
  clickhouse:
    image: clickhouse/clickhouse-server:latest
    ports:
      - "8123:8123"
  
  backend:
    build: ./backend
    ports:
      - "8000:8000"
    depends_on:
      - kafka
      - clickhouse
```

### 常用命令

```bash
# 启动所有服务（后台运行）
docker-compose up -d

# 查看运行状态
docker-compose ps

# 查看日志
docker-compose logs -f

# 停止所有服务
docker-compose down

# 重新构建并启动
docker-compose up -d --build
```

### 本章小结

Docker Compose 管理多个容器，一个 YAML 文件定义，一条命令启动。

---

## 六、在 Agent-Insight 项目中的角色

### 项目的 docker-compose.yml

```yaml
version: '3.8'

services:
  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:29093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    volumes:
      - kafka_data:/var/lib/kafka/data

  clickhouse:
    image: clickhouse/clickhouse-server:23.8
    container_name: clickhouse
    ports:
      - "8123:8123"
      - "9000:9000"
    volumes:
      - clickhouse_data:/var/lib/clickhouse

volumes:
  kafka_data:
  clickhouse_data:
```

### 启动项目基础设施

```bash
# 启动 Kafka 和 ClickHouse
docker-compose up -d

# 查看状态
docker-compose ps

# 查看日志
docker-compose logs -f kafka
docker-compose logs -f clickhouse
```

### 数据持久化

注意 `volumes` 部分。容器删了，数据就没了。用 Volume 可以把数据存到宿主机：

```yaml
volumes:
  - kafka_data:/var/lib/kafka/data
```

这样即使容器重启，数据还在。

### 本章小结

项目用 Docker Compose 管理 Kafka 和 ClickHouse，一条命令启动所有基础设施。

---

## 七、动手体验

### 启动一个 Nginx

```bash
# 拉取镜像
docker pull nginx

# 启动容器，后台运行，端口映射
docker run -d -p 8080:80 --name my-nginx nginx

# 浏览器打开 http://localhost:8080，能看到 Nginx 欢迎页

# 停止
docker stop my-nginx

# 删除
docker rm my-nginx
```

### 进入容器内部

```bash
# 启动一个交互式的 Ubuntu 容器
docker run -it ubuntu bash

# 现在你在容器内部了
root@xxx:/# ls
root@xxx:/# apt update
root@xxx:/# exit
```

### 用 Docker Compose 启动项目

```bash
# 进入项目目录
cd agent-observability

# 启动所有服务
docker-compose up -d

# 查看状态
docker-compose ps

# 应该能看到 kafka 和 clickhouse 两个容器在运行
```

### 查看日志

```bash
# 查看所有服务日志
docker-compose logs -f

# 只看 Kafka 日志
docker-compose logs -f kafka
```

---

## 八、常见问题

**Q：Docker 和虚拟机有什么区别？**

|  | Docker | 虚拟机 |
|--|--------|--------|
| 启动速度 | 秒级 | 分钟级 |
| 资源占用 | 小（共享宿主机内核） | 大（每个 VM 有完整 OS） |
| 隔离性 | 进程级隔离 | 完全隔离 |
| 性能 | 接近原生 | 有损耗 |
| 适用场景 | 应用部署、微服务 | 需要完全隔离的环境 |

Docker 更轻量，虚拟机更隔离。

**Q：容器重启后数据会丢吗？**

默认会丢。要用 Volume 持久化：

```yaml
volumes:
  - my_data:/var/lib/data
```

或者挂载宿主机目录：

```yaml
volumes:
  - ./local_data:/var/lib/data
```

**Q：容器之间怎么通信？**

Docker Compose 里的容器自动在同一个网络，可以用服务名访问：

```python
# backend 连接 Kafka
KAFKA_HOST = "kafka:9092"  # 直接用服务名 kafka

# backend 连接 ClickHouse
CLICKHOUSE_HOST = "clickhouse"  # 直接用服务名 clickhouse
```

**Q：怎么更新镜像？**

```bash
# 拉取最新镜像
docker-compose pull

# 重新创建容器
docker-compose up -d --force-recreate
```

**Q：怎么清理不用的镜像和容器？**

```bash
# 删除所有停止的容器
docker container prune

# 删除所有未使用的镜像
docker image prune

# 一键清理（容器、镜像、网络、缓存）
docker system prune
```

---

## 九、延伸阅读

| 资源 | 链接 |
|------|------|
| Docker 官方文档 | https://docs.docker.com |
| Docker Compose 文档 | https://docs.docker.com/compose |
| 项目的 docker-compose.yml | `docker-compose.yml` |
| 项目的 Dockerfile | `backend/Dockerfile` |

---

Docker 解决的是环境问题。把应用和依赖打包在一起，到哪都能跑。Docker Compose 解决的是多容器管理问题，一个 YAML 文件定义，一条命令启动。在 Agent-Insight 项目中，Kafka 和 ClickHouse 都用 Docker 跑，省去了本地安装的麻烦。
