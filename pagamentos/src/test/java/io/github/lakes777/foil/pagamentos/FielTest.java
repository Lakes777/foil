package io.github.lakes777.foil.pagamentos;

import static io.github.lakes777.foil.comum.LeitorDeTopico.doPedido;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.redpanda.RedpandaContainer;

import io.github.lakes777.foil.comum.ConteineresDeTeste;
import io.github.lakes777.foil.comum.LeitorDeTopico;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.PagamentosComandos.CancelarCobranca;
import io.github.lakes777.foil.contratos.PagamentosComandos.CobrarPedido;
import io.github.lakes777.foil.contratos.PagamentosEventos.CobrancaCancelada;
import io.github.lakes777.foil.contratos.PagamentosEventos.CobrancaCriada;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoAprovado;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoEstornado;
import io.github.lakes777.foil.contratos.PagamentosEventos.PagamentoRecusado;
import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/**
 * A compra manual pelo Fiel, de ponta a ponta dentro de pagamentos: o comando chega pelo Redpanda, o trabalhador
 * fala com o {@link FielDeMentira} por HTTP, o aviso assinado volta por HTTP e a resposta à saga sai no tópico.
 * A porta do serviço é fixa (escolhida livre na partida) porque o endereço dos avisos vai no cadastro do Fiel.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
		properties = { "foil.fiel.intervalo-ms=200", "foil.fiel.conferir-a-cada=10s", "foil.fiel.site=https://fiel" })
@Import(ConteineresDeTeste.class)
class FielTest {

	private static final Duration PRAZO = Duration.ofSeconds(20);

	private static final FielDeMentira FIEL;

	private static final int PORTA;

	static {
		try (var livre = new ServerSocket(0)) {
			PORTA = livre.getLocalPort();
			FIEL = new FielDeMentira();
		}
		catch (IOException erro) {
			throw new UncheckedIOException(erro);
		}
	}

	@DynamicPropertySource
	static void fiel(DynamicPropertyRegistry registro) {
		registro.add("server.port", () -> PORTA);
		registro.add("foil.fiel.api", FIEL::endereco);
		registro.add("foil.fiel.chave", () -> FielDeMentira.CHAVE);
		registro.add("foil.fiel.webhook", () -> "http://127.0.0.1:" + PORTA + "/fiel/avisos");
	}

	@AfterAll
	static void desligar() {
		FIEL.close();
	}

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	RedpandaContainer redpanda;

	@Autowired
	AvisosDoFiel avisos;

	private LeitorDeTopico eventos;

	@BeforeEach
	void abrir() {
		FIEL.foraDoAr = false;
		FIEL.semSegredo = false;
		FIEL.valorMaximo = Integer.MAX_VALUE;
		eventos = new LeitorDeTopico(redpanda.getBootstrapServers(), Topicos.PAGAMENTOS_EVENTOS);
	}

	@AfterEach
	void fechar() {
		eventos.close();
	}

	private void enviar(UUID pedido, String tipo, int versao, Object dados) throws Exception {
		var envelope = Envelope.novo(tipo, versao, pedido, json.valueToTree(dados), Clock.systemUTC());
		kafka.send(Topicos.PAGAMENTOS_COMANDOS, pedido.toString(), json.writeValueAsString(envelope)).get();
	}

	private void cobrarNoFiel(UUID pedido, int valor) throws Exception {
		enviar(pedido, CobrarPedido.TIPO, CobrarPedido.VERSAO, new CobrarPedido(valor, CobrarPedido.Forma.FIEL));
	}

	private void cancelar(UUID pedido) throws Exception {
		enviar(pedido, CancelarCobranca.TIPO, CancelarCobranca.VERSAO, new CancelarCobranca());
	}

	private List<Envelope> respostas(UUID pedido, String tipo, int quantas, Duration prazo) {
		return eventos.ler(doPedido(pedido).and(r -> tipo.equals(tipoDe(r))), quantas, prazo)
			.stream()
			.map(r -> json.readValue(r.value(), Envelope.class))
			.toList();
	}

	private Envelope resposta(UUID pedido, String tipo) {
		var lidas = respostas(pedido, tipo, 1, PRAZO);
		assertThat(lidas).as("%s do pedido %s", tipo, pedido).hasSize(1);
		return lidas.getFirst();
	}

	private String tipoDe(ConsumerRecord<String, String> r) {
		return json.readValue(r.value(), Envelope.class).tipo();
	}

	private List<String> tiposDoPedido(UUID pedido, Duration durante) {
		return eventos.lerDurante(doPedido(pedido), durante).stream().map(this::tipoDe).toList();
	}

	private String situacao(UUID pedido) {
		return jdbc.sql("select situacao from cobranca where pedido_id = :p").param("p", pedido).query(String.class)
			.single();
	}

	@Test
	void compraPagaPeloQrDoFielViraPagamentoAprovado() throws Exception {
		var pedido = UUID.randomUUID();
		cobrarNoFiel(pedido, 1990);

		var criada = json.treeToValue(resposta(pedido, CobrancaCriada.TIPO).dados(), CobrancaCriada.class);
		var noFiel = FIEL.daReferencia(pedido);
		assertThat(criada.qr()).isEqualTo(noFiel.qr());
		assertThat(criada.linkParaPagar()).isEqualTo("https://fiel/pagar?qr=FIEL1%7C" + noFiel.id + "%7C1990");

		assertThat(FIEL.pagar(pedido, true)).isEqualTo(204);

		// Antes da primeira conferência (10 s): quem aprovou foi o aviso
		var aprovado = respostas(pedido, PagamentoAprovado.TIPO, 1, Duration.ofSeconds(5));
		assertThat(aprovado).hasSize(1);
		assertThat(json.treeToValue(aprovado.getFirst().dados(), PagamentoAprovado.class).valorCentavos())
			.isEqualTo(1990);
		assertThat(situacao(pedido)).isEqualTo("aprovada");
	}

	@Test
	void avisoRepetidoAprovaUmaVezSo() throws Exception {
		var pedido = UUID.randomUUID();
		cobrarNoFiel(pedido, 1000);
		resposta(pedido, CobrancaCriada.TIPO);

		assertThat(FIEL.pagar(pedido, true)).isEqualTo(204);
		assertThat(FIEL.reenviarUltimo()).isEqualTo(204);
		assertThat(FIEL.reenviarUltimo()).isEqualTo(204);

		assertThat(tiposDoPedido(pedido, Duration.ofSeconds(3))).containsExactly(CobrancaCriada.TIPO,
				PagamentoAprovado.TIPO);
	}

	@Test
	void avisoComAssinaturaErradaOuVelhaEhRecusado() throws Exception {
		var pedido = UUID.randomUUID();
		cobrarNoFiel(pedido, 1000);
		resposta(pedido, CobrancaCriada.TIPO);
		var c = FIEL.daReferencia(pedido);
		var corpo = "{\"id\":\"x\",\"tipo\":\"cobranca.paga\",\"dados\":{\"cobranca\":{\"id\":" + c.id
				+ ",\"referencia\":\"" + pedido + "\"}}}";
		var agora = Instant.now().getEpochSecond();

		assertThat(FIEL.avisar(corpo, UUID.randomUUID().toString(), "outro-segredo", agora)).isEqualTo(401);
		assertThat(FIEL.avisar(corpo, UUID.randomUUID().toString(), FIEL.segredo, agora - 600)).isEqualTo(401);
		// Corpo trocado depois de assinado
		var assinatura = "t=" + agora + ",v1=" + FielDeMentira.hmac(FIEL.segredo, agora + "." + corpo);
		var mexido = HttpRequest.newBuilder(URI.create(FIEL.webhook))
			.header("Fiel-Evento", "cobranca.paga")
			.header("Fiel-Evento-Id", UUID.randomUUID().toString())
			.header("Fiel-Assinatura", assinatura)
			.POST(HttpRequest.BodyPublishers.ofString(corpo.replace("\"x\"", "\"y\"")))
			.build();
		assertThat(HttpClient.newHttpClient().send(mexido, HttpResponse.BodyHandlers.discarding()).statusCode())
			.isEqualTo(401);

		assertThat(situacao(pedido)).isEqualTo("pendente");
	}

	@Test
	void avisoPerdidoAConferenciaAchaOPagamento() throws Exception {
		var pedido = UUID.randomUUID();
		cobrarNoFiel(pedido, 1000);
		resposta(pedido, CobrancaCriada.TIPO);

		FIEL.pagar(pedido, false);

		assertThat(respostas(pedido, PagamentoAprovado.TIPO, 1, Duration.ofSeconds(30))).hasSize(1);
	}

	@Test
	void cancelarPendenteCancelaNoFiel() throws Exception {
		var pedido = UUID.randomUUID();
		cobrarNoFiel(pedido, 1000);
		resposta(pedido, CobrancaCriada.TIPO);

		cancelar(pedido);

		resposta(pedido, CobrancaCancelada.TIPO);
		assertThat(FIEL.daReferencia(pedido).estado).isEqualTo("cancelada");
		assertThat(situacao(pedido)).isEqualTo("cancelada");
	}

	@Test
	void pagamentoQueChegaDepoisDoCancelamentoEhEstornado() throws Exception {
		var pedido = UUID.randomUUID();
		cobrarNoFiel(pedido, 1500);
		resposta(pedido, CobrancaCriada.TIPO);
		// O visitante paga no último segundo; o aviso ainda não chegou quando a saga desiste
		FIEL.pagar(pedido, false);

		cancelar(pedido);

		var estorno = json.treeToValue(resposta(pedido, PagamentoEstornado.TIPO).dados(), PagamentoEstornado.class);
		assertThat(estorno.valorCentavos()).isEqualTo(1500);
		var noFiel = FIEL.daReferencia(pedido);
		assertThat(noFiel.estado).isEqualTo("estornada");
		assertThat(noFiel.estornos).containsExactly(pedido.toString());
		// E nunca um PagamentoAprovado: a saga já tinha desistido
		assertThat(tiposDoPedido(pedido, Duration.ofSeconds(2))).doesNotContain(PagamentoAprovado.TIPO);
	}

	@Test
	void avisoDoPagamentoDepoisDoCancelamentoTambemEstorna() throws Exception {
		var pedido = UUID.randomUUID();
		cobrarNoFiel(pedido, 1500);
		resposta(pedido, CobrancaCriada.TIPO);
		FIEL.foraDoAr = true;
		cancelar(pedido);
		// O cancelamento está esperando o Fiel voltar; o pagamento e o aviso chegam antes
		var fim = System.nanoTime() + PRAZO.toNanos();
		while (!situacao(pedido).equals("cancelando")) {
			assertThat(System.nanoTime()).as("chegar a cancelando").isLessThan(fim);
			Thread.sleep(50);
		}
		// O aviso vai do Fiel para o Foil, então chega mesmo com a API do Fiel ainda fora para o Foil
		assertThat(FIEL.pagar(pedido, true)).isEqualTo(204);
		assertThat(situacao(pedido)).isEqualTo("estornando");
		FIEL.foraDoAr = false;
		FIEL.semSegredo = false;

		resposta(pedido, PagamentoEstornado.TIPO);
		assertThat(FIEL.daReferencia(pedido).estado).isEqualTo("estornada");
		assertThat(tiposDoPedido(pedido, Duration.ofSeconds(2))).doesNotContain(PagamentoAprovado.TIPO,
				CobrancaCancelada.TIPO);
	}

	@Test
	void fielQuePerdeARespostaNaoGanhaCobrancaDuplicada() throws Exception {
		var pedido = UUID.randomUUID();
		FIEL.perderRespostas.set(2);

		cobrarNoFiel(pedido, 1000);

		resposta(pedido, CobrancaCriada.TIPO);
		assertThat(FIEL.pedidosDeCriacao.get(pedido.toString())).hasValue(3);
		assertThat(FIEL.cobrancas.values().stream().filter(c -> c.referencia.equals(pedido.toString())))
			.hasSize(1);
	}

	@Test
	void fielForaDoArSoAtrasaACompraManualNaoOsRobos() throws Exception {
		FIEL.foraDoAr = true;
		var manual = UUID.randomUUID();
		var robo = UUID.randomUUID();
		cobrarNoFiel(manual, 1000);
		enviar(robo, CobrarPedido.TIPO, CobrarPedido.VERSAO, new CobrarPedido(1000, CobrarPedido.Forma.SIMULADA));

		// O robô responde na hora, mesmo com o comando manual antes dele (talvez na mesma partição)
		assertThat(eventos.ler(doPedido(robo), 1, PRAZO)).hasSize(1);
		assertThat(tiposDoPedido(manual, Duration.ofSeconds(1))).isEmpty();

		FIEL.foraDoAr = false;
		FIEL.semSegredo = false;
		resposta(manual, CobrancaCriada.TIPO);
	}

	@Test
	void fielRecusaACriacaoERecusaOPagamento() throws Exception {
		FIEL.valorMaximo = 500_000;
		var pedido = UUID.randomUUID();

		cobrarNoFiel(pedido, 500_001);

		var recusa = json.treeToValue(resposta(pedido, PagamentoRecusado.TIPO).dados(), PagamentoRecusado.class);
		assertThat(recusa.motivo()).isEqualTo(PagamentoRecusado.Motivo.RECUSADO);
		assertThat(situacao(pedido)).isEqualTo("recusada");
	}

	@Test
	void recadastroTrocaOSegredoEOAntigoDeixaDeValer() throws Exception {
		var pedido = UUID.randomUUID();
		cobrarNoFiel(pedido, 1000);
		resposta(pedido, CobrancaCriada.TIPO);
		var antigo = FIEL.segredo;
		assertThat(jdbc.sql("select segredo from webhook_do_fiel").query(String.class).single()).isEqualTo(antigo);

		assertThat(avisos.cadastrar()).isTrue();

		assertThat(FIEL.segredo).isNotEqualTo(antigo);
		var corpo = "{\"id\":\"x\",\"tipo\":\"cobranca.paga\",\"dados\":{}}";
		assertThat(FIEL.avisar(corpo, UUID.randomUUID().toString(), antigo, Instant.now().getEpochSecond()))
			.isEqualTo(401);
		assertThat(FIEL.pagar(pedido, true)).isEqualTo(204);
		resposta(pedido, PagamentoAprovado.TIPO);
	}

	@Test
	void idReaproveitadoDepoisDaRecriacaoDoFielNaoMexeNoPedidoDeOutro() throws Exception {
		var antigo = UUID.randomUUID();
		cobrarNoFiel(antigo, 1000);
		resposta(antigo, CobrancaCriada.TIPO);
		var idDoAntigo = FIEL.daReferencia(antigo).id;

		// 4h: o Fiel é recriado, e outro pedido ganha o mesmo id e paga
		FIEL.recriar(idDoAntigo);
		var novo = UUID.randomUUID();
		cobrarNoFiel(novo, 1000);
		resposta(novo, CobrancaCriada.TIPO);
		assertThat(FIEL.daReferencia(novo).id).isEqualTo(idDoAntigo);
		FIEL.pagar(novo, false);

		// A saga do antigo desiste: o cancelamento não pode estornar o pagamento do novo
		cancelar(antigo);

		resposta(antigo, CobrancaCancelada.TIPO);
		assertThat(FIEL.daReferencia(novo).estado).isEqualTo("paga");
		assertThat(FIEL.daReferencia(novo).estornos).isEmpty();
		assertThat(tiposDoPedido(antigo, Duration.ofSeconds(1))).doesNotContain(PagamentoAprovado.TIPO,
				PagamentoEstornado.TIPO);
	}

	@Test
	void cadastroSemSegredoNaoTrocaOSegredoGuardado() throws Exception {
		assertThat(avisos.cadastrar()).isTrue();
		var guardado = jdbc.sql("select segredo from webhook_do_fiel").query(String.class).single();
		FIEL.semSegredo = true;

		assertThat(avisos.cadastrar()).isFalse();

		// "" guardado deixaria o recebimento de avisos quebrado sem ninguém ver (o HMAC do Java recusa chave vazia)
		assertThat(jdbc.sql("select segredo from webhook_do_fiel").query(String.class).single()).isEqualTo(guardado);
		var pedido = UUID.randomUUID();
		cobrarNoFiel(pedido, 1000);
		resposta(pedido, CobrancaCriada.TIPO);
		assertThat(FIEL.pagar(pedido, true)).isEqualTo(204);
		resposta(pedido, PagamentoAprovado.TIPO);
	}

	@Test
	void fielForaDoArNoCadastroNaoDerrubaNada() {
		FIEL.foraDoAr = true;

		assertThat(avisos.cadastrar()).isFalse();
	}

	@Test
	void avisoGrandeDemaisEhRecusadoSemLer() throws Exception {
		var enorme = HttpRequest.newBuilder(URI.create(FIEL.webhook))
			.header("Fiel-Evento", "cobranca.paga")
			.header("Fiel-Evento-Id", UUID.randomUUID().toString())
			.header("Fiel-Assinatura", "t=1,v1=x")
			.POST(HttpRequest.BodyPublishers.ofByteArray(new byte[AvisosDoFielController.MAIOR_CORPO + 1]))
			.build();

		assertThat(HttpClient.newHttpClient().send(enorme, HttpResponse.BodyHandlers.discarding()).statusCode())
			.isEqualTo(413);
	}

	@Test
	void cobrarPedidoV1AindaEhAceitoComoSimulada() throws Exception {
		var pedido = UUID.randomUUID();
		var v1 = json.readTree("{\"valorCentavos\":1000}");

		enviar(pedido, CobrarPedido.TIPO, CobrarPedido.VERSAO_ANTERIOR, v1);

		var tipos = tiposDoPedido(pedido, Duration.ofSeconds(5));
		assertThat(tipos).hasSize(1);
		assertThat(tipos.getFirst()).isIn(PagamentoAprovado.TIPO, PagamentoRecusado.TIPO);
		assertThat(jdbc.sql("select forma from cobranca where pedido_id = :p").param("p", pedido)
			.query(String.class)
			.single()).isEqualTo("simulada");
	}

}
