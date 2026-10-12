package dev.squaddesktop.agents;

import java.util.List;

import dev.squaddesktop.documents.Document;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.shared.Texts;

/**
 * Monta as instruções finais de um agente: é o texto anexado ao prompt de sistema do Claude Code
 * e o mesmo que o Estúdio do Agente mostra na prévia.
 *
 * <p>As instruções não dependem do modo da mensagem. O Claude Code grava o prompt de sistema na primeira
 * chamada de uma conversa e o reaproveita ao retomá-la; por isso o modo (conversa ou execução) vai num
 * preâmbulo de cada mensagem ({@link #turnPreamble}), e a garantia de que uma conversa não altera arquivos
 * vem das permissões da execução, não do texto.
 */
public final class InstructionComposer {

    /** Limite por documento do projeto, para o contexto não crescer sem controle. */
    static final int MAX_DOCUMENT_CHARS = 6_000;

    public enum Mode {
        /** Conversa e planejamento: o agente analisa e propõe, sem alterar arquivos. */
        CONVERSATION,
        /** Execução autorizada de uma tarefa na pasta do projeto. */
        EXECUTION
    }

    private InstructionComposer() {
    }

    public static String compose(Agent agent, Project project, List<Document> documents) {
        StringBuilder out = new StringBuilder();
        out.append("# Você é ").append(agent.name()).append('\n');
        out.append("Papel na squad: ").append(agent.role().label());
        if (!Texts.isBlank(agent.specialty())) {
            out.append(" (").append(agent.specialty().strip()).append(')');
        }
        out.append(".\n");
        section(out, "Responsabilidade", agent.responsibility());
        section(out, "Jeito de trabalhar", agent.personality());
        section(out, "Instruções", agent.instructions());
        section(out, "Diretrizes", agent.guidelines());

        if (project != null) {
            out.append("\n## Projeto: ").append(project.name()).append('\n');
            if (project.folderPath() != null) {
                out.append("Pasta de trabalho: `").append(project.folderPath()).append("`\n");
            }
            if (documents != null) {
                for (Document document : documents) {
                    if (!Texts.isBlank(document.content())) {
                        out.append("\n### ").append(document.title()).append('\n');
                        out.append(Texts.truncate(document.content().strip(), MAX_DOCUMENT_CHARS)).append('\n');
                    }
                }
            }
        }

        out.append("\n## Regras do Squad Desktop\n");
        out.append("- Responda em português do Brasil.\n");
        out.append("- Cada mensagem começa com o modo em que ela deve ser atendida; siga esse modo.\n");
        out.append("- Mantenha todos os arquivos dentro da pasta de trabalho do projeto.\n");
        out.append("- Se uma ferramenta for negada, não tente contornar: diga qual permissão faltou.\n");
        return out.toString();
    }

    /** Preâmbulo da mensagem enviada ao agente, conforme o modo pedido pela pessoa. */
    public static String turnPreamble(Mode mode) {
        if (mode == Mode.CONVERSATION) {
            return "[Modo desta mensagem: conversa. Analise e responda; não crie, edite nem apague arquivos e não execute "
                    + "comandos que alterem o projeto. Mudanças em documentos vão como proposta para a pessoa aplicar.]";
        }
        return "[Modo desta mensagem: execução autorizada na pasta do projeto. Ao terminar, resuma o que foi entregue, "
                + "os arquivos alterados, o que foi verificado e as pendências.]";
    }

    /** Texto efetivamente enviado ao Claude Code: preâmbulo do modo seguido da mensagem da pessoa. */
    public static String turnPrompt(Mode mode, String message) {
        return turnPreamble(mode) + "\n\n" + message;
    }

    private static void section(StringBuilder out, String title, String body) {
        if (!Texts.isBlank(body)) {
            out.append("\n## ").append(title).append('\n').append(body.strip()).append('\n');
        }
    }
}
