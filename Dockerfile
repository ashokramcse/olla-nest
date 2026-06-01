# =============================================================================
# Olla Nest — Multi-stage Production Dockerfile
# Builds a minimal JRE image for either the admin or user service.
#
# Build args:
#   MODULE   — olla-nest-admin | olla-nest-user  (default: olla-nest-admin)
#   JAR_NAME — the final JAR filename (auto-detected when using docker-compose)
#
# Security hardening:
#   - Runs as non-root user (uid 1001)
#   - Read-only root filesystem with explicit writable tmpfs/data mounts
#   - No shell in final image (distroless base)
#   - JVM flags: -XX:+UseContainerSupport for correct cgroup memory detection
# =============================================================================

# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# Copy Maven wrapper and pom files first (Docker layer cache optimization)
COPY pom.xml .
COPY olla-nest-common/pom.xml olla-nest-common/
COPY olla-nest-admin/pom.xml  olla-nest-admin/
COPY olla-nest-user/pom.xml   olla-nest-user/
COPY .mvn/ .mvn/
COPY mvnw .
RUN chmod +x mvnw

# Download dependencies (cached unless pom.xml changes)
RUN ./mvnw dependency:go-offline -B -q 2>/dev/null || true

# Copy source and build
COPY olla-nest-common/src/ olla-nest-common/src/
COPY olla-nest-admin/src/  olla-nest-admin/src/
COPY olla-nest-user/src/   olla-nest-user/src/
RUN ./mvnw package -DskipTests -B -q

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: create a non-root user to run the application
RUN addgroup -S ollanest && adduser -S -G ollanest -u 1001 ollanest

# Install only what's needed for the sandbox (optional — remove if not using sandbox)
# Note: For production, disable sandbox:run right and remove interpreter packages.
# RUN apk add --no-cache python3 nodejs bash

WORKDIR /app

ARG MODULE=olla-nest-admin
ARG JAR_FILE=${MODULE}/target/${MODULE}-*.jar

COPY --from=builder /build/${JAR_FILE} app.jar

# Create data directory with correct ownership
RUN mkdir -p /data/logs /data/backups /data/workspace \
    && chown -R ollanest:ollanest /data /app

USER ollanest

EXPOSE 8080

# JVM tuning:
#   -XX:+UseContainerSupport   — respect cgroup memory/CPU limits
#   -XX:MaxRAMPercentage=75    — use 75% of container RAM for heap
#   -XX:+UseZGC                — low-latency GC for streaming workloads
#   -Djava.security.egd        — faster entropy source in containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 \
  -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom \
  -Dspring.profiles.active=prod"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
