package dev.squaddesktop.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.planning.Blocks;
import dev.squaddesktop.planning.Mentions;
import dev.squaddesktop.plans.DependencyGraph;
import dev.squaddesktop.plans.PlanContent;
import dev.squaddesktop.plans.PlanService;
import dev.squaddesktop.plans.Problem;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.runs.RunOutcome;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.testsupport.Fixture;
import dev.squaddesktop.testsupport.TestJson;

/** Regras de plano, dependências, estados de feature e leitura dos blocos dos agentes. */
class PlanAndFeatureRulesTest {

    @TempDir
    Path dir;

    private Fixture f;
    private Project project;
    private List<Agent> squad;

    @BeforeEach
    void setUp() throws Exception {
        f = new Fixture(dir, (spec, sink, control) -> RunOutcome.failure(1, "x", "não deve rodar", List.of()));
        project = f.projectWithSquad("Projeto");
        squad = f.members.of(project);
    }

    @AfterEach
    void close() {
        f.close();
    }

    private static PlanContent.FeatureItem item(String code, String owner, List<String> criteria, List<String> deps) {
        return new PlanContent.FeatureItem(code, "Título " + code, "Descrição", "alta", "Backend", owner, "Sprint 1", criteria,
                List.of("tarefa"), deps);
    }

    private List<String> codes(List<Problem> problems) {
        return problems.stream().filter(Problem::blocking).map(Problem::code).toList();
    }

    @Test
    void validacaoApontaCicloCamposIncompletosEResponsavelDesconhecido() {
        String owner = "@" + Mentions.handle(squad.get(3).name());
        PlanContent content = new PlanContent("x", List.of(new PlanContent.SprintItem("Sprint 1", "")), List.of(
                item("A", owner, List.of("ok"), List.of("C")),
                item("B", owner, List.of("ok"), List.of("A")),
                item("C", owner, List.of("ok"), List.of("B")),
                item("D", "@Ninguem", List.of(), List.of("D", "Z"))));
        List<Problem> problems = f.plans.validate(project.id(), content);
        assertThat(codes(problems)).contains("dependency_cycle", "owner_unknown", "criteria_missing", "dependency_self", "dependency_unknown");
        Problem cycle = problems.stream().filter(p -> p.code().equals("dependency_cycle")).findFirst().orElseThrow();
        assertThat(cycle.message()).contains("→");

        PlanService.Plan plan = f.plans.createBlank(project.id());
        PlanService.Plan saved = f.plans.update(plan.id(), content);
        assertThat(saved.approvable()).isFalse();
        assertThatThrownBy(() -> f.plans.approve(plan.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("plan_invalid"));
        assertThat(f.features.list(project.id())).isEmpty();
    }

    @Test
    void normalizaChavesEmPortuguesEPrioridades() {
        PlanContent content = PlanService.normalize(Map.of("resumo", "r",
                "features", List.of(Map.of("codigo", "F1", "titulo", "T", "prioridade", "Média", "responsavel", "@X",
                        "criterios", List.of("c"), "depende_de", List.of()))));
        assertThat(content.features()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("F1");
            assertThat(PlanService.normalizePriority(i.priority())).isEqualTo("MEDIUM");
        });
        assertThat(PlanService.normalizePriority("ALTA")).isEqualTo("HIGH");
        assertThat(PlanService.normalizePriority("urgente")).isNull();
    }

    @Test
    void grafoOrdenaDependenciasEDetectaCiclo() {
        DependencyGraph graph = new DependencyGraph().add("C", List.of("B")).add("B", List.of("A")).add("A", List.of());
        assertThat(graph.order()).containsExactly("A", "B", "C");
        graph.add("A", List.of("C"));
        assertThat(graph.cycles()).hasSize(1);
        assertThat(graph.order()).isNull();
    }

