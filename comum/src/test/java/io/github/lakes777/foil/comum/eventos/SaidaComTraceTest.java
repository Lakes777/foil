package io.github.lakes777.foil.comum.eventos;

import static io.github.lakes777.foil.comum.LeitorDeTopico.doPedido;
import static io.github.lakes777.foil.comum.LeitorDeTopico.traceDa;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.redpanda.RedpandaContainer;

import io.github.lakes777.foil.comum.ConteineresDeTeste;
import io.github.lakes777.foil.comum.LeitorDeTopico;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.Topicos;
import io.micrometer.tracing.Tracer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fase 9: a mensagem sai no trace de quem a gravou, mesmo publicada depois, pela agenda, fora de qualquer trace.
 * Contraprova: sem o {@code rastro.reabrir} no publicador, cada mensagem sai num trace novo e os dois primeiros testes
 * reprovam.
 */
@SpringBootTest(properties = { "spring.application.name=teste", "spring.flyway.locations=classpath:db/teste-comum",
		"foil.saida.automatica=false" })
@Import(ConteineresDeTeste.class)
class SaidaComTraceTest {

	private static final String TOPICO = Topicos.PEDIDOS_EVENTOS;

	@Autowired
	Saida saida;

	@Autowired
	PublicadorDaSaida publicador;

	@Autowired
	Rastro rastro;

	@Autowired
	Tracer tracer;

	@Autowired
	TransactionTemplate transacao;

	@Autowired
	JsonMapper json;

	@Autowired
	RedpandaContainer redpanda;

	private Envelope evento(UUID pedido) {
		return Envelope.novo("ComTrace", 1, pedido, json.readTree("{}"), Clock.systemUTC());
	}

	/** Roda {@code oQue} dentro de um span novo (um trace só dele); devolve o que ele devolveu. */
	private <T> T numTraceNovo(Supplier<T> oQue) {
		try (var escopo = rastro.novoTrace("teste")) {
			return oQue.get();
		}
	}

	private String traceDaMensagem(UUID pedido) {
		try (var leitor = new LeitorDeTopico(redpanda.getBootstrapServers(), TOPICO)) {
			var lidos = leitor.ler(doPedido(pedido), 1, Duration.ofSeconds(15));
			assertThat(lidos).hasSize(1);
			return traceDa(lidos.getFirst());
		}
	}

	@Test
	void aMensagemSaiNoTraceDeQuemGravou() {
		var pedido = UUID.randomUUID();
		var trace = numTraceNovo(() -> {
			transacao.executeWithoutResult(t -> saida.registrar(TOPICO, evento(pedido)));
			return tracer.currentSpan().context().traceId();
		});

		// A agenda publica fora de qualquer trace
		assertThat(tracer.currentSpan()).isNull();
		publicador.publicarPendentes();

		assertThat(traceDaMensagem(pedido)).isEqualTo(trace);
	}

	@Test
	void oTraceGuardadoDoPedidoValeQuandoAgoraEOutroTrace() {
		var pedido = UUID.randomUUID();
		var doPedido = numTraceNovo(rastro::atual);

		// Como o aviso do Fiel: chega num trace próprio, mas a resposta pertence à saga do pedido
		var outro = numTraceNovo(() -> {
			transacao.executeWithoutResult(t -> saida.registrar(TOPICO, evento(pedido), doPedido));
			return tracer.currentSpan().context().traceId();
		});
		publicador.publicarPendentes();

		assertThat(traceDaMensagem(pedido)).isEqualTo(Rastro.idDoTrace(doPedido)).isNotEqualTo(outro);
	}

	@Test
	void foraDeQualquerTraceOGuardadoVale() {
		var pedido = UUID.randomUUID();
		var doPedido = numTraceNovo(rastro::atual);

		// Como o prazo vencido: a agenda não abre trace
		transacao.executeWithoutResult(t -> saida.registrar(TOPICO, evento(pedido), doPedido));
		publicador.publicarPendentes();

		assertThat(traceDaMensagem(pedido)).isEqualTo(Rastro.idDoTrace(doPedido));
	}

	@Test
	void semTraceNenhumAMensagemAbreUmNovo() {
		var pedido = UUID.randomUUID();
		transacao.executeWithoutResult(t -> saida.registrar(TOPICO, evento(pedido)));
		publicador.publicarPendentes();

		// O envio ganha um trace próprio (o KafkaTemplate abre um), e nada vaza para quem chamou
		assertThat(traceDaMensagem(pedido)).hasSize(32);
		assertThat(tracer.currentSpan()).isNull();
	}

	@Test
	void escolherPrefereOSpanDeAgoraQuandoEOMesmoTrace() {
		var doPedido = numTraceNovo(rastro::atual);
		// Um filho no mesmo trace (como o consumidor da saga): o span dele é o pai mais preciso
		try (var escopo = rastro.reabrir(doPedido, "consumidor")) {
			assertThat(rastro.escolher(doPedido)).isEqualTo(rastro.atual()).isNotEqualTo(doPedido);
		}
	}

}
