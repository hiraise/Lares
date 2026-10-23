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

# Small, container-aware heap (ADR-0013); M0-11 may tune.
ENV JAVA_OPTS="-XX:+UseSerialGC -Xmx160m -Xss512k -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError" \
    FAMILYHUB_PORT=8080

USER familyhub
WORKDIR /app
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/health/ready || exit 1

ENTRYPOINT ["/app/bin/server"]
