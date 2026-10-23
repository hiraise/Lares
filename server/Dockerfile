# FamilyHub server image. Copies the output of `./gradlew :server:installDist`; no Gradle inside Docker.
# Build from the repository root:
#   ./gradlew :server:installDist && docker build -f server/Dockerfile -t familyhub-server:dev .
FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 familyhub \
    && useradd --system --uid 10001 --gid familyhub --home-dir /app --no-create-home --shell /usr/sbin/nologin familyhub

COPY --chown=root:root server/build/install/server /app

# Small heap and code cache, fewer malloc arenas (ADR-0013); measured in M0-11, docs/architecture/perf/M0-baseline.md.
ENV JAVA_OPTS="-XX:+UseSerialGC -Xmx128m -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m -XX:+ExitOnOutOfMemoryError" \
    MALLOC_ARENA_MAX=2 \
    FAMILYHUB_PORT=8080

USER familyhub
WORKDIR /app
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/health/ready || exit 1

ENTRYPOINT ["/app/bin/server"]
