# 多阶段构建无需：CI/本地先用 Maven 打出 fat jar，再拷贝进镜像
FROM eclipse-temurin:21-jre

WORKDIR /app

# spring-boot-maven-plugin 的 repackage 产物（mvn -pl gateway-core -am package 之后存在）
COPY gateway-core/target/gateway-core-0.1.0-SNAPSHOT-exec.jar app.jar

EXPOSE 8080

# 健康检查：编排层能感知 JVM 假死（restart: unless-stopped 对此无感知）。
# 探针端口跟随 MANAGEMENT_SERVER_PORT（compose 内网 9091；本地裸跑默认与业务共享 8080）
RUN apt-get update     && apt-get install -y --no-install-recommends curl     && rm -rf /var/lib/apt/lists/*
HEALTHCHECK --interval=20s --timeout=4s --start-period=90s --retries=5     CMD sh -c 'curl -sf "http://localhost:${MANAGEMENT_SERVER_PORT:-8080}/actuator/health" || exit 1'

# 非 root 运行：容器内进程被攻破不直接获得 root
RUN useradd --system --uid 10001 appuser
USER appuser

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
