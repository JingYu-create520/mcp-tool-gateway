FROM eclipse-temurin:21-jre

WORKDIR /app

COPY examples/upstream-time/target/upstream-time-0.1.0-SNAPSHOT-exec.jar app.jar

EXPOSE 8090

# 健康检查：编排层能感知 JVM 假死（restart: unless-stopped 对此无感知）
RUN apt-get update     && apt-get install -y --no-install-recommends curl     && rm -rf /var/lib/apt/lists/*
HEALTHCHECK --interval=20s --timeout=4s --start-period=90s --retries=5     CMD curl -sf http://localhost:8090/actuator/health || exit 1

# 非 root 运行：容器内进程被攻破不直接获得 root
RUN useradd --system --uid 10001 appuser
USER appuser

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar --spring.config.name=upstream-time"]
