package io.github.lakes777.foil.comum;

/**
 * As threads virtuais rodam sobre "carregadores" (threads de verdade), tantos quantos processadores a JVM vê. Na VM
 * (um núcleo) seria um só, e no Java 21 uma thread virtual que espera dentro de um synchronized (no nosso código ou
 * numa biblioteca: driver, Flyway, cliente do Kafka) prende o carregador: com um só, o serviço inteiro para. Foi o
 * que travou o /saude na primeira publicação (20/10/2026). Com 4, um preso não para os outros; o sistema operacional
 * divide o núcleo entre eles. Chamar no main, antes de qualquer thread virtual existir.
 */
public final class Carregadores {

	static final String PROPRIEDADE = "jdk.virtualThreadScheduler.parallelism";

	static final int MINIMO = 4;

	private Carregadores() {
	}

	/** Pelo menos {@link #MINIMO} carregadores; quem já definiu a propriedade (na linha de comando) manda. */
	public static void garantir() {
		if (System.getProperty(PROPRIEDADE) == null) {
			System.setProperty(PROPRIEDADE,
					String.valueOf(Math.max(MINIMO, Runtime.getRuntime().availableProcessors())));
		}
	}

}
