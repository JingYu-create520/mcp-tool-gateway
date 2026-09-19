FROM eclipse-temurin:21-jre

WORKDIR /app

COPY examples/upstream-weather/target/upstream-weather-0.1.0-SNAPSHOT-exec.jar app.jar

EXPOSE 8091

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.config.name=upstream-weather"]
