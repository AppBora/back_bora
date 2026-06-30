# ---- build (com cache de dependências) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# ---- runtime (enxuto, non-root) ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 bora
COPY --from=build /app/target/bora-0.0.1-SNAPSHOT.jar app.jar
USER 1001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=4s --start-period=40s --retries=5 \
  CMD ["bash","-c","echo > /dev/tcp/localhost/8080"]
ENTRYPOINT ["java","-jar","app.jar"]
