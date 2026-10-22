# Build context is the repo root, because the image needs both backend/ (source)
# and config/ (weights.yml, which lives outside the jar on purpose).

FROM gradle:8.14-jdk21 AS build
WORKDIR /src
# Dependency layer first: this only re-resolves when the build files change.
COPY backend/settings.gradle.kts backend/build.gradle.kts ./
RUN gradle --no-daemon dependencies --quiet || true
COPY backend/src ./src
RUN gradle --no-daemon bootJar --quiet

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN useradd --system --create-home --uid 10001 app
WORKDIR /app

COPY --from=build /src/build/libs/*.jar /app/app.jar
# Weights ship with the image but stay a file on disk, so they can be replaced by
# a mounted volume or a config map without rebuilding.
COPY config/weights.yml /app/config/weights.yml
ENV WEIGHTS_FILE=/app/config/weights.yml

RUN chown -R app:app /app
USER app

EXPOSE 8080
# JAVA_OPTS is for EXTRA flags only. The required flags are in the entrypoint below, ahead of
# $JAVA_OPTS, so a platform variable can add to them or override a value (the last -Xmx or
# -XX:G1PeriodicGCInterval wins) but can never remove them all (spec 041 FR-012). The collector
# is the exception: a second collector flag (e.g. -XX:+UseSerialGC) makes the JVM refuse to start.
# Empty by default; an empty value expands to nothing. Values with spaces can't be quoted here.
ENV JAVA_OPTS=""

# /api/health is deliberately exempt from the token filter so this works without
# handing the platform your secret.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "curl -fsS http://localhost:${PORT:-8080}/api/health || exit 1"]

# Required JVM flags (spec 041; values come from specs/041-memory-footprint/baseline.md "Sweep",
# they are measured choices, not principled ones):
#   -XX:+UseG1GC                    pinned, so a small container cannot silently pick the Serial collector.
#   -Xmx1024m                       explicit ceiling (replaces MaxRAMPercentage=75, which was 1.5 GB in a 2 GB box).
#                                   Heap 1024 + ~250 MB measured non-heap leaves headroom under 2 GB; the "worst" profile
#                                   (2 x 5,000-iteration sims + player ingest) ran without OOM.
#                                   ONLY SAFE WITH A CONTAINER LIMIT OF ~1.5 GB OR MORE. Railway's limit is 2 GB; if it is
#                                   ever lowered, lower -Xmx with it (heap + ~250-380 MB non-heap must fit, or the
#                                   container is OOM-killed instead of the JVM throwing). Measured: at a 1 GB limit this
#                                   ceiling equals the whole container.
#   -XX:G1PeriodicGCInterval=60000  after 60 s idle G1 runs a concurrent cycle that uncommits unused heap. Without it an
#                                   idle service held ~600 MB committed heap for 10+ min (all we measured) after a spike (15000 gave the
#                                   lowest memory but slower resims; 300000 recovers too late).
# Min/MaxHeapFreeRatio were swept too and made no measurable difference, so they stay at the defaults.
ENTRYPOINT ["sh", "-c", "exec java -XX:+UseG1GC -Xmx1024m -XX:G1PeriodicGCInterval=60000 $JAVA_OPTS -jar /app/app.jar"]
