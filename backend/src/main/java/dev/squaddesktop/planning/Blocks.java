package dev.squaddesktop.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.squaddesktop.shared.JsonCodec;

/**
 * Blocos estruturados que os agentes deixam no fim da resposta:
 *
 * <pre>
 * ```squad-proposta   alteração proposta para um documento do projeto
 * ```squad-plano      plano de features (comandante)
 * ```squad-entrega    passagem ao fim de uma etapa de execução
 * </pre>
 *
 * O bloco termina numa linha que contém apenas a cerca. Dentro de uma string JSON não existe quebra de linha
 * literal, então uma cerca de Markdown dentro do conteúdo proposto não encerra o bloco antes da hora.
 */
public final class Blocks {

    public static final String PROPOSAL = "proposta";
    public static final String PLAN = "plano";
    public static final String HANDOFF = "entrega";

    private static final Pattern BLOCK = Pattern.compile("(?ms)^[ \\t]*```squad-([a-z]+)[^\\n]*\\n(.*?)\\n[ \\t]*```[ \\t]*$");

    /** Um bloco encontrado. {@code data} é null quando o JSON não pôde ser lido; {@code error} explica o motivo. */
    public record Block(String kind, Map<String, Object> data, String error) {
    }

    /** Texto sem os blocos (o que aparece na conversa) e os blocos, na ordem em que vieram. */
    public record Parsed(String text, List<Block> blocks) {
        public List<Block> of(String kind) {
            return blocks.stream().filter(b -> b.kind().equals(kind)).toList();
        }
    }

    private Blocks() {
    }

    @SuppressWarnings("unchecked")
    public static Parsed parse(String text, JsonCodec json) {
        if (text == null || text.isEmpty()) {
            return new Parsed("", List.of());
        }
        List<Block> blocks = new ArrayList<>();
        StringBuilder clean = new StringBuilder();
        Matcher m = BLOCK.matcher(text);
        int last = 0;
        while (m.find()) {
            clean.append(text, last, m.start());
            last = m.end();
            String kind = m.group(1);
            String body = m.group(2).strip();
            try {
                Object value = json.parse(body);
                if (value instanceof Map<?, ?> map) {
                    blocks.add(new Block(kind, (Map<String, Object>) map, null));
                } else {
                    blocks.add(new Block(kind, null, "o bloco precisa ser um objeto JSON"));
                }
            } catch (IllegalArgumentException e) {
                blocks.add(new Block(kind, null, "JSON inválido no bloco squad-" + kind));
            }
        }
        clean.append(text.substring(last));
        String cleaned = clean.toString().replaceAll("\\n{3,}", "\n\n").strip();
        return new Parsed(cleaned, List.copyOf(blocks));
    }
}