    @Test
    void edicaoDeFeatureRecusaCicloEFeaturesEmAndamento() {
        String owner = squad.get(3).id();
        Feature a = f.features.create(project.id(), draft("A", owner, null));
        Feature b = f.features.create(project.id(), draft("B", owner, List.of("A")));
        assertThatThrownBy(() -> f.features.update(a.id(), draft(null, null, List.of("B"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("dependency_cycle"));
        assertThatThrownBy(() -> f.features.delete(a.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("feature_has_dependents"));
        assertThatThrownBy(() -> f.features.create(project.id(), draft("A", owner, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("code_taken"));
        assertThat(f.features.overview(project.id()).blockedBy().get(b.id())).containsExactly("A");

        // em revisão: só checklists podem mudar
        f.database.writeVoid(tx -> f.features.repository.update(tx, b.withStatus(FeatureStatus.IN_REVIEW, null, "2026-01-01T00:00:00Z")));
        assertThatThrownBy(() -> f.features.update(b.id(), draft(null, null, List.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("feature_locked"));
        Feature checked = f.features.setCriterion(b.id(), 0, true);
        assertThat(checked.acceptanceCriteria().get(0).met()).isTrue();
        assertThatThrownBy(() -> f.featureRunner.authorize(b.id())).isInstanceOf(ApiException.class);
    }

    @Test
    void transicoesDeEstadoSoPermitemOCaminhoDocumentado() {
        assertThat(FeatureStatus.PLANNED.canTransitionTo(FeatureStatus.IN_PROGRESS)).isFalse();
        assertThat(FeatureStatus.IN_PROGRESS.canTransitionTo(FeatureStatus.APPROVED)).as("só a revisão aprova").isFalse();
        assertThat(FeatureStatus.IN_REVIEW.canTransitionTo(FeatureStatus.APPROVED)).isTrue();
        assertThat(FeatureStatus.FAILED.canTransitionTo(FeatureStatus.IN_PROGRESS)).as("falha não volta a rodar sozinha").isFalse();
        assertThat(FeatureStatus.APPROVED.canTransitionTo(FeatureStatus.QUEUED)).isFalse();
    }

    @Test
    void autorizacaoExigeResponsavel() {
        Feature semDono = f.features.create(project.id(), draft("S", null, null));
        assertThatThrownBy(() -> f.featureRunner.authorize(semDono.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("owner_required"));
    }

    @Test
    void blocosSaoSeparadosDoTextoEJsonInvalidoViraErro() {
        String text = """
                Resposta.

                ```squad-proposta
                {"documento": "briefing", "conteudo": "# B\\n```js\\nx\\n```"}
                ```

                ```squad-plano
                {nao é json}
                ```
                """;
        Blocks.Parsed parsed = Blocks.parse(text, TestJson.CODEC);
        assertThat(parsed.text()).isEqualTo("Resposta.");
        assertThat(parsed.of(Blocks.PROPOSAL)).singleElement().satisfies(b -> assertThat(b.data()).containsEntry("documento", "briefing"));
        assertThat(parsed.of(Blocks.PLAN)).singleElement().satisfies(b -> assertThat(b.error()).isNotNull());
    }

    @Test
    void mencoesEncontramAgentesPorApelidoEPapel() {
        Agent commander = squad.get(0);
        List<Agent> found = Mentions.find("Oi @comandante e @" + Mentions.handle(squad.get(4).name()) + ", e o e-mail a@b.com", squad);
        assertThat(found).extracting(Agent::id).containsExactly(commander.id(), squad.get(4).id());
    }

    @Test
    void passagemNormalizaCamposConhecidos() {
        Map<String, Object> handoff = FeaturePrompts.normalize(Map.of("objetivo", "x", "testes", List.of(Map.of("comando", "mvn test", "resultado", "ok")),
                "proximo", "nenhum"));
        assertThat(handoff.get("testes")).isEqualTo(List.of("mvn test → ok"));
        assertThat(FeaturePrompts.nextReference(handoff)).isNull();
        assertThat(handoff).containsKeys("entregas", "arquivos", "pendencias", "criterios_validacao");
    }

    private static FeatureService.FeatureDraft draft(String code, String owner, List<String> deps) {
        return new FeatureService.FeatureDraft(code, code == null ? null : "Feature " + code, null, null, null, owner, null,
                code == null ? null : List.of(new Feature.Criterion("critério", false)), null, deps);
    }
}
