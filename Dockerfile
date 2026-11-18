# syntax=docker/dockerfile:1
# Multi-stage build: Maven on a JDK 21 image, then a slim JRE 21 image that runs as a non-root user.
# No secrets are baked in: every setting comes from environment variables at run time (see compose.yaml).

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependencies first, so this layer is cached until the POM changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Tests need Docker (Testcontainers), which is not available here. CI and `./mvnw clean verify` run them.
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package && mv target/ledger-*.jar /workspace/app.jar

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 ledger && adduser -S -u 10001 -G ledger -h /app ledger
WORKDIR /app
COPY --from=build --chown=ledger:ledger /workspace/app.jar app.jar

USER 10001:10001
EXPOSE 8080

# Heap is a share of the container's memory limit (so it follows `mem_limit`), not a fixed -Xmx.
# Exit on OutOfMemoryError so the orchestrator restarts a clean process instead of a half-dead one.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
