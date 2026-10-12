package dev.squaddesktop.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Respostas fixas do modo demonstração para a sala de planejamento e para as etapas de feature. São sempre as
 * mesmas para o mesmo pedido e usam o mesmo formato de blocos dos agentes reais, para o fluxo inteiro poder ser
 * conhecido sem Claude Code.
 */
final class DemoScripts {

    private static final Pattern PARTICIPANT = Pattern.compile("(?m)^- @([A-Za-z0-9]+) — ([^,\\n]+)(?:, ([^\\n]+))?");
    private static final Pattern SELF = Pattern.compile("Você é @([A-Za-z0-9]+)");
    private static final Pattern FEATURE = Pattern.compile("^# Feature ([^:]+): (.*)$", Pattern.MULTILINE);
    private static final Pattern STEP = Pattern.compile("(?m)^Etapa (\\d+)");

    private DemoScripts() {
    }

    static String planning(String agentName, String prompt) {
        Matcher self = SELF.matcher(prompt);
        String handle = self.find() ? self.group(1) : "Agente";
        boolean commander = prompt.contains("Você é o comandante");
        List<String> operators = new ArrayList<>();
        java.util.Map<String, String> specialties = new java.util.HashMap<>();
        String role = "";
        Matcher m = PARTICIPANT.matcher(prompt);
        while (m.find()) {
            if (m.group(2).startsWith("Operador")) {
                operators.add(m.group(1));
                specialties.put(m.group(1), m.group(3) == null ? "" : m.group(3).toLowerCase(java.util.Locale.ROOT));
            }
            if (m.group(1).equals(handle)) {
                role = m.group(2).strip();
            }
        }
        if (commander) {
            String fallback = operators.isEmpty() ? handle : operators.get(0);
            String a = pick(operators, specialties, fallback, "backend", "java", "spring");
            String b = pick(operators, specialties, fallback, "frontend", "angular", "interface");
            String c = pick(operators, specialties, fallback, "qa", "qualidade", "teste");
            return "(Demonstração) Sou " + agentName + ". Organizei o pedido em três features, com o catálogo primeiro porque as "
                    + "outras dependem dele. Revise o plano anexo: nada será executado sem a sua autorização.\n\n"
                    + "```squad-plano\n"
                    + "{\"resumo\": \"Plano de demonstração em duas sprints\", "
                    + "\"sprints\": [{\"nome\": \"Sprint 1\", \"objetivo\": \"Base do catálogo\"}, {\"nome\": \"Sprint 2\", \"objetivo\": \"Busca e qualidade\"}], "
                    + "\"features\": ["
                    + "{\"codigo\": \"F1\", \"titulo\": \"Catálogo de livros\", \"descricao\": \"Listar livros com título, autor e preço.\", "
                    + "\"prioridade\": \"alta\", \"especialidade\": \"Backend\", \"responsavel\": \"@" + a + "\", \"sprint\": \"Sprint 1\", "
                    + "\"criterios\": [\"A listagem mostra título, autor e preço\", \"Livros sem estoque aparecem marcados\"], "
                    + "\"tarefas\": [\"Modelo de livro\", \"Endpoint de listagem\"], \"depende_de\": []}, "
                    + "{\"codigo\": \"F2\", \"titulo\": \"Busca por título\", \"descricao\": \"Filtrar o catálogo pelo título.\", "
                    + "\"prioridade\": \"media\", \"especialidade\": \"Frontend\", \"responsavel\": \"@" + b + "\", \"sprint\": \"Sprint 2\", "
                    + "\"criterios\": [\"A busca ignora maiúsculas e acentos\"], \"tarefas\": [\"Campo de busca\"], \"depende_de\": [\"F1\"]}, "
                    + "{\"codigo\": \"F3\", \"titulo\": \"Testes do catálogo\", \"descricao\": \"Cobrir listagem e busca com testes.\", "
                    + "\"prioridade\": \"baixa\", \"especialidade\": \"QA\", \"responsavel\": \"@" + c + "\", \"sprint\": \"Sprint 2\", "
                    + "\"criterios\": [\"Os testes rodam sem falhas\"], \"tarefas\": [\"Testes de listagem\", \"Testes de busca\"], \"depende_de\": [\"F1\", \"F2\"]}"
                    + "]}\n```";
        }
        if (role.startsWith("Requisitos")) {
            return "(Demonstração) Sou " + agentName + ". Sugiro deixar o escopo explícito no briefing; a proposta está anexa para você "
                    + "aplicar ou descartar.\n\n"
                    + "```squad-proposta\n"
                    + "{\"documento\": \"briefing\", \"resumo\": \"Inclui objetivo e público do catálogo\", "
                    + "\"conteudo\": \"# Briefing\\n\\n## Objetivo\\nPermitir que leitores encontrem livros rapidamente.\\n\\n"
                    + "## Público\\nClientes da livraria, no computador e no celular.\\n\\n## Fora do escopo\\nPagamentos nesta fase.\\n\"}\n```";
        }
        return "(Demonstração) Sou " + agentName + ". Do ponto de vista de " + (role.isEmpty() ? "agente" : role.toLowerCase())
                + ", o plano parece viável; recomendo manter as dependências na ordem proposta. Nada foi lido de verdade.";
    }

    static String featureStep(String agentName, String prompt) {
        Matcher f = FEATURE.matcher(prompt);
        boolean found = f.find();
        String code = found ? f.group(1).strip() : "F?";
        String title = found ? f.group(2).strip() : "";
        Matcher s = STEP.matcher(prompt);
        String step = s.find() ? s.group(1) : "1";
        String adjustments = prompt.contains("## Ajustes pedidos na revisão humana") ? " Considerei os ajustes pedidos na revisão." : "";
        return "(Demonstração) " + agentName + " concluiu a etapa " + step + " da feature " + code + "." + adjustments
                + " Numa execução real eu alteraria os arquivos da pasta e rodaria os testes; aqui nada foi alterado.\n\n"
                + "```squad-entrega\n"
                + "{\"objetivo\": \"" + escape(title) + "\", \"contexto\": \"Execução simulada no modo demonstração.\", "
                + "\"entregas\": [\"Implementação simulada de " + escape(code) + "\"], \"arquivos\": [], "
                + "\"pendencias\": [\"Executar com o Claude Code para ter alterações reais\"], "
                + "\"criterios_validacao\": [\"Conferir cada critério de aceite na revisão\"], "
                + "\"testes\": [\"Nenhum teste foi executado (demonstração)\"], \"proximo\": null, \"mensagem_proximo\": \"\"}\n```";
    }

    private static String pick(List<String> operators, java.util.Map<String, String> specialties, String fallback, String... words) {
        for (String op : operators) {
            String text = specialties.getOrDefault(op, "") + " " + op.toLowerCase(java.util.Locale.ROOT);
            for (String w : words) {
                if (text.contains(w)) {
                    return op;
                }
            }
        }
        return fallback;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
