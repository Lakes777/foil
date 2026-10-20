package io.github.lakes777.foil.pontaaponta;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Um serviço rodando como processo Java separado: um do Foil, a partir do -exec.jar que o Maven montou, ou um jar de
 * fora (o Fiel), com a porta na variável de ambiente dele.
 */
final class Servico {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

	private final String nome;

	private final Map<String, String> ambiente;

	private final List<String> argumentos;

	private final int porta;

	/** null = o -exec.jar do Foil com este nome. */
	private final Path jarDeFora;

	private final String variavelDaPorta;

	private Process processo;

	Servico(String nome, Map<String, String> ambiente, List<String> argumentos) throws IOException {
		this(nome, null, "PORTA", ambiente, argumentos);
	}

	Servico(String nome, Path jarDeFora, String variavelDaPorta, Map<String, String> ambiente, List<String> argumentos)
			throws IOException {
		this.nome = nome;
		this.jarDeFora = jarDeFora;
		this.variavelDaPorta = variavelDaPorta;
		this.ambiente = ambiente;
		this.argumentos = argumentos;
		try (var livre = new ServerSocket(0)) {
			this.porta = livre.getLocalPort();
		}
	}

	String url(String caminho) {
		return "http://localhost:" + porta + caminho;
	}

	void subir() throws Exception {
		var jar = jarDeFora;
		if (jar == null) {
			jar = Path.of(System.getProperty("foil.jars"), nome, "target",
					nome + "-" + System.getProperty("foil.versao") + "-exec.jar");
			conferirQueOJarEstaEmDia(jar);
		}
		else if (!Files.exists(jar)) {
			throw new IllegalStateException(jar + " não existe");
		}
		var java = ProcessHandle.current().info().command().orElse("java");
		// Um processador, como a VM (20/10/2026): com mais núcleos, uma thread virtual presa num synchronized não trava
		// as outras, e o deadlock do /saude da primeira publicação passou por todos os testes
		var comando = new ArrayList<>(List.of(java, "-Xmx256m", "-XX:ActiveProcessorCount=1", "-jar", jar.toString()));
		comando.addAll(argumentos);
		var logs = Path.of(System.getProperty("foil.logs"));
		Files.createDirectories(logs);
		var construtor = new ProcessBuilder(comando).redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logs.toFile(), nome + ".log")));
		construtor.environment().putAll(ambiente);
		construtor.environment().put(variavelDaPorta, String.valueOf(porta));
		processo = construtor.start();
		// Se o Maven ou o teste morrerem no meio, a JVM do serviço não fica esquecida rodando
		var esta = processo;
		Runtime.getRuntime().addShutdownHook(new Thread(esta::destroyForcibly));
		esperarSaude();
	}

	/**
	 * O jar só é refeito no "package": rodar só a fase "test" (ou pela IDE) subiria código velho e passaria verde.
	 * Mais velho que alguma classe compilada = recusa.
	 */
	private void conferirQueOJarEstaEmDia(Path jar) throws IOException {
		if (!Files.exists(jar)) {
			throw new IllegalStateException(jar + " não existe: rode ./mvnw package (ou verify)");
		}
		var classes = jar.getParent().resolve("classes");
		try (var arquivos = Files.walk(classes)) {
			var maisNova = arquivos.filter(Files::isRegularFile).mapToLong(f -> f.toFile().lastModified()).max();
			if (maisNova.isPresent() && maisNova.getAsLong() > jar.toFile().lastModified()) {
				throw new IllegalStateException(jar + " é mais velho que o código compilado: rode ./mvnw package");
			}
		}
	}

	/** Queda de verdade: kill -9, sem desligamento elegante (nada de terminar o que estava fazendo). */
	void derrubar() throws InterruptedException {
		processo.destroyForcibly();
		processo.waitFor();
	}

	void desligar() throws InterruptedException {
		if (processo != null && processo.isAlive()) {
			processo.destroy();
			if (!processo.waitFor(20, TimeUnit.SECONDS)) {
				processo.destroyForcibly();
				processo.waitFor();
			}
		}
	}

	private void esperarSaude() throws Exception {
		var fim = System.nanoTime() + Duration.ofSeconds(90).toNanos();
		while (System.nanoTime() < fim) {
			if (!processo.isAlive()) {
				throw new IllegalStateException(nome + " morreu ao subir; veja " + System.getProperty("foil.logs"));
			}
			try {
				var resposta = HTTP.send(HttpRequest.newBuilder(URI.create(url("/saude"))).build(),
						HttpResponse.BodyHandlers.discarding());
				if (resposta.statusCode() == 200) {
					return;
				}
			}
			catch (IOException naoSubiuAinda) {
				// tenta de novo
			}
			Thread.sleep(500);
		}
		throw new IllegalStateException(nome + " não respondeu /saude em 90 s");
	}

}
