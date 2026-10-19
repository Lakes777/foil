package io.github.lakes777.foil.comum;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /saude}: 200 só quando o banco e o Redpanda respondem; senão 503, dizendo qual falhou.
 * É o que o Vigil e o healthcheck do Docker vão consultar.
 *
 * <p>As duas checagens rodam em paralelo, cada uma com prazo, e o resultado vale por {@link #VALIDADE}:
 * repetir a URL não abre uma conexão nova com o Redpanda a cada vez (a VM tem 1 núcleo).
 */
@RestController
public class SaudeController {

	static final int PRAZO_MS = 2000;

	static final Duration VALIDADE = Duration.ofSeconds(5);

	private static final String OK = "ok";

	private static final String FALHOU = "falhou";

	private static final Logger log = LoggerFactory.getLogger(SaudeController.class);

	private final String servico;

	private final DataSource banco;

	private final ObjectProvider<Flyway> flyway;

	private final Map<String, Object> configKafka;

	private Saude ultima;

	private long ultimaEm;

	private String migracao;

	SaudeController(@Value("${spring.application.name}") String servico, DataSource banco,
			ObjectProvider<Flyway> flyway, KafkaAdmin kafkaAdmin) {
		this.servico = servico;
		this.banco = banco;
		this.flyway = flyway;
		this.configKafka = new HashMap<>(kafkaAdmin.getConfigurationProperties());
		this.configKafka.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, PRAZO_MS);
		this.configKafka.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, PRAZO_MS);
	}

	public record Saude(String servico, String status, String banco, String kafka, String migracao) {
	}

	@GetMapping("/saude")
	ResponseEntity<Saude> saude() {
		var corpo = atual();
		return ResponseEntity.status(OK.equals(corpo.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
			.body(corpo);
	}

	synchronized Saude atual() {
		if (ultima == null || System.nanoTime() - ultimaEm > VALIDADE.toNanos()) {
			ultima = conferir();
			ultimaEm = System.nanoTime();
		}
		return ultima;
	}

	private Saude conferir() {
		try (var paralelo = Executors.newVirtualThreadPerTaskExecutor()) {
			var banco = paralelo.submit(this::banco);
			var kafka = paralelo.submit(this::kafka);
			var b = resultado(banco::get);
			var k = resultado(kafka::get);
			var ok = OK.equals(b) && OK.equals(k);
			return new Saude(servico, ok ? OK : FALHOU, b, k, ok ? migracao() : null);
		}
	}

	private static String resultado(Callable<String> checagem) {
		try {
			return checagem.call();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return FALHOU;
		}
		catch (Exception e) {
			return FALHOU;
		}
	}

	private String banco() {
		try (var conexao = banco.getConnection()) {
			return conexao.isValid(PRAZO_MS / 1000) ? OK : FALHOU;
		}
		catch (Exception e) {
			log.warn("saude: banco não respondeu: {}", e.getMessage());
			return FALHOU;
		}
	}

	private String kafka() {
		Admin admin = null;
		try {
			// Dentro do try: com o nome do Redpanda sem resolver (contêiner parado), o create já lança
			admin = Admin.create(configKafka);
			admin.describeCluster(new DescribeClusterOptions().timeoutMs(PRAZO_MS))
				.clusterId()
				.get(PRAZO_MS, TimeUnit.MILLISECONDS);
			return OK;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return FALHOU;
		}
		catch (Exception e) {
			log.warn("saude: Redpanda não respondeu: {}", e.toString());
			return FALHOU;
		}
		finally {
			if (admin != null) {
				// close() sem prazo espera as chamadas pendentes; com o Redpanda fora, isso trava o /saude
				admin.close(Duration.ZERO);
			}
		}
	}

	/** Versão da última migração (ex.: "1"); não muda com o serviço rodando, então é lida uma vez. */
	private String migracao() {
		if (migracao == null) {
			try {
				var f = flyway.getIfAvailable();
				var atual = f == null ? null : f.info().current();
				migracao = atual == null ? null : atual.getVersion().getVersion();
			}
			catch (RuntimeException e) {
				log.warn("saude: não deu para ler a versão das migrações: {}", e.getMessage());
			}
		}
		return migracao;
	}

}
