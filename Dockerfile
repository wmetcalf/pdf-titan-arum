# ---- Build stage ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Cache dependency downloads separately from source
COPY pom.xml .
RUN apt-get update && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*
RUN mvn dependency:go-offline -Pserver -q

COPY src ./src
RUN mvn package -Pserver -DskipTests -q

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre

# CVE-2026-114938 ("Java4Shell"): heap overflow in HotSpot's AVX-512 VBMI Base64
# decode intrinsic (JDK 17.0.6+ .. 27). Hostile PDF content reaches java.util.Base64,
# so the intrinsic is switched off for EVERY JVM this image starts. Base64 keeps
# working via the pure-Java path; only the assembly stub goes. JAVA_TOOL_OPTIONS is
# the catch-all; the engine argv (titanarum/engine.py) and the AOT bake ALSO pass the
# flag explicitly, because `docker export` drops ENV for the warm FC/gVisor rootfs.
ENV JAVA_TOOL_OPTIONS="-XX:-UseBASE64Intrinsics"
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr tesseract-ocr-all \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/target/pdf-titan-arum-server-*.jar app.jar
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

VOLUME /data/artifacts
EXPOSE 7272

ENTRYPOINT ["docker-entrypoint.sh"]
