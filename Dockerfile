FROM eclipse-temurin:25-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS build
WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts gradle.lockfile settings-gradle.lockfile ./
COPY gradle ./gradle
COPY src/main ./src/main
COPY docs/openapi.yaml ./docs/openapi.yaml
RUN --mount=type=cache,target=/root/.gradle sh ./gradlew bootJar --no-daemon --console=plain

FROM eclipse-temurin:25-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce AS runtime
LABEL org.opencontainers.image.authors="Васильев Юрий, 4П1.23"
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 support \
    && useradd --uid 10001 --gid support --no-create-home support
COPY --from=build --chown=10001:10001 /workspace/build/libs/*.jar ./app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD curl --fail --silent --show-error --max-time 3 --output /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
