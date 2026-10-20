package io.github.lakes777.foil.estoque;

/**
 * Uma gema do jogo. {@code cor} e {@code fluorescencia} são chaves de paleta que a interface usa para desenhar a gema
 * (de dia e na luz negra); {@code fluorescencia} null = a gema não fluoresce.
 */
public record Carta(long id, String nome, String raridade, String descricao, int mohs, String cor, String fluorescencia) {

}
