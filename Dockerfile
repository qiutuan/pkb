# ---------- 阶段 1：构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml .
# 先拉依赖层，善用缓存
RUN mvn -q -B dependency:go-offline || true
COPY backend/src ./src
RUN mvn -q -B -DskipTests package

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/pkb.jar /app/pkb.jar
# 数据目录（SQLite、密钥、上传文件、设置文件都在这里），建议挂载卷
RUN mkdir -p /data && chmod 777 /data
ENV PKB_DATA_DIR=/data \
    SERVER_PORT=8080 \
    PKB_VECTOR_MODE=embedded
EXPOSE 8080
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fs http://localhost:8080/api/system/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/pkb.jar"]
