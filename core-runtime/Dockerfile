# syntax=docker/dockerfile:1.7
FROM alpine:3.20 AS codex
RUN apk add --no-cache curl \
    && curl -fsSL https://chatgpt.com/codex/install.sh | sh \
    && cp "$(readlink -f /root/.local/bin/codex)" /codex

FROM python:3.12-slim
RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates git \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 app
WORKDIR /app
COPY core-runtime/pyproject.toml ./pyproject.toml
COPY core-runtime/agentown_tframex_adapter ./agentown_tframex_adapter
RUN pip install --no-cache-dir .
COPY --from=codex /codex /usr/local/bin/codex
USER app
EXPOSE 8090
ENTRYPOINT ["agentown-tframex-runtime"]
