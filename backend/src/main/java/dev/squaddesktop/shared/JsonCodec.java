package dev.squaddesktop.shared;

/**
 * Leitura e escrita de JSON para o núcleo, sem acoplar as regras a uma biblioteca.
 * {@link #parse(String)} devolve a árvore em tipos simples: Map, List, String, Number, Boolean ou null.
 */
public interface JsonCodec {

    /** @throws IllegalArgumentException quando o texto não é JSON válido */
    Object parse(String json);

    String write(Object value);
}
