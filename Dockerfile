# ---- build (com cache de dependências) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
# Os testes rodam AQUI, no build da imagem que vai para producao. Antes a imagem era construida com
# -DskipTests: os testes existiam, passavam na minha maquina, e nada garantia que tinham rodado antes
# do deploy -- dependia de alguem lembrar. Teste que nao e obrigatorio vira decoracao.
# Se um teste falhar, a imagem nao nasce, e e exatamente isso que se quer.
RUN mvn -q package

# ---- runtime (enxuto, non-root) ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 bora
COPY --from=build /app/target/bora-0.0.1-SNAPSHOT.jar app.jar
USER 1001
EXPOSE 8080
# Fuso explicito. O container roda em UTC e a loja opera 18h-23h de Brasilia, que e 21h-02h em UTC:
# o cupom com validade "hoje" vencia as 21h, no meio do jantar, e o corte do mes no faturamento da
# plataforma saia 3 horas adiantado. O codigo novo usa America/Sao_Paulo explicito, mas o default da
# JVM tem que estar certo para o que ainda nao foi revisado.
#
# MaxRAMPercentage: o default da JVM e ~25% da RAM, uns 485 MB numa instancia de 2 GB. Como aqui a
# JVM e praticamente o unico processo do container, 60% e mais honesto.
#
# ExitOnOutOfMemoryError: sem isto, um OutOfMemory deixa a JVM viva e meio morta, respondendo o
# health e nao atendendo. Com isto ela morre e o `--restart unless-stopped` religa.
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=America/Sao_Paulo -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
# Porta aberta nao e aplicacao sadia: o healthcheck antigo (`echo > /dev/tcp`) passava com a
# aplicacao recusando tudo, inclusive com o banco fora do ar.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD ["bash","-c","curl -fs localhost:8080/actuator/health | grep -q UP"]
ENTRYPOINT ["java","-jar","app.jar"]
