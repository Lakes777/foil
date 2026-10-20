#!/usr/bin/env bash
# Publica uma versão nova na VM: monta as quatro imagens aqui (para ARM), manda pela conexão SSH, sobe na ordem
# (Redpanda, pagamentos, depois estoque e pedidos) e confere a saúde. A VM não precisa do código, do JDK nem do
# Node, só do Docker. As migrações rodam na partida de cada serviço, com o usuário dono do banco dele.
# Uso: deploy/publicar.sh   (a primeira vez: deploy/preparar-vm.sh antes)
set -euo pipefail

VM="${VM:-ubuntu@159.112.189.95}"
CHAVE_SSH="${CHAVE_SSH:-$HOME/.ssh/oracle_bot}"
DOMINIO="${DOMINIO:-foil.andrelagos.dev}"
cd "$(dirname "$0")/.."
SSH=(ssh -i "$CHAVE_SSH" -o BatchMode=yes)
SERVICOS=(pedidos estoque pagamentos)

# Só publica o que está no git: a imagem leva o commit, e dá para saber o que está no ar
if [ -n "$(git status --porcelain)" ]; then
	echo "Há mudanças sem commit. Faça o commit antes de publicar." >&2
	exit 1
fi
commit=$(git rev-parse --short HEAD)

if ! "${SSH[@]}" "$VM" "test -f foil/.env"; then
	echo "Falta o .env na VM. Rode deploy/preparar-vm.sh antes." >&2
	exit 1
fi

echo "Montando as imagens ARM ($commit)..."
for s in "${SERVICOS[@]}"; do
	docker buildx build -q --load --platform linux/arm64 --target servico --build-arg SERVICO="$s" \
		--build-arg COMMIT="$commit" -t "foil-$s:$commit" .
done
docker buildx build -q --load --platform linux/arm64 --target web --build-arg COMMIT="$commit" -t "foil-web:$commit" .

# Na VM, as imagens chegam só com a tag do commit; o "latest" (o que o compose usa) muda logo antes de subir
echo "Enviando para a VM (comprimidas)..."
docker save "foil-pedidos:$commit" "foil-estoque:$commit" "foil-pagamentos:$commit" "foil-web:$commit" \
	| gzip | "${SSH[@]}" "$VM" "gunzip | docker load -q"
scp -q -i "$CHAVE_SSH" deploy/compose.yaml "$VM:foil/"

# Espera um contêiner ficar healthy (até ~3 min: três JVMs disputam um núcleo na partida)
esperar() {
	"${SSH[@]}" "$VM" "for _ in \$(seq 1 60); do
		[ \"\$(docker inspect -f '{{.State.Health.Status}}' foil-$1-1 2>/dev/null)\" = healthy ] && exit 0
		sleep 3
	done
	echo 'foil-$1 não ficou healthy. Veja: cd foil && docker compose logs $1 --tail 50' >&2
	echo 'Para voltar à versão anterior: docker tag foil-<serviço>:<commit anterior> foil-<serviço>:latest' >&2
	echo '(docker images foil-$1 lista os commits) e cd foil && docker compose up -d' >&2
	exit 1"
}

echo "Subindo na ordem..."
"${SSH[@]}" "$VM" "for n in pedidos estoque pagamentos web; do docker tag foil-\$n:$commit foil-\$n:latest; done \
	&& cd foil && docker compose up -d redpanda"
esperar redpanda
# Pagamentos antes de pedidos: um pagamentos antigo mandaria o CobrarPedido novo para a fila de mortos (SPEC §7)
"${SSH[@]}" "$VM" "cd foil && docker compose up -d pagamentos"
esperar pagamentos
"${SSH[@]}" "$VM" "cd foil && docker compose up -d --remove-orphans"
esperar estoque
esperar pedidos

# O Caddy é do Beacon e serve os outros sites também. Com o foil.caddy errado, o reload recusa e segue com a
# configuração antiga; o arquivo ruim não pode ficar na pasta, senão derrubaria todos no próximo reinício. Numa
# republicação, o que funcionava volta no lugar (apagar tiraria o Foil do ar no próximo reinício)
"${SSH[@]}" "$VM" "if [ -f beacon/sites/foil.caddy ]; then cp beacon/sites/foil.caddy foil/foil.caddy.anterior; else rm -f foil/foil.caddy.anterior; fi"
sed "s/DOMINIO_FOIL/$DOMINIO/g" deploy/foil.caddy | "${SSH[@]}" "$VM" "cat > beacon/sites/foil.caddy"
if ! "${SSH[@]}" "$VM" "cd beacon && docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile"; then
	"${SSH[@]}" "$VM" "if [ -f foil/foil.caddy.anterior ]; then mv foil/foil.caddy.anterior beacon/sites/foil.caddy; else rm -f beacon/sites/foil.caddy; fi"
	echo "O Caddy recusou o foil.caddy novo: voltei o anterior (ou tirei, na primeira vez); os outros sites seguem como estavam." >&2
	exit 1
fi

# Tira as tags de commits antigos (senão as imagens velhas ficam ocupando o disco) e o prune apaga o resto
"${SSH[@]}" "$VM" "for n in foil-pedidos foil-estoque foil-pagamentos foil-web; do
	docker images \$n --format '{{.Tag}}' | grep -vxE 'latest|$commit' | xargs -r -I{} docker rmi -f \$n:{} >/dev/null
done; docker image prune -f >/dev/null"

echo "Conferindo pelo endereço público (na primeira vez, o certificado HTTPS pode levar um minuto)..."
codigo() { curl -s -o /dev/null -w '%{http_code}' --path-as-is "https://$DOMINIO$1"; }
for _ in $(seq 1 40); do
	if [ "$(codigo /)" = 200 ] && [ "$(codigo /api/estoque/vitrine)" = 200 ]; then
		# O que a interface usa passa; a fila de mortos não sai da VM; truque de caminho é recusado
		lancamentos=$(codigo /api/pedidos/lancamentos)
		mortos=$(codigo /api/pedidos/mortos)
		truque=$(codigo '/api/pedidos/pedidos/x/..%2f..%2fmortos')
		if [ "$lancamentos" = 200 ] && [ "$mortos" = 404 ] && [ "$truque" = 400 ]; then
			echo "No ar: https://$DOMINIO ($commit)"
			exit 0
		fi
		echo "Respondeu, mas as rotas não estão como deviam: lancamentos $lancamentos (200), mortos $mortos (404), truque $truque (400)." >&2
		exit 1
	fi
	sleep 3
done
echo "O Foil não respondeu pelo endereço público. Veja os logs do Caddy: cd beacon && docker compose logs caddy" >&2
exit 1
