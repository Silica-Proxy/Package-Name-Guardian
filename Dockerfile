# Stage 1: Build with JDK 25
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app

# Copy gradle wrapper and config first for better layer caching
COPY gradle/ ./gradle/
COPY gradlew .
COPY settings.gradle .
COPY build.gradle .

# Copy refdata-tool subproject (required by settings.gradle)
COPY refdata-tool/ ./refdata-tool/

# Download dependencies (cached separately)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY src/ ./src/

# Build the application (skip tests) - packagenameguardian is the root project
RUN ./gradlew bootJar --no-daemon -x test

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

# Copy minimal JRE and application
COPY --from=jre-builder /opt/jre /opt/jre
COPY --from=builder /app/build/libs/*.jar /app/packagenameguardian.jar

# Create non-root user and directories
RUN addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    mkdir -p /app/logs && \
    chown -R appuser:appgroup /app/logs

# Switch to non-root user
USER appuser

# Expose port 8100 (Spring Boot server port)
EXPOSE 8100

# JVM tuning for production
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseCompactObjectHeaders", \
    "-XX:+UseStringDeduplication", \
    "-Dsun.net.inetaddr.ttl=60", \
    "-Dspring.profiles.active=prod", \
    "-jar", \
    "packagenameguardian.jar"]
