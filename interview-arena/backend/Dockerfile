# ===== 后端 Dockerfile(多阶段构建)=====

# 第一阶段:编译
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
# 先复制 pom.xml,下载依赖(利用 Docker 缓存层)
COPY pom.xml .
RUN mvn dependency:go-offline -B
# 复制源码,编译打包
COPY src ./src
RUN mvn package -DskipTests -B

# 第二阶段:运行(只用 JRE,镜像更小)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
