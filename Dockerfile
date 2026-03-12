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
