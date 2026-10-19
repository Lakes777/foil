package io.github.lakes777.foil.comum;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/** O que os três serviços têm igual. Entra sozinho por estar no classpath. */
@AutoConfiguration
@Import(SaudeController.class)
public class ComumAutoConfiguration {

}
