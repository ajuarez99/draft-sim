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
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# /api/health is deliberately exempt from the token filter so this works without
# handing the platform your secret.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "curl -fsS http://localhost:${PORT:-8080}/api/health || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
