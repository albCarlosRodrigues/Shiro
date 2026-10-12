package dev.squaddesktop.plans;

import java.util.List;

/**
 * Conteúdo estruturado de um plano: sprints e features com responsável, ordem (dependências) e critérios verificáveis.
 * É o formato que o comandante preenche no bloco {@code squad-plano} e que a pessoa edita na revisão do plano.
 */
public record PlanContent(String summary, List<SprintItem> sprints, List<FeatureItem> features) {

    public record SprintItem(String name, String goal) {
    }

    /**
     * @param owner     nome, apelido ou id do agente responsável
     * @param priority  HIGH, MEDIUM ou LOW (aceita ALTA, MÉDIA, BAIXA)
     * @param dependsOn códigos de features deste plano ou já cadastradas no projeto
     */
    public record FeatureItem(String code, String title, String description, String priority, String specialty, String owner,
            String sprint, List<String> criteria, List<String> tasks, List<String> dependsOn) {
    }
}
