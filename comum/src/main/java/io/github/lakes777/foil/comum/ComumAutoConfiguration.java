package io.github.lakes777.foil.comum;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.lakes777.foil.comum.eventos.AgendaDaSaida;
import io.github.lakes777.foil.comum.eventos.ErrosDoConsumidor;
import io.github.lakes777.foil.comum.eventos.EventosProcessados;
import io.github.lakes777.foil.comum.eventos.Limpeza;
import io.github.lakes777.foil.comum.eventos.Mortos;
import io.github.lakes777.foil.comum.eventos.PublicadorDaSaida;
import io.github.lakes777.foil.comum.eventos.Rastro;
import io.github.lakes777.foil.comum.eventos.Saida;
import io.github.lakes777.foil.contratos.Topicos;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import tools.jackson.databind.json.JsonMapper;

/** O que os três serviços têm igual. Entra sozinho por estar no classpath. */
@AutoConfiguration
@Import({ SaudeController.class, ComumAutoConfiguration.Agendamento.class, MortosController.class })
public class ComumAutoConfiguration {

	/** Sem Tracer no contexto (tracing desligado), a outbox segue igual, só sem guardar trace. */
	@Bean
	Rastro rastro(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
		var t = tracer.getIfAvailable();
		var p = propagator.getIfAvailable();
		return t == null || p == null ? Rastro.desligado() : new Rastro(t, p);
	}

	@Bean
	Saida saida(JdbcClient jdbc, JsonMapper json, Rastro rastro) {
		return new Saida(jdbc, json, rastro);
	}

	@Bean
	PublicadorDaSaida publicadorDaSaida(JdbcClient jdbc, KafkaTemplate<String, String> kafka, Rastro rastro) {
		return new PublicadorDaSaida(jdbc, kafka, rastro);
	}

	/**
	 * Fora dos traces (e das métricas HTTP): toda leitura (GET), como o /saude do Docker e do Vigil, o Prometheus lendo
	 * o actuator, o painel ao vivo e a tela da compra perguntando pelo pedido a cada 1,5 s; e os agendamentos (o
	 * publicador roda a cada 100 ms). Com 100% amostrado e o Jaeger guardando só os últimos 2.000, eles empurravam o
	 * trace de quem comprou para fora em minutos. Toda saga começa num POST.
	 */
	@Bean
	ObservationPredicate semRuido() {
		return (nome, contexto) -> {
			if (nome.startsWith("tasks.scheduled")) {
				return false;
			}
			if (contexto instanceof ServerRequestObservationContext pedido) {
				return !ruidoso(pedido.getCarrier().getMethod(), pedido.getCarrier().getRequestURI());
			}
			return true;
		};
	}

	static boolean ruidoso(String metodo, String caminho) {
		return "GET".equals(metodo) || "HEAD".equals(metodo) || caminho.startsWith("/actuator");
	}

	@Bean
	EventosProcessados eventosProcessados(JdbcClient jdbc) {
		return new EventosProcessados(jdbc);
	}

	/**
	 * Os tópicos e as filas de mortos, com {@link Topicos#PARTICOES} partições (a fila de mortos usa a mesma
	 * partição da origem). Cada um guarda as mensagens por no máximo {@link Limpeza#DIAS_NO_TOPICO} dias, menos do que a
	 * limpeza guarda os ids dos eventos processados. Num tópico que já existe, só a configuração é acertada
	 * ({@code spring.kafka.admin.modify-topic-configs=true} nos serviços).
	 */
	@Bean
	KafkaAdmin.NewTopics topicos() {
		var retencao = String.valueOf(Duration.ofDays(Limpeza.DIAS_DE_RETENCAO).toMillis());
		var segmento = String.valueOf(Duration.ofDays(Limpeza.DIAS_DO_SEGMENTO).toMillis());
		return new KafkaAdmin.NewTopics(Topicos.TODOS.stream()
			.flatMap(nome -> Stream.of(nome, nome + Topicos.MORTOS))
			.map(nome -> TopicBuilder.name(nome)
				.partitions(Topicos.PARTICOES)
				.replicas(1)
				.config(TopicConfig.RETENTION_MS_CONFIG, retencao)
				.config(TopicConfig.SEGMENT_MS_CONFIG, segmento)
				.build())
			.toArray(NewTopic[]::new));
	}

	/** A limpeza das 4h10, com as tabelas que cada serviço acrescenta ({@link Limpeza.Regras}). */
	@Bean
	Limpeza limpeza(JdbcClient jdbc, ObjectProvider<Limpeza.Regras> regras,
			@Value("${foil.limpeza.dias-dos-eventos:4}") int diasDosEventos,
			@Value("${foil.limpeza.dias-das-compras:30}") int diasDasCompras) {
		return new Limpeza(jdbc, regras.orderedStream().toList(), diasDosEventos, diasDasCompras);
	}

	/** Boot liga este tratador ao container dos listeners. Esperas configuráveis para os testes. */
	@Bean
	DefaultErrorHandler errosDoConsumidor(KafkaTemplate<String, String> kafka,
			@Value("${foil.consumo.espera-inicial-ms:500}") long esperaInicial,
			@Value("${foil.consumo.espera-maxima-ms:30000}") long esperaMaxima) {
		return ErrosDoConsumidor.criar(kafka, esperaInicial, esperaMaxima);
	}

	/** Só nos serviços que dizem quais filas de mortos ler ({@code foil.mortos.topicos}). */
	@Bean
	@ConditionalOnProperty("foil.mortos.topicos")
	Mortos mortos(JdbcClient jdbc, KafkaTemplate<String, String> kafka,
			@Value("${foil.limpeza.dias-das-compras:30}") int diasDasCompras) {
		return new Mortos(jdbc, kafka, Limpeza.diasParaReprocessar(diasDasCompras));
	}

	/**
	 * Produtor com prazos curtos: sem isso, com o Redpanda fora, um send() espera até 60 s pelos metadados
	 * (max.block.ms) com a transação da saída aberta. Pior caso por evento: ~11 s (ver PublicadorDaSaida). acks=all + idempotência: o Redpanda não grava duplicado
	 * por causa de uma nova tentativa do próprio produtor.
	 */
	@Bean
	DefaultKafkaProducerFactoryCustomizer prazosDoProdutor() {
		return fabrica -> fabrica.updateConfigs(Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000,
				ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4000, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000,
				ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true));
	}

	/**
	 * Consumidor que morre sem avisar (kill -9, queda da VM) só perde as partições quando a sessão dele vence; até
	 * lá, o serviço que voltou fica parado esperando. O padrão (45 s) parava as cobranças por mais de meio minuto
	 * a cada queda; 10 s (o Redpanda aceita a partir de 6 s), com batida a cada 3 s.
	 */
	@Bean
	DefaultKafkaConsumerFactoryCustomizer sessaoDoConsumidor() {
		return fabrica -> fabrica.updateConfigs(Map.of(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10_000,
				ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000));
	}

	@Bean
	@ConditionalOnProperty(name = "foil.saida.automatica", havingValue = "true", matchIfMissing = true)
	AgendaDaSaida agendaDaSaida(PublicadorDaSaida publicador) {
		return new AgendaDaSaida(publicador);
	}

	@EnableScheduling
	@ConditionalOnProperty(name = "foil.saida.automatica", havingValue = "true", matchIfMissing = true)
	static class Agendamento {

	}

}
