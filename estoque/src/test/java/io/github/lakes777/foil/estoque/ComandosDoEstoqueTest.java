package io.github.lakes777.foil.estoque;

import static io.github.lakes777.foil.comum.LeitorDeTopico.doPedido;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.redpanda.RedpandaContainer;

import io.github.lakes777.foil.comum.LeitorDeTopico;
import io.github.lakes777.foil.contratos.Envelope;
import io.github.lakes777.foil.contratos.EstoqueComandos.DevolverUnidade;
import io.github.lakes777.foil.contratos.EstoqueComandos.ReservarUnidade;
import io.github.lakes777.foil.contratos.EstoqueEventos.ReservaRecusada;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeDevolvida;
import io.github.lakes777.foil.contratos.EstoqueEventos.UnidadeReservada;
import io.github.lakes777.foil.contratos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/** De ponta a ponta pelo Redpanda: comando no tópico, consumidor, estoque, saída, evento no outro tópico. */
@TesteDoEstoque
class ComandosDoEstoqueTest {

	private static final Duration PRAZO = Duration.ofSeconds(20);

	@Autowired
	EstoqueService estoque;

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	RedpandaContainer redpanda;

	private LeitorDeTopico eventos;

	@BeforeEach
	void abrir() {
		eventos = new LeitorDeTopico(redpanda.getBootstrapServers(), Topicos.ESTOQUE_EVENTOS);
	}

	@AfterEach
	void fechar() {
		eventos.close();
	}

	private Edicao edicaoNova(int total) {
		var carta = estoque.criarCarta("Carta " + UUID.randomUUID(), "comum", "");
		return estoque.novaEdicao(carta.id(), total);
	}

	private Envelope reservar(UUID pedido, long edicao) {
		return Envelope.novo(ReservarUnidade.TIPO, ReservarUnidade.VERSAO, pedido,
				json.valueToTree(new ReservarUnidade(edicao)), Clock.systemUTC());
	}

	private Envelope devolver(UUID pedido, long edicao) {
		return Envelope.novo(DevolverUnidade.TIPO, DevolverUnidade.VERSAO, pedido,
				json.valueToTree(new DevolverUnidade(edicao)), Clock.systemUTC());
	}

	private void enviar(Envelope comando) throws Exception {
		kafka.send(Topicos.ESTOQUE_COMANDOS, comando.pedidoId().toString(), json.writeValueAsString(comando)).get();
	}

	private List<Envelope> respostas(UUID pedido, int quantas) {
		return eventos.ler(doPedido(pedido), quantas, PRAZO)
			.stream()
			.map(r -> json.readValue(r.value(), Envelope.class))
			.toList();
	}

	private int disponivel(Edicao edicao) {
		return estoque.buscarEdicao(edicao.id()).orElseThrow().disponivel();
	}

	@Test
	void reservarPorComandoRespondeUnidadeReservada() throws Exception {
		var edicao = edicaoNova(3);
		var pedido = UUID.randomUUID();

		enviar(reservar(pedido, edicao.id()));

		var resposta = respostas(pedido, 1);
		assertThat(resposta).extracting(Envelope::tipo).containsExactly(UnidadeReservada.TIPO);
		assertThat(json.treeToValue(resposta.getFirst().dados(), UnidadeReservada.class))
			.isEqualTo(new UnidadeReservada(edicao.id(), EstoqueService.PRECO_PADRAO));
		assertThat(disponivel(edicao)).isEqualTo(2);
	}

	@Test
	void oMesmoComandoEntregueDuasVezesTemEfeitoUmaVez() throws Exception {
		var edicao = edicaoNova(3);
		var pedido = UUID.randomUUID();
		var comando = reservar(pedido, edicao.id());

		enviar(comando);
		enviar(comando);
		// Mesma chave = mesma partição = processado depois dos dois: quando a resposta dele chegar, o repetido já passou
		enviar(devolver(pedido, edicao.id()));

		var lidas = respostas(pedido, 2);
		assertThat(lidas).extracting(Envelope::tipo).containsExactly(UnidadeReservada.TIPO, UnidadeDevolvida.TIPO);
		// Nada a mais chegando atrás
		assertThat(eventos.lerDurante(doPedido(pedido), Duration.ofSeconds(2))).hasSize(2);
		assertThat(json.treeToValue(lidas.get(1).dados(), UnidadeDevolvida.class)).isEqualTo(new UnidadeDevolvida(true));
		assertThat(disponivel(edicao)).isEqualTo(3);
		assertThat(jdbc.sql("select count(*) from evento_processado where evento_id = :id")
			.param("id", comando.id())
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	@Test
	void semEstoqueRespondeReservaRecusada() throws Exception {
		var edicao = edicaoNova(1);
		estoque.reservar(UUID.randomUUID(), edicao.id());
		var pedido = UUID.randomUUID();

		enviar(reservar(pedido, edicao.id()));

		var resposta = respostas(pedido, 1);
		assertThat(resposta).extracting(Envelope::tipo).containsExactly(ReservaRecusada.TIPO);
		assertThat(json.treeToValue(resposta.getFirst().dados(), ReservaRecusada.class))
			.isEqualTo(new ReservaRecusada(edicao.id(), ReservaRecusada.Motivo.SEM_ESTOQUE));
	}

	@Test
	void comandoDesconhecidoEDescartadoSemTravarOSeguinte() throws Exception {
		var edicao = edicaoNova(2);
		var pedido = UUID.randomUUID();
		var desconhecido = Envelope.novo("Desconhecido", 1, pedido, json.readTree("{}"), Clock.systemUTC());
		kafka.send(Topicos.ESTOQUE_COMANDOS, pedido.toString(), "isto não é JSON").get();

		enviar(desconhecido);
		enviar(reservar(pedido, edicao.id()));

		assertThat(respostas(pedido, 1)).extracting(Envelope::tipo).containsExactly(UnidadeReservada.TIPO);
		assertThat(disponivel(edicao)).isEqualTo(1);
	}

	@Test
	void pedidoRepetidoComOutraEdicaoRespondeAEdicaoDaReserva() throws Exception {
		var edicao = edicaoNova(2);
		var outra = edicaoNova(2);
		var pedido = UUID.randomUUID();

		enviar(reservar(pedido, edicao.id()));
		enviar(reservar(pedido, outra.id()));

		var lidas = respostas(pedido, 2);
		assertThat(lidas).extracting(e -> json.treeToValue(e.dados(), UnidadeReservada.class).edicaoId())
			.containsExactly(edicao.id(), edicao.id());
		assertThat(disponivel(outra)).isEqualTo(2);
	}

	@Test
	void pedidoJaDevolvidoNaoVoltaAReservado() throws Exception {
		var edicao = edicaoNova(2);
		var pedido = UUID.randomUUID();

		enviar(reservar(pedido, edicao.id()));
		enviar(devolver(pedido, edicao.id()));
		// Comando novo (outro id) para o mesmo pedido, depois da devolução
		enviar(reservar(pedido, edicao.id()));

		var lidas = respostas(pedido, 3);
		assertThat(lidas).extracting(Envelope::tipo)
			.containsExactly(UnidadeReservada.TIPO, UnidadeDevolvida.TIPO, ReservaRecusada.TIPO);
		assertThat(json.treeToValue(lidas.get(2).dados(), ReservaRecusada.class).motivo())
			.isEqualTo(ReservaRecusada.Motivo.JA_DEVOLVIDA);
		assertThat(disponivel(edicao)).isEqualTo(2);
	}

}
