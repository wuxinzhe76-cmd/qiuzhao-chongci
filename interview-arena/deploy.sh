#!/bin/bash
# 服务器部署脚本
# 在服务器上执行: bash deploy.sh

set -e

PROJECT_DIR="/root/interview-arena"
REPO_URL="https://github.com/wuxinzhe76-cmd/interview-arena.git"

echo "===== 1. 拉取最新代码 ====="
if [ -d "$PROJECT_DIR" ]; then
    cd "$PROJECT_DIR"
    git pull origin main
else
    git clone "$REPO_URL" "$PROJECT_DIR"
    cd "$PROJECT_DIR"
fi

echo "===== 2. 构建并启动容器 ====="
docker compose down
docker compose up -d --build

echo "===== 3. 等待启动 ====="
sleep 10

echo "===== 4. 检查状态 ====="
docker compose ps

echo "===== 5. 健康检查 ====="
for i in 1 2 3 4 5; do
    code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost/api/health 2>/dev/null || echo "000")
    if [ "$code" = "200" ]; then
        echo "后端健康检查: OK"
        break
    fi
    echo "等待后端启动... ($i/5)"
    sleep 5
done

echo "===== 部署完成 ====="
echo "访问: http://$(curl -s ifconfig.me)"
