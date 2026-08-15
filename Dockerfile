# ─────────────────────────────────────────────────────────────────────────────
# Smart Transit & Crash Telemetry Hub — Dockerfile
# Builds a fat JAR from the Maven project and runs it on Railway / any container host.
#
# Multi-stage build:
#   Stage 1 (builder) : Maven + JDK 17 → compiles & packages the fat JAR
#   Stage 2 (runtime) : JRE 17 slim    → runs the JAR (smaller final image)
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy pom first so Docker caches dependency layer
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build fat JAR
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the shaded fat JAR from the builder stage
COPY --from=builder /app/target/smart-transit-hub-1.0.0-SNAPSHOT.jar app.jar

# Railway injects PORT env var; default to 8080 locally
ENV PORT=8080

EXPOSE 8080

# Environment variables for DB connection (set in Railway dashboard):
#   DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASS
ENTRYPOINT ["java", "-jar", "app.jar"]
