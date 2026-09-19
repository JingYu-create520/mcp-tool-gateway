# 多阶段构建无需：CI/本地先用 Maven 打出 fat jar，再拷贝进镜像
FROM eclipse-temurin:21-jre

WORKDIR /app

# spring-boot-maven-plugin 的 repackage 产物（mvn -pl gateway-core -am package 之后存在）
COPY gateway-core/target/gateway-core-0.1.0-SNAPSHOT-exec.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
