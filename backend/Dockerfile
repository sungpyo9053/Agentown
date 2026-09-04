# syntax=docker/dockerfile:1.7
FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY backend/build.gradle.kts backend/build.gradle.kts
COPY backend/src backend/src
COPY core-runtime core-runtime
RUN test -f core-runtime/pyproject.toml
RUN test -f core-runtime/agentown_tframex_adapter/adapter.py
RUN --mount=type=cache,target=/home/gradle/.gradle/caches \
    --mount=type=cache,target=/home/gradle/.gradle/wrapper \
    gradle :backend:bootJar --no-daemon
RUN jar tf /workspace/backend/build/libs/backend-0.1.0-SNAPSHOT.jar \
    | grep -q '^BOOT-INF/classes/tframex-runtime/pyproject.toml$'
RUN jar tf /workspace/backend/build/libs/backend-0.1.0-SNAPSHOT.jar \
    | grep -q '^BOOT-INF/classes/tframex-runtime/agentown_tframex_adapter/adapter.py$'
RUN jar tf /workspace/backend/build/libs/backend-0.1.0-SNAPSHOT.jar \
    | grep -q '^BOOT-INF/classes/tframex-runtime/agentown_tframex_adapter/server.py$'

FROM alpine:3.20 AS codex
RUN apk add --no-cache curl \
    && curl -fsSL https://chatgpt.com/codex/install.sh | sh \
    && cp "$(readlink -f /root/.local/bin/codex)" /codex \
    && /codex --version

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=codex /codex /usr/local/bin/codex
COPY --from=build /workspace/backend/build/libs/backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
