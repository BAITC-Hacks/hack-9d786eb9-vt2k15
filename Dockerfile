# syntax=docker/dockerfile:1

# --- Build: собираем исполняемый jar через Gradle wrapper (JDK 21) ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Сначала только файлы сборки — слой с зависимостями кешируется отдельно от исходников.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# --- Runtime: только JRE + собранный jar ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
