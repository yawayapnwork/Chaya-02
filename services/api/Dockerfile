# syntax=docker/dockerfile:1.7
# Chaya 02 API (Spring Boot control plane).
#
# Build:  docker build -t chaya-api services/api
# Runs as a fixed non-root UID, with a JRE only (no JDK, no Maven, no shell tools beyond busybox) and no secrets:
# every credential arrives as an environment variable at run time (DEPLOYMENT.md). Tests are neither compiled nor run
# here; CI compiles and runs them (.github/workflows/backend.yml) on the same commit.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -Dmaven.test.skip=true package \
 && java -Djarmode=tools -jar "$(ls target/chaya-api-*.jar | head -1)" extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 chaya && adduser -S -D -H -u 10001 -G chaya chaya
WORKDIR /app
# Layers from least to most frequently changing, so a code change re-ships only the last one. Owned by root and not
# writable by the runtime user: the application cannot modify itself.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom" \
    API_PORT=8080
EXPOSE 8080
# Readiness, not liveness: healthy only once migrations ran and the database, object storage and identity provider
# answer (dev.chaya.api.health.DependenciesHealthIndicator). start-period covers Flyway on first boot.
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=4 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
