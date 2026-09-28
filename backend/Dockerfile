# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# The jar stays root-owned; only the signing-key directory (a named volume in compose) is writable.
# A fresh volume copies this ownership. A volume created before Phase 24 is root-owned: fix it once with
#   docker compose run --rm -u 0 --entrypoint chown backend -R 10001:10001 /app/data/signing-keys
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /home/app --create-home app \
    && mkdir -p /app/data/signing-keys \
    && chown -R app:app /app/data
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER 10001:10001
EXPOSE 8080 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
