# Stage 1: Build with JDK 25
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app

COPY gradle/ ./gradle/
COPY gradlew .
COPY settings.gradle .
COPY build.gradle .

# Copy refdata-tool subproject (required by settings.gradle)
COPY refdata-tool/ ./refdata-tool/

# Download dependencies (cached separately)
RUN ./gradlew dependencies --no-daemon || true

COPY src/ ./src/

RUN ./gradlew bootJar --no-daemon -x test

# Stage 1b: Generate AOT cache with training run
FROM eclipse-temurin:25-jdk-alpine AS aot-generator
WORKDIR /app

COPY compose.yaml .

COPY src/main/resources/db/migration/ ./src/main/resources/db/migration/

COPY --from=builder /app/build/libs/*.jar ./packagenameguardian.jar

# Generate AOT cache with a cold training run (no DB available at build time).
# JEP 514 (Java 25): -XX:AOTCacheOutput does training run automatically, observes
# class loading/linking patterns, then creates the cache in a single invocation.
# Cache is "cold" (no observations from actual queries), but startup patterns are
# captured; in production with a real DB, the cache will refine over time.
RUN timeout 30 java \
    -XX:AOTCacheOutput=packagenameguardian.aot \
    -Dspring.datasource.url=jdbc:postgresql://localhost:5432/dummy \
    -Dspring.profiles.active=prod \
    -jar packagenameguardian.jar || true

# Stage 2: Create minimal JRE with jlink
FROM eclipse-temurin:25-jdk-alpine AS jre-builder
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar /app/packagenameguardian.jar

# Extract and analyze dependencies for minimal JRE
RUN mkdir -p /tmp/app && \
    cd /tmp/app && \
    jar xf /app/packagenameguardian.jar && \
    MODULES=$(jdeps \
        --ignore-missing-deps \
        --print-module-deps \
        --multi-release 25 \
        --class-path '/tmp/app/BOOT-INF/lib/*' \
        /tmp/app/BOOT-INF/classes \
        2>/dev/null) && \
    echo "Detected modules: $MODULES" && \
    jlink \
        --add-modules "${MODULES},jdk.crypto.ec" \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=zip-6 \
        --output /opt/jre

# Stage 3: Runtime image
FROM alpine:3
ENV JAVA_HOME=/opt/jre
ENV PATH="$JAVA_HOME/bin:$PATH"
WORKDIR /app

# Copy minimal JRE, application, and AOT cache
COPY --from=jre-builder /opt/jre /opt/jre
COPY --from=builder /app/build/libs/*.jar /app/packagenameguardian.jar
COPY --from=aot-generator /app/packagenameguardian.aot /app/packagenameguardian.aot

# Create non-root user and directories
RUN addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    mkdir -p /app/logs && \
    chown -R appuser:appgroup /app/logs

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:AOTCache=packagenameguardian.aot", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=100", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseCompactObjectHeaders", \
    "-XX:+UseStringDeduplication", \
    "-Dsun.net.inetaddr.ttl=60", \
    "-Dspring.profiles.active=prod", \
    "-jar", \
    "packagenameguardian.jar"]
