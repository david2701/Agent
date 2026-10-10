# syntax=docker/dockerfile:1.7
# Kairo: una sola imagen con la API (Ktor) y el dashboard web compilado.

# --- 1. Dashboard (Vite + React) ---
FROM node:22-alpine AS dashboard
WORKDIR /dashboard
COPY dashboard/package.json dashboard/package-lock.json* ./
RUN npm ci --no-audit --no-fund
COPY dashboard/ ./
COPY design/ /design/
RUN npm run build

# --- 2. Backend (Kotlin/Ktor, fat jar) ---
FROM eclipse-temurin:21-jdk AS backend
# Opcional: espejo de Maven Central (p. ej. si repo1 limita peticiones)
ARG MAVEN_MIRROR=""
WORKDIR /src
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts backend/gradle.properties ./
COPY backend/gradle ./gradle
RUN if [ -n "$MAVEN_MIRROR" ]; then mkdir -p /root/.gradle/init.d && printf 'settingsEvaluated { s -> s.pluginManagement.repositories.all { r -> if (r instanceof MavenArtifactRepository && r.url.toString().contains("repo.maven.apache.org")) r.url = "%s" }; s.dependencyResolutionManagement.repositories.all { r -> if (r instanceof MavenArtifactRepository && r.url.toString().contains("repo.maven.apache.org")) r.url = "%s" } }\n' "$MAVEN_MIRROR" "$MAVEN_MIRROR" > /root/.gradle/init.d/mirror.gradle; fi
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY backend/src ./src
COPY --from=dashboard /dashboard/dist/ ./src/main/resources/static/
RUN ./gradlew --no-daemon buildFatJar -x test

# --- 3. Imagen final ---
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app kairo
WORKDIR /app
COPY --from=backend /src/build/libs/kairo.jar ./kairo.jar
USER kairo
ENV KAIRO_PORT=8080 JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 CMD curl -fsS http://localhost:8080/api/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/kairo.jar"]
