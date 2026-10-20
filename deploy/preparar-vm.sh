#!/usr/bin/env bash
# Prepara a VM uma vez só (rodar de novo não estraga nada): os três usuários e bancos do Foil no Postgres do
# Beacon (foil_pedidos, foil_estoque, foil_pagamentos; cada usuário dono só do seu banco, sem superpoder), o .env
# com as senhas e a chave de integração (geradas NA VM, ficam só lá) e a mesma chave no .env do Fiel, que é
# reiniciado para a Loja Foil passar a existir.
# Uso: deploy/preparar-vm.sh
set -euo pipefail

VM="${VM:-ubuntu@159.112.189.95}"
CHAVE_SSH="${CHAVE_SSH:-$HOME/.ssh/oracle_bot}"
DOMINIO="${DOMINIO:-foil.andrelagos.dev}"
SSH=(ssh -i "$CHAVE_SSH" -o BatchMode=yes)

"${SSH[@]}" "$VM" DOMINIO="$DOMINIO" bash -s <<'REMOTO'
set -euo pipefail
mkdir -p ~/foil
if [ -f ~/foil/.env ]; then
	echo "O .env do Foil já existe: usuários, bancos e chave ficam como estão."
else
	pedidos=$(openssl rand -hex 24)
	estoque=$(openssl rand -hex 24)
	pagamentos=$(openssl rand -hex 24)
	chave=$(openssl rand -hex 32)
	# A rede do Caddy (ex.: 172.30.0.0/24) vira a regex dos proxies confiáveis de pedidos (como no Fiel)
	rede=$(docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}' beacon_interna)
	case "$rede" in
		*/24) confiaveis="$(printf '%s' "${rede%.*/24}" | sed 's/\./\\./g')\\.\\d{1,3}" ;;
		*) echo "A rede do Caddy ($rede) não é /24: ajuste FOIL_PROXIES_CONFIAVEIS à mão." >&2; exit 1 ;;
	esac
	cd ~/beacon
	# Pela entrada padrão (e não como argumento): as senhas não aparecem na lista de processos. Repetível:
	# cria só o que falta e (re)define as senhas. O log_min_error_statement impede que um comando que dê erro
	# vá para o log do Postgres com a senha dentro.
	docker compose exec -T banco psql -U beacon -d beacon -v ON_ERROR_STOP=1 -q <<SQL
set log_min_error_statement = panic;
select 'create role foil_pedidos login' where not exists (select from pg_roles where rolname = 'foil_pedidos') \gexec
select 'create role foil_estoque login' where not exists (select from pg_roles where rolname = 'foil_estoque') \gexec
select 'create role foil_pagamentos login' where not exists (select from pg_roles where rolname = 'foil_pagamentos') \gexec
alter role foil_pedidos password '$pedidos';
alter role foil_estoque password '$estoque';
alter role foil_pagamentos password '$pagamentos';
select 'create database foil_pedidos owner foil_pedidos' where not exists (select from pg_database where datname = 'foil_pedidos') \gexec
select 'create database foil_estoque owner foil_estoque' where not exists (select from pg_database where datname = 'foil_estoque') \gexec
select 'create database foil_pagamentos owner foil_pagamentos' where not exists (select from pg_database where datname = 'foil_pagamentos') \gexec
revoke connect on database foil_pedidos from public;
revoke connect on database foil_estoque from public;
revoke connect on database foil_pagamentos from public;
SQL
	umask 077
	# Aspas simples: o compose lê este arquivo para preencher o compose.yaml, e a regex tem barras invertidas
	cat > ~/foil/.env <<ENV
DOMINIO=$DOMINIO
FOIL_SENHA_PEDIDOS=$pedidos
FOIL_SENHA_ESTOQUE=$estoque
FOIL_SENHA_PAGAMENTOS=$pagamentos
FOIL_FIEL_CHAVE=$chave
FOIL_PROXIES_CONFIAVEIS='$confiaveis'
ENV
	echo "Usuários e bancos foil_pedidos, foil_estoque e foil_pagamentos e o .env prontos (proxies: $confiaveis)."
fi

# A mesma chave no Fiel: sem ela, a Loja Foil não existe e toda compra pela loja é recusada. Só um Fiel com a
# chave de integração e a V9 (d48b252 ou depois) sabe o que fazer com ela
revisao=$(docker inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' fiel:latest)
if ! (cd ~/beacon && docker compose exec -T banco psql -U beacon -d fiel_demo -tAc \
	"select 1 from flyway_schema_history where version = '9' and success") | grep -q 1; then
	echo "O Fiel no ar ($revisao) ainda não tem a V9: publique o Fiel d48b252 ou mais novo antes (/publicar-vm fiel)." >&2
	exit 1
fi
# Compara o valor, e não só se existe: um .env do Foil refeito tem chave nova, e o Fiel ficaria com a velha
nova=$(grep '^FOIL_FIEL_CHAVE=' ~/foil/.env | cut -d= -f2-)
atual=$(grep '^FIEL_INTEGRACAO_CHAVE=' ~/fiel/.env | cut -d= -f2- || true)
if [ "$nova" = "$atual" ]; then
	echo "O Fiel ($revisao) já tem a mesma chave de integração."
else
	sed -i '/^FIEL_INTEGRACAO_CHAVE=/d' ~/fiel/.env
	(umask 077; printf 'FIEL_INTEGRACAO_CHAVE=%s\n' "$nova" >> ~/fiel/.env)
	# Recria só a API do Fiel (lê o .env de novo); a Loja Foil nasce na partida
	(cd ~/fiel && docker compose up -d api)
	for _ in $(seq 1 40); do
		[ "$(docker inspect -f '{{.State.Health.Status}}' fiel-api-1)" = healthy ] && break
		sleep 3
	done
	[ "$(docker inspect -f '{{.State.Health.Status}}' fiel-api-1)" = healthy ] \
		|| { echo "O Fiel não voltou healthy depois da chave: veja 'cd ~/fiel && docker compose logs api'." >&2; exit 1; }
	echo "Chave de integração no Fiel ($revisao), que voltou healthy."
fi
REMOTO
