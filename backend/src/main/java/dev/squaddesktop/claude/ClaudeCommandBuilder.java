package dev.squaddesktop.claude;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.squaddesktop.runs.RunSpec;

/**
 * Monta os argumentos de {@code claude -p}. O prompt nunca entra aqui: vai pela entrada padrão.
 * Cada flag opcional só é usada se a CLI instalada a oferece; as omitidas são devolvidas em {@link Command#skipped}.
 */
public final class ClaudeCommandBuilder {

    /** @param skipped descrições, para a pessoa, do que foi pedido e a CLI instalada não oferece */
    public record Command(List<String> args, List<String> skipped) {
    }

    private ClaudeCommandBuilder() {
    }

    public static Command build(RunSpec spec, ClaudeCapabilities caps, Path systemPromptFile) {
        List<String> args = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        args.add("-p");
        args.add("--output-format");
        args.add("stream-json");
        args.add("--verbose");
        if (caps.partialMessages()) {
            args.add("--include-partial-messages");
        } else {
            skipped.add("texto em tempo real (a resposta aparece ao fim de cada mensagem)");
        }
        if (caps.permissionPrompts()) {
            // Ninguém responde a pedidos de permissão num processo sem terminal: o que pediria confirmação é negado e relatado.
            args.add("--permission-prompts");
            args.add("none");
        }
        if (spec.permissionMode() != null) {
            args.add("--permission-mode");
            args.add(spec.permissionMode());
        }
        if (spec.model() != null) {
            args.add("--model");
            args.add(spec.model());
        }
        if (spec.effort() != null) {
            if (caps.effort() && (caps.effortLevels().isEmpty() || caps.effortLevels().contains(spec.effort()))) {
                args.add("--effort");
                args.add(spec.effort());
            } else {
                skipped.add("nível de esforço \"" + spec.effort() + "\"");
            }
        }
        if (spec.maxBudgetUsd() != null) {
            if (caps.maxBudget()) {
                args.add("--max-budget-usd");
                args.add(String.format(Locale.ROOT, "%.2f", spec.maxBudgetUsd()));
            } else {
                skipped.add("orçamento máximo por execução");
            }
        }
        if (spec.resumeSessionId() != null && caps.resume()) {
            args.add("--resume");
            args.add(spec.resumeSessionId());
        } else if (spec.newSessionId() != null && caps.sessionId()) {
            args.add("--session-id");
            args.add(spec.newSessionId());
        }
        if (systemPromptFile != null) {
            if (caps.appendSystemPromptFile()) {
                args.add("--append-system-prompt-file");
                args.add(systemPromptFile.toString());
                if (caps.systemPromptSnapshot()) {
                    // Instruções e documentos do projeto podem mudar entre mensagens: a conversa retomada usa a versão atual.
                    args.add("--system-prompt-snapshot");
                    args.add("off");
                }
            } else {
                skipped.add("instruções do agente (a CLI instalada não aceita arquivo de prompt de sistema)");
            }
        }
        if (spec.tools() != null) {
            if (caps.toolsFlag()) {
                args.add("--tools");
                args.add(String.join(",", spec.tools()));
            } else {
                skipped.add("restrição de ferramentas disponíveis");
            }
        }
        if (spec.allowedTools() != null && !spec.allowedTools().isEmpty()) {
            args.add("--allowedTools");
            args.add(String.join(",", spec.allowedTools()));
        }
        if (spec.disallowedTools() != null && !spec.disallowedTools().isEmpty()) {
            args.add("--disallowedTools");
            args.add(String.join(",", spec.disallowedTools()));
        }
        return new Command(List.copyOf(args), List.copyOf(skipped));
    }

    /** Versão para histórico e console: troca o caminho do arquivo temporário de instruções por um marcador. */
    public static List<String> forDisplay(List<String> launcher, List<String> args, Path systemPromptFile) {
        List<String> out = new ArrayList<>();
        // só o nome do arquivo: o caminho completo pode expor a pasta da pessoa e nem sempre é um caminho válido
        // (por exemplo, um classpath com ";" e ":" no Windows), por isso não passa por Path.of
        launcher.stream().map(ClaudeCommandBuilder::lastSegment).forEach(out::add);
        for (String arg : args) {
            out.add(systemPromptFile != null && arg.equals(systemPromptFile.toString()) ? "<instruções do agente>" : arg);
        }
        return out;
    }

    private static String lastSegment(String part) {
        int cut = Math.max(part.lastIndexOf('/'), part.lastIndexOf('\\'));
        return cut >= 0 && cut < part.length() - 1 ? part.substring(cut + 1) : part;
    }
}
