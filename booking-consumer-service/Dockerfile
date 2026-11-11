# syntax=docker/dockerfile:1
# Build from the repository root (the service depends on the booking-contract module):
#   docker build -f booking-consumer-service/Dockerfile -t booking-consumer-service .

FROM eclipse-temurin:21-jdk-noble@sha256:114cabb73033ca4ce7ceec3deb62396bc1b716282bae051ed3c1d82e38fcab2d AS build
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

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:d978eefd303c59c42f382aef811a0e41f2c68a6e5c319a23769cd3d1d5bf8e3c

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
