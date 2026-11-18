# syntax=docker/dockerfile:1
# Build from the repository root (the service depends on the booking-contract module):
#   docker build -f booking-consumer-service/Dockerfile -t booking-consumer-service .

FROM eclipse-temurin:21-jdk-noble@sha256:06c1f157dacf6b68198bd9a4186dd4c34ef24e65d7bbbe2a0cf8e62571ad38c9 AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
# The reactor needs every module's POM, but only this service's and the contract's sources
COPY booking-producer-service/pom.xml booking-producer-service/pom.xml
COPY booking-contract booking-contract
COPY booking-consumer-service booking-consumer-service

# Tests run in CI before images are built; Testcontainers cannot run inside a docker build
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw -B -ntp -pl booking-consumer-service -am package -DskipTests \
    && cp booking-consumer-service/target/booking-consumer-service-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:16641166545e1b07b10ba0d51118000620980e51a38359c822c7003d5b3742de

LABEL org.opencontainers.image.title="booking-consumer-service" \
      org.opencontainers.image.description="Worker consuming booking events from RabbitMQ and persisting bookings"

WORKDIR /app
# Least-frequently changing layers first, so code changes only rebuild the thin application layer
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

# Heap sized from the container memory limit; exit on OOM so the orchestrator restarts a clean JVM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

USER nonroot
# Management port only (probes, metrics); the consumer serves no API
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
