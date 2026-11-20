#!/usr/bin/env bash
#
# Roda os testes `*IT` — os que precisam de um PostgreSQL de verdade.
#
# POR QUE ESTE SCRIPT EXISTE (18/11/2026)
#
# São 25 classes e 254 testes que NÃO rodam no `mvn test` nem no `mvn package`, e isso é
# deliberado: o `package` roda dentro do build da imagem de produção (ver Dockerfile), onde não há
# banco nenhum. O `pom.xml` exclui `**/*IT.java` de propósito e documenta o comando para rodar à
# mão.
#
# O problema não era a decisão — era que nada FORÇAVA a rodada. O comando documentado pressupõe um
# Postgres já de pé em localhost:55432, com banco, usuário e senha exatos, e é esse passo que
# ninguém lembra. Resultado: 254 testes cujo estado era desconhecido em qualquer momento dado, num
# projeto onde o deploy depende de teste verde.
#
# Aqui o banco e a rodada são UM comando.
#
#   uso:  bash testes-de-integracao.sh            # sobe o banco, roda, e DERRUBA o banco
#         bash testes-de-integracao.sh --manter   # deixa o banco de pé para investigar
#         bash testes-de-integracao.sh --so-uma NomeDoIT
#
set -uo pipefail

CONTAINER="bora-testes-it-pg"
PORTA=55432
MANTER=0
FILTRO='*IT'

while [ $# -gt 0 ]; do
  case "$1" in
    --manter)  MANTER=1; shift ;;
    --so-uma)  FILTRO="${2:?uso: --so-uma NomeDoIT}"; shift 2 ;;
    *) echo "argumento desconhecido: $1" >&2; exit 2 ;;
  esac
done

cd "$(dirname "$0")" || exit 1

dizer() { printf '\n=== %s\n' "$*"; }

# --------------------------------------------------------------------------- 1
dizer "1/5 o docker responde?"
if ! docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
  echo "ABORTADO: o docker nao responde. Suba o Docker Desktop e tente de novo." >&2
  exit 1
fi
echo "ok: docker $(docker version --format '{{.Server.Version}}')"

# --------------------------------------------------------------------------- 2
# A PORTA PODE ESTAR OCUPADA POR OUTRA COISA, e sobrescrever seria pior que falhar.
#
# Um banco de medição de alguém, outra rodada deste script em paralelo, um Postgres instalado na
# máquina. Qualquer um deles perde dado se este script assumir que a porta é dele. Então: se o que
# está lá for o container DESTE script, reaproveita; se for outra coisa, recusa e diz o que é.
dizer "2/5 a porta $PORTA esta livre?"
DONO="$(docker ps --format '{{.Names}} {{.Ports}}' 2>/dev/null | grep ":$PORTA->" | awk '{print $1}' | head -1)"
if [ -n "$DONO" ] && [ "$DONO" != "$CONTAINER" ]; then
  echo "ABORTADO: a porta $PORTA esta ocupada pelo container '$DONO', que NAO e deste script." >&2
  echo "Nao vou mexer nele: pode ser medicao de alguem ou outra rodada." >&2
  echo "Espere ele terminar, ou derrube com: docker rm -f $DONO" >&2
  exit 1
fi
# SO "ESCUTANDO" CONTA. Antes era `grep ":$PORTA "` na saida inteira do netstat, e isso casava
# tambem as conexoes de SAIDA que a rodada anterior deixou em TIME_WAIT -- dezenas delas, do proprio
# Maven falando com o container. Resultado: logo depois de uma rodada bem-sucedida o script RECUSAVA
# rodar de novo por ~2 minutos, dizendo que "algo fora do docker esta escutando". Nao estava: nao
# havia nenhuma linha LISTENING. Medido em 18/11/2026.
#
# Um script de teste que recusa rodar sem motivo e um script que as pessoas param de rodar -- e estes
# 230 testes ja nao rodam em nenhum lugar automaticamente.
if [ -z "$DONO" ] && (netstat -ano 2>/dev/null | grep -aiE "^ *TCP +[0-9.]+:$PORTA +.*LISTENING" \
                      || ss -ltn 2>/dev/null | grep -qE ":$PORTA ") >/dev/null 2>&1; then
  echo "ABORTADO: algo fora do docker esta ESCUTANDO na porta $PORTA." >&2
  echo "Quem e:" >&2
  (netstat -ano 2>/dev/null | grep -aiE ":$PORTA +.*LISTENING" || ss -ltnp 2>/dev/null | grep ":$PORTA ") >&2
  exit 1
fi
echo "ok"

