package io.github.lakes777.foil.pagamentos;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.lakes777.foil.comum.eventos.Rastro;
import io.github.lakes777.foil.pagamentos.ClienteDoFiel.FalhaPassageira;
import io.github.lakes777.foil.pagamentos.ClienteDoFiel.Resposta;
import io.github.lakes777.foil.pagamentos.CobrancasNoFiel.Trabalho;

/**
 * Faz no Fiel o que as cobranças da forma FIEL estão esperando (V5): criar, conferir se foi paga, cancelar e
 * estornar. Fica fora do consumidor do Redpanda de propósito: com o Fiel fora do ar, só estas linhas esperam
 * (com espera crescente), e os comandos dos robôs seguem.
 *
 * <p>
 * O pedido HTTP acontece sem transação aberta (a linha fica reservada por {@link CobrancasNoFiel#RESERVA}); a
 * resposta é aplicada depois, numa transação que confere a situação de novo.
 */
@Component
class TrabalhoNoFiel {

	static final int LOTE = 10;

	private static final Logger log = LoggerFactory.getLogger(TrabalhoNoFiel.class);

	private final CobrancasNoFiel cobrancas;

	private final ClienteDoFiel fiel;

	private final Duration conferirACada;

	private final Rastro rastro;

	TrabalhoNoFiel(CobrancasNoFiel cobrancas, ClienteDoFiel fiel,
			@Value("${foil.fiel.conferir-a-cada:15s}") Duration conferirACada, Rastro rastro) {
		this.cobrancas = cobrancas;
		this.fiel = fiel;
		this.conferirACada = conferirACada;
		this.rastro = rastro;
	}

	/** Um lote; devolve quantos pegou (lote cheio = provavelmente tem mais). */
	int rodar() {
		if (!fiel.ligado()) {
			return 0;
		}
		var lote = cobrancas.reservar(LOTE);
		for (var t : lote) {
			// A conversa com o Fiel aparece no trace da saga; a conferência a cada 15 s, não (seria um span por rodada)
			var conferencia = "pendente".equals(t.situacao()) && t.fielId() != null;
			try (var span = rastro.reabrir(conferencia ? null : t.traceparent(), nomeNoTrace(t))) {
				fazer(t);
			}
			catch (FalhaPassageira erro) {
				log.warn("pagamentos: Fiel, pedido {} ({}), tentativa {}: {}", t.pedidoId(), t.situacao(),
						t.tentativas() + 1, erro.getMessage());
				cobrancas.adiar(t);
			}
			catch (RuntimeException erro) {
				// Qualquer outra coisa (banco, resposta estranha): não interrompe o resto do lote, e espera crescendo
				log.error("pagamentos: Fiel, pedido {} ({}): erro inesperado", t.pedidoId(), t.situacao(), erro);
				cobrancas.adiar(t);
			}
		}
		return lote.size();
	}

	private static String nomeNoTrace(Trabalho t) {
		return switch (t.situacao()) {
			case "pendente" -> "Fiel: criar cobrança";
			case "cancelando" -> "Fiel: cancelar cobrança";
			default -> "Fiel: estornar";
		};
	}

	private void fazer(Trabalho t) {
		switch (t.situacao()) {
			case "pendente" -> {
				if (t.fielId() == null) {
					criar(t);
				}
				else {
					conferir(t);
				}
			}
			case "cancelando" -> cancelar(t);
			case "estornando" -> estornar(t);
			default -> throw new IllegalStateException("situação sem trabalho: " + t.situacao());
		}
	}

	private void criar(Trabalho t) {
		var r = fiel.criar(t.pedidoId(), t.valorCentavos());
		if (r.deu()) {
			var qr = r.corpo().path("qr").asString();
			cobrancas.criada(t, r.corpo().path("id").asLong(), qr, fiel.linkParaPagar(qr), conferirACada);
		}
		else {
			// 4xx: o pedido não vale (valor acima do limite, por exemplo); repetir daria a mesma resposta
			cobrancas.recusadaPeloFiel(t, "HTTP " + r.status() + " " + r.corpo().path("detail").asString(""));
		}
	}

	/**
	 * A cobrança deste pedido no Fiel, ou vazio se ela sumiu. O id do Fiel recomeça do 1 quando a demonstração é
	 * recriada (4h): o mesmo id pode ser hoje a cobrança de outro pedido. Sem conferir a referência, a conferência
	 * aprovaria este pedido com o pagamento do outro, e o cancelamento cancelaria (ou estornaria) a cobrança do outro.
	 */
	private Optional<Resposta> daCobrancaDoPedido(Trabalho t) {
		var r = fiel.ler(t.fielId());
		if (r.status() == 404) {
			return Optional.empty();
		}
		if (!r.deu()) {
			throw new FalhaPassageira("ler: HTTP " + r.status());
		}
		if (!t.pedidoId().toString().equals(r.corpo().path("referencia").asString(""))) {
			log.warn("pagamentos: pedido {}: a cobrança {} do Fiel agora é de outro pedido (Fiel recriado)",
					t.pedidoId(), t.fielId());
			return Optional.empty();
		}
		return Optional.of(r);
	}

	/** A rede de segurança do aviso: se ele se perdeu, a conferência acha o pagamento. */
	private void conferir(Trabalho t) {
		var r = daCobrancaDoPedido(t);
		if (r.isPresent() && "paga".equals(r.get().estado())) {
			cobrancas.paga(t.pedidoId(), t.fielId());
		}
		else {
			// Pendente, expirada ou sumida (Fiel recriado às 4h): quem decide é o prazo da saga
			cobrancas.conferirDeNovoEm(t, conferirACada);
		}
	}

	private void cancelar(Trabalho t) {
		if (daCobrancaDoPedido(t).isEmpty()) {
			// Sumiu (Fiel recriado): não há o que cancelar, e o id pode ser de outro pedido
			cobrancas.cancelada(t);
			return;
		}
		var r = fiel.cancelar(t.fielId());
		if (r.deu() || r.status() == 404) {
			// 404: a demonstração do Fiel foi recriada e a cobrança sumiu junto; não há o que cancelar
			cobrancas.cancelada(t);
			return;
		}
		if (r.status() != 409) {
			throw new FalhaPassageira("cancelar: HTTP " + r.status());
		}
		// 409: não estava pendente. Paga (o visitante pagou no último segundo) vira estorno; o resto já acabou
		var agora = daCobrancaDoPedido(t).map(Resposta::estado).orElse("sumida");
		switch (agora) {
			case "paga", "parcialmente_estornada", "estornada" -> cobrancas.pagaAntesDoCancelamento(t);
			default -> cobrancas.cancelada(t);
		}
	}

	private void estornar(Trabalho t) {
		var cobranca = daCobrancaDoPedido(t);
		if (cobranca.isEmpty()) {
			log.warn("pagamentos: pedido {}: a cobrança {} não existe mais no Fiel (recriado?); nada a estornar",
					t.pedidoId(), t.fielId());
			cobrancas.estornada(t);
			return;
		}
		if ("estornada".equals(cobranca.get().estado())) {
			cobrancas.estornada(t);
			return;
		}
		var r = fiel.estornar(t.fielId(), t.valorCentavos(), t.pedidoId());
		if (r.deu() || r.status() == 404) {
			if (r.status() == 404) {
				log.warn("pagamentos: pedido {}: cobrança {} não existe mais no Fiel (recriado?); nada a estornar",
						t.pedidoId(), t.fielId());
			}
			cobrancas.estornada(t);
			return;
		}
		throw new FalhaPassageira("estornar: HTTP " + r.status() + " " + r.corpo().path("detail").asString(""));
	}

}
