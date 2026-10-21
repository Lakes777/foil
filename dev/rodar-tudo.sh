#!/usr/bin/env bash
# Sobe o Foil inteiro no PC para usar no navegador: Postgres e Redpanda (compose), o Fiel (jar + tela, do repositório
# vizinho ../fiel), os três serviços e a interface. Mesmo arranjo do ponta-a-ponta (CompraPeloFielTest).
#
#   dev/rodar-tudo.sh          sobe tudo (monta os jars antes, se faltar)
#   dev/rodar-tudo.sh parar    derruba os processos (o compose continua; docker compose down para parar)
#
# Endereços: loja http://localhost:5195 · Fiel http://localhost:5190 · console do Redpanda http://localhost:8090
# Logs em dev/logs/. Precisa de Java 25, Node e Docker.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
FIEL="${FIEL_DIR:-$RAIZ/../fiel}"
LOGS="$RAIZ/dev/logs"
PIDS="$RAIZ/dev/.pids"
# Só no PC: a mesma chave nos dois lados (na VM ela vem do .env)
CHAVE="chave-de-dev-do-foil-0123456789abcdef"

parar() {
  if [[ -f "$PIDS" ]]; then
    while read -r pid; do kill "$pid" 2>/dev/null || true; done <"$PIDS"
    rm -f "$PIDS"
    echo "processos parados"
  fi
}

if [[ "${1:-}" == "parar" ]]; then
  parar
  exit 0
fi

parar
mkdir -p "$LOGS"

esperar() { # esperar <nome> <url>
  for _ in $(seq 1 90); do
    if curl -fs -o /dev/null "$2"; then echo "  $1 no ar"; return; fi
    sleep 2
  done
  echo "  $1 não subiu: veja $LOGS/$1.log" >&2
  exit 1
}

fundo() { # fundo <nome> <comando...>: roda em segundo plano, guarda o pid
  local nome="$1"; shift
  "$@" >"$LOGS/$nome.log" 2>&1 &
  echo $! >>"$PIDS"
}

echo "compose (Postgres 5434, Redpanda 19092)"
docker compose -f "$RAIZ/compose.yaml" up -d banco redpanda console >/dev/null
for _ in $(seq 1 30); do
  docker compose -f "$RAIZ/compose.yaml" exec -T banco pg_isready -U foil >/dev/null 2>&1 && break
  sleep 1
done
docker compose -f "$RAIZ/compose.yaml" exec -T banco psql -U foil -d foil -tc \
  "select 1 from pg_database where datname = 'fiel'" | grep -q 1 ||
  docker compose -f "$RAIZ/compose.yaml" exec -T banco psql -U foil -d foil -c "create database fiel owner foil" >/dev/null

if ! ls "$RAIZ"/estoque/target/*-exec.jar >/dev/null 2>&1; then
  echo "montando os jars do Foil"
  (cd "$RAIZ" && ./mvnw -q -B install -DskipTests)
fi
JAR_DO_FIEL="$(ls "$FIEL"/build/libs/fiel-*.jar 2>/dev/null | grep -v plain | head -1 || true)"
if [[ -z "$JAR_DO_FIEL" ]]; then
  echo "montando o jar do Fiel"
  (cd "$FIEL" && ./gradlew -q --no-daemon bootJar)
  JAR_DO_FIEL="$(ls "$FIEL"/build/libs/fiel-*.jar | grep -v plain | head -1)"
fi

BANCO="jdbc:postgresql://localhost:5434"
echo "Fiel (API 8085)"
fundo fiel env FIEL_PORTA=8085 FIEL_BANCO_URL="$BANCO/fiel" FIEL_BANCO_USUARIO=foil FIEL_BANCO_SENHA=foil \
  FIEL_INTEGRACAO_CHAVE="$CHAVE" FIEL_DEMO=true FIEL_CABECALHOS_DO_PROXY=native \
  java -Xmx300m -jar "$JAR_DO_FIEL" --fiel.avisos.permitir-rede-local=true --server.servlet.session.cookie.secure=false
esperar fiel http://localhost:8085/saude

servico() { # servico <nome> <porta> [variáveis...]
  local nome="$1" porta="$2"; shift 2
  fundo "$nome" env PORTA="$porta" FOIL_BANCO_URL="$BANCO/foil_$nome" FOIL_KAFKA=localhost:19092 "$@" \
    java -Xmx300m -jar "$(ls "$RAIZ/$nome"/target/*-exec.jar)" --spring.profiles.active=dev
}
echo "Foil (estoque 8092, pedidos 8091, pagamentos 8093)"
servico estoque 8092
servico pedidos 8091
servico pagamentos 8093 FOIL_FIEL_API=http://localhost:8085 FOIL_FIEL_CHAVE="$CHAVE" \
  FOIL_FIEL_SITE=http://localhost:5190 FOIL_FIEL_WEBHOOK=http://localhost:8093/fiel/avisos
esperar estoque http://localhost:8092/saude
esperar pedidos http://localhost:8091/saude
esperar pagamentos http://localhost:8093/saude

echo "telas (Fiel 5190, loja 5195)"
fundo fiel-web env FIEL_API=http://localhost:8085 npm --prefix "$FIEL/web" run dev -- --host 127.0.0.1
fundo foil-web npm --prefix "$RAIZ/web" run dev -- --host 127.0.0.1
esperar fiel-web http://localhost:5190
esperar foil-web http://localhost:5195

echo
echo "pronto: abra http://localhost:5195 (a loja). Para parar: dev/rodar-tudo.sh parar"