# --------------------------------------------------------------------------- 3
dizer "3/5 Postgres 16 em localhost:$PORTA"
# Os valores abaixo NAO sao escolha deste script: estao fixos no @TestPropertySource das 25 classes
# `*IT`. Mudar aqui sem mudar lá faz todos falharem por conexao, e o motivo nao teria nada a ver
# com o que eles testam.
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
# max_connections=300 (medido em 19/11/2026)
#
# Cada classe de IT com @SpringBootTest levanta o proprio contexto Spring, e cada contexto abre o
# proprio pool do Hikari. Com 30 classes, o padrao do Postgres (100 conexoes) acaba no meio da
# rodada e as ultimas classes morrem com:
#
#     FATAL: sorry, too many clients already   (SQL State 53300)
#
# E e um erro que ENGANA: ele aparece como "Error creating bean 'flywayInitializer'", a mesma linha
# que um bean quebrado de verdade produz. Passei por isso ao acrescentar tres classes novas -- o
# primeiro palpite foi defeito meu no codigo, e nao era.
#
# Subir o limite e certo AQUI porque este banco e descartavel e vive num container: nao e producao,
# nao ha concorrencia real, e o custo de uma conexao parada e irrisorio. O conserto alternativo
# (pool de 2 conexoes por contexto) exigiria tocar as 30 classes para resolver um problema que e do
# banco de teste, nao delas.
docker run -d --name "$CONTAINER" \
  -e POSTGRES_DB=bora -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=teste \
  -p "$PORTA:5432" postgres:16 \
  -c max_connections=300 >/dev/null || {
    echo "ABORTADO: o container nao subiu." >&2; exit 1; }

PRONTO=0
for i in $(seq 1 60); do
  if docker exec "$CONTAINER" pg_isready -U postgres -d bora >/dev/null 2>&1; then
    echo "ok: aceitando conexao em ${i}s"
    PRONTO=1
    break
  fi
  sleep 1
done
if [ "$PRONTO" != "1" ]; then
  echo "ABORTADO: o Postgres nao ficou pronto em 60s." >&2
  docker logs --tail 20 "$CONTAINER" >&2 || true
  [ "$MANTER" = "1" ] || docker rm -f "$CONTAINER" >/dev/null 2>&1
  exit 1
fi

limpar() {
  if [ "$MANTER" = "1" ]; then
    printf '\n(o banco ficou de pe em localhost:%s — derrube com: docker rm -f %s)\n' "$PORTA" "$CONTAINER"
  else
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap limpar EXIT

# --------------------------------------------------------------------------- 4
# O Flyway roda aqui: as 25 classes pedem `spring.flyway.enabled=true` e
# `hibernate.ddl-auto=validate`. Ou seja, elas tambem provam que as migracoes aplicam num banco
# limpo e que o mapeamento JPA bate com o schema que elas produzem. E a unica coisa no projeto que
# prova isso antes de a migracao chegar em producao.
dizer "4/5 rodando os testes de integracao ($FILTRO)"
# APAGA OS RELATORIOS *IT ANTES DE RODAR, para o resumo do passo 5 contar SO esta rodada.
# Antes o passo 5 filtrava por `-newermt '-10 minutes'`, que e um chute, nao um fato -- e o chute
# errou: uma rodada completa de 8 minutos atras ainda caia na janela, e `--so-uma` (6 testes, 1
# classe) reportou "26 classes, 236 testes". Numero inflado num relatorio de teste e pior que numero
# nenhum, que e o que o comentario do passo 5 ja dizia e o codigo nao cumpria. Medido em 18/11/2026.
rm -f target/surefire-reports/*IT.txt target/surefire-reports/*IT.xml 2>/dev/null || true
mvn -o test -Dtest="$FILTRO" -DfailIfNoTests=false
CODIGO=$?

# --------------------------------------------------------------------------- 5
dizer "5/5 o resultado"
if [ -d target/surefire-reports ]; then
  # Conta so os relatorios desta rodada. Quem garante isso e o `rm -f` do passo 4, nao uma janela
  # de tempo: o diretorio comeca vazio de *IT, logo tudo o que esta aqui saiu desta rodada.
  find target/surefire-reports -name "*IT.txt" -exec grep -h "^Tests run" {} \; \
    | awk -F'[:,]' '{t+=$2; f+=$4; e+=$6; s+=$8; n++}
        END { if (n) printf "   %d classes, %d testes, %d falhas, %d erros, %d pulados\n", n, t, f, e, s;
              else print "   NENHUM relatorio desta rodada -- os testes nao rodaram." }'
fi

if [ "$CODIGO" = "0" ]; then
  echo "   VERDE"
else
  echo "   VERMELHO (codigo $CODIGO) -- veja target/surefire-reports" >&2
fi
exit "$CODIGO"
