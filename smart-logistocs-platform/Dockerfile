# syntax=docker/dockerfile:1
# =============================================================================
# Smart Logistics Platform (ASLP) — 统一多模块 Dockerfile
#
# 历史 Bug：原实现按服务拆分为 services/*/Dockerfile，构建上下文被设为 ./services/xxx，
# 无法访问项目根 pom.xml（COPY ../../pom.xml 属于上下文越界，必然失败），
# 且 auth/report 的 Dockerfile 直接 COPY target/*.jar（容器构建时并不存在）。
#
# 现统一为「根上下文 + ARG MODULE」：
#   docker build --build-arg MODULE=gateway -t aslp/gateway .
#   docker build --build-arg MODULE=services/route-service -t aslp/route-service .
# =============================================================================

# ---------------------------- 构建阶段 ----------------------------
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# 目标模块（多模块坐标：gateway 或 services/xxx）
ARG MODULE=gateway

# 1) 先只复制 POM 结构，充分利用 Docker 层缓存预下载依赖
COPY pom.xml ./
COPY gateway/pom.xml gateway/
COPY services/auth-service/pom.xml services/auth-service/
COPY services/inventory-service/pom.xml services/inventory-service/
COPY services/order-service/pom.xml services/order-service/
COPY services/report-service/pom.xml services/report-service/
COPY services/route-service/pom.xml services/route-service/
RUN mvn -B -q dependency:go-offline -pl "${MODULE}" -am -DskipTests || true

# 2) 复制源码（.dockerignore 已排除 target/、.git/、.gradle/ 等）并构建
COPY . .
# -T 1C：Maven “高铁模式” —— 按 CPU 核心数并行编译
RUN mvn -B clean package -pl "${MODULE}" -am -DskipTests -T 1C

# ---------------------------- 运行阶段 ----------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# 非 root 运行，降低容器逃逸风险
RUN addgroup -S aslp && adduser -S aslp -G aslp

ARG MODULE=gateway
WORKDIR /app

COPY --from=build /app/${MODULE}/target/*.jar /app/app.jar
RUN chown -R aslp:aslp /app
USER aslp

# 容器内存感知 + 容器环境优化
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD wget -qO- http://localhost:${SERVER_PORT:-8080}/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

