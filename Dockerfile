# =============================================================================
# AetherOps — multi-stage Dockerfile
#
# Four stages, each cached independently:
#
#  1. deps    — resolves Maven dependencies into ~/.m2; invalidated only
#               when pom.xml changes (typically rare).
#  2. build   — compiles and packages; invalidated when src/ changes.
#  3. layers  — extracts the Spring Boot layered JAR into four directories
#               so the runtime image can copy each layer separately.
#  4. runtime — minimal JRE image; only the four extract directories are
#               copied.  A code-only change (the "application" layer,
#               ~100 KB) no longer invalidates the ~80 MB "dependencies"
#               layer, cutting push/pull time dramatically on CI.
# =============================================================================

# ─── Stage 1: dependency resolution ──────────────────────────────────────────
# Separate this step so that the ~/.m2 cache layer is only busted when
# pom.xml changes, not on every source edit.
FROM maven:3.9-eclipse-temurin-17 AS deps
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q

# ─── Stage 2: compile & package ──────────────────────────────────────────────
FROM deps AS build
COPY src ./src
# -DskipTests: tests are executed in CI (mvn clean test) before this image
# is ever built.  Re-running them here doubles build time for no new signal.
RUN mvn package -DskipTests -q

# ─── Stage 3: extract Spring Boot layered JAR ────────────────────────────────
# Spring Boot 3 generates layered JARs by default via spring-boot-maven-plugin.
# jarmode=layertools splits the fat JAR into four content-addressed layers:
#   dependencies          — third-party release JARs (rarely changes)
#   spring-boot-loader    — Spring Boot bootstrap classes (rarely changes)
#   snapshot-dependencies — SNAPSHOT JARs (occasionally changes)
#   application           — compiled classes + resources (changes every commit)
FROM eclipse-temurin:17-jre-alpine AS layers
WORKDIR /build
COPY --from=build /build/target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ─── Stage 4: minimal runtime image ──────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

# Non-root user.  Running as root inside a container violates the principle of
# least privilege, fails Kubernetes PodSecurity "restricted" profiles, and
# would allow a container breakout to write to the host as root.
RUN addgroup -S aetherops && adduser -S aetherops -G aetherops

WORKDIR /app

# Copy layers in stability order (least → most frequently changed).
# Each COPY is a distinct Docker layer; unchanged layers are served from the
# registry cache without re-download, making deploys faster.
COPY --from=layers /build/dependencies/          ./
COPY --from=layers /build/spring-boot-loader/    ./
COPY --from=layers /build/snapshot-dependencies/ ./
COPY --from=layers /build/application/           ./

RUN chown -R aetherops:aetherops /app
USER aetherops

EXPOSE 8080

# JVM flags chosen for Kubernetes container environments:
#
#   -XX:+UseContainerSupport
#       Honour the cgroup CPU quota and memory limit instead of reading
#       /proc/cpuinfo and total host RAM. Without this the JVM would
#       over-provision threads and heap for the underlying node.
#
#   -XX:MaxRAMPercentage=75.0
#       Cap the heap at 75 % of the container memory limit.  The remaining
#       25 % is headroom for Metaspace, thread stacks, direct buffers, and
#       the GC bookkeeping that lives outside the heap. With a 1 Gi limit
#       this yields ~768 Mi heap — safe for a Spring Boot service under load.
#
#   -XX:+ExitOnOutOfMemoryError
#       Crash immediately and let the pod be restarted rather than allowing
#       the JVM to limp in a partially broken OOM state.  This makes OOM
#       failures visible and deterministic instead of silent and slow.
#
#   -Djava.security.egd=file:/dev/./urandom
#       Speed up SecureRandom seeding on Linux.  The /dev/random device can
#       block waiting for entropy; urandom is cryptographically safe for
#       session tokens and JWT signing and will not stall startup.
#
# The entrypoint calls JarLauncher directly (Spring Boot 3.2+ launcher path)
# rather than "java -jar app.jar" so that the layered class layout is used
# correctly from the extracted directories.
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]
