# syntax=docker/dockerfile:1

# Stage 1: Build — bytecode is architecture-independent, so compile once on the native build platform
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS builder
WORKDIR /app

COPY gradle/ /app/gradle/
COPY gradlew settings.gradle build.gradle /app/
# refdata-tool is included by settings.gradle, so its build script must be present
COPY refdata-tool/build.gradle /app/refdata-tool/
COPY src/ /app/src/

# The Gradle wrapper and dependency caches persist across builds in a BuildKit cache mount
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :bootJar --no-daemon -x test && \
    cp build/libs/*.jar /app/packagenameguardian.jar

# Split the jar into Spring Boot layers, from the most stable to the most volatile
RUN java -Djarmode=tools -jar /app/packagenameguardian.jar extract --layers --destination /app/extracted

# Stage 2: Minimal JRE with jlink (musl, matches runtime base; the jdk-all variant ships the jmods jlink needs)
FROM bellsoft/liberica-runtime-container:jdk-all-25-musl AS jre-builder
COPY --link --from=builder /app/packagenameguardian.jar /app/packagenameguardian.jar
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

# Stage 3: Runtime
# Alpaquita musl: Alpine-compatible, with a faster musl allocator than stock Alpine
FROM bellsoft/alpaquita-linux-base:stream-musl
ENV JAVA_HOME=/opt/jre
ENV PATH="$JAVA_HOME/bin:$PATH"
WORKDIR /app

# Run as non-root user; the application files stay owned by root and read-only for appuser
RUN addgroup -S appgroup && adduser -S appuser -G appgroup && \
    mkdir -p /app/logs && chown -R appuser:appgroup /app/logs

COPY --link --from=jre-builder /opt/jre /opt/jre
COPY --link --from=builder /app/extracted/dependencies/ /app/
COPY --link --from=builder /app/extracted/spring-boot-loader/ /app/
COPY --link --from=builder /app/extracted/snapshot-dependencies/ /app/
COPY --link --from=builder /app/extracted/application/ /app/

USER appuser

EXPOSE 8080

# Default JVM flags; override the whole variable to tune them (e.g. -e JDK_JAVA_OPTIONS="...").
ENV JDK_JAVA_OPTIONS="-XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:MaxRAMPercentage=75.0 -XX:+UseCompactObjectHeaders -XX:+UseStringDeduplication -Dsun.net.inetaddr.ttl=60 -XX:ErrorFile=/app/logs/hs_err_pid%p.log -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs"

ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "packagenameguardian.jar"]
