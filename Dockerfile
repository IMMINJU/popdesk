# syntax=docker/dockerfile:1
# The popdesk image: both web apps built into the server, one container (architecture.md section 2).
#   docker build -t popdesk:test .
# Base images are pinned to exact versions and to the digest of their multi-arch index (amd64 and
# arm64), so a rebuild of a release tag starts from the same layers.
# The web and server stages give files that do not depend on the CPU, so they run on the machine that
# builds ($BUILDPLATFORM); only the runtime stage is built once for each architecture.

# 1. The customer portal and the agent console (Vite builds).
FROM --platform=$BUILDPLATFORM node:24.18.1-alpine3.24@sha256:f70403e87646dc51b45295f4b8b70cdad0b63d2297c4c9899119b03f7af7a6b3 AS web
WORKDIR /web
RUN corepack enable
COPY web/ ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile \
 && pnpm --filter @popdesk/portal --filter @popdesk/console run build

# 2. The server, with the two builds as its static files (io.popdesk.AppScreens), extracted into a
#    thin jar plus lib/ for a faster start. The Maven image has the version mvnw names; the JDK image
#    has no download tool for mvnw.
FROM --platform=$BUILDPLATFORM maven:3.9.16-eclipse-temurin-25-noble@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS server
WORKDIR /build
COPY server/ ./
COPY --from=web /web/portal/dist/ src/main/resources/static/portal/
COPY --from=web /web/console/dist/ src/main/resources/static/console/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package \
 && cp target/popdesk-server-*.jar popdesk.jar \
 && java -Djarmode=tools -jar popdesk.jar extract --destination /out

# 3. The runtime: a JRE, and the PostgreSQL 16 client tools for popdesk backup and restore
#    (pg_dump must have the server's major version, S0-AC-89).
FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client-16 \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 popdesk \
 && useradd --system --uid 10001 --gid popdesk --home-dir /app --shell /usr/sbin/nologin popdesk \
 && mkdir -p /data/attachments \
 && chown popdesk:popdesk /data/attachments
COPY --from=server /out/ /app/
COPY deploy/image/popdesk /usr/local/bin/popdesk
RUN sed -i 's/\r$//' /usr/local/bin/popdesk && chmod 755 /usr/local/bin/popdesk
USER popdesk
WORKDIR /app
EXPOSE 8080 8081
# The JVM options are part of the image, so the app behaves the same on every host (S0-AC-64):
# - G1, named here, because the JVM would otherwise pick Serial on a small container.
# - The heap is 384 MB by default (architecture.md section 7): MaxRAM=512m with MaxRAMPercentage=75.
#   Written this way, an -Xmx in JDK_JAVA_OPTIONS still wins; JDK_JAVA_OPTIONS is left to the operator.
# No entry point: `docker compose run --rm app popdesk restore <file>` runs the command instead.
CMD ["java", "-XX:+UseG1GC", "-XX:MaxRAM=512m", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", \
     "-jar", "/app/popdesk.jar"]
