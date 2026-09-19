FROM eclipse-temurin:21-jre

WORKDIR /app

COPY examples/upstream-time/target/upstream-time-0.1.0-SNAPSHOT-exec.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.config.name=upstream-time"]
