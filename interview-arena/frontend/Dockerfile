# ===== 前端 Dockerfile(多阶段构建)=====

# 第一阶段:安装依赖
FROM node:20-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm install

# 第二阶段:构建
FROM node:20-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# 第三阶段:运行(只保留生产文件)
FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
RUN mkdir -p public
EXPOSE 3000
CMD ["node", "server.js"]
