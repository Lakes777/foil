# syntax=docker/dockerfile:1
# As imagens do Foil, montadas no PC (x86) para a VM ARM (deploy/publicar.sh):
#   docker buildx build --platform linux/arm64 --target servico --build-arg SERVICO=pedidos -t foil-pedidos .
#   docker buildx build --platform linux/arm64 --target web -t foil-web .
# A compilação roda na arquitetura de quem monta ($BUILDPLATFORM), sem emulação: bytecode e JavaScript são
# iguais em qualquer processador. Só as etapas finais são ARM.

# 1. Os três serviços de uma vez (dividem contratos e comum). O jar que roda sozinho é o *-exec.jar
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS compilacao
WORKDIR /fonte
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY contratos contratos
COPY comum comum
COPY pedidos pedidos
COPY estoque estoque
COPY pagamentos pagamentos
# O ponta-a-ponta fica de fora da montagem (é só teste), mas o pom pai lista o módulo
COPY ponta-a-ponta/pom.xml ponta-a-ponta/pom.xml
# Memória contida: o PC que monta divide a RAM com o Docker. O ~/.m2 fica entre uma montagem e outra
ENV MAVEN_OPTS="-Xmx1g"
RUN --mount=type=cache,target=/root/.m2 \
	./mvnw -B -q -DskipTests -pl pedidos,estoque,pagamentos -am package \
	&& mkdir /jars \
	&& for s in pedidos estoque pagamentos; do cp $s/target/$s-*-exec.jar /jars/$s.jar; done

# 2. Um serviço (SERVICO = pedidos, estoque ou pagamentos)
FROM eclipse-temurin:25-jre-alpine AS servico
ARG SERVICO
ARG COMMIT=desconhecido
LABEL org.opencontainers.image.revision=$COMMIT
RUN addgroup -S foil && adduser -S -G foil foil
WORKDIR /app
COPY --from=compilacao /jars/${SERVICO}.jar app.jar
# O processo não roda como root
USER foil
# Cabe no limite do contêiner (compose: 448 MB): heap de até 256 MB e teto no resto (metaspace, código
# compilado, memória direta do cliente do Kafka). Serial GC: a VM tem um núcleo, sem sobra para o G1.
ENV JAVA_TOOL_OPTIONS="-Xmx256m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m \
-XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=48m"
# A porta de cada um vem do compose (PORTA); o /saude responde 503 se o banco ou o Redpanda caírem
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
	CMD wget -qO- "http://127.0.0.1:${PORTA}/saude" > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

# 3. As telas: o build do Vite servido por um Caddy mínimo (sem HTTPS: o Caddy da VM fica na frente)
FROM --platform=$BUILDPLATFORM node:24-alpine AS compilacao-web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web ./
RUN npm run build

FROM caddy:2-alpine AS web
ARG COMMIT=desconhecido
LABEL org.opencontainers.image.revision=$COMMIT
COPY deploy/web.Caddyfile /etc/caddy/Caddyfile
COPY --from=compilacao-web /web/dist /srv
