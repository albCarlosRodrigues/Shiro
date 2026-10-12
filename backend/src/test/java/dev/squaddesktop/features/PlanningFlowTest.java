package dev.squaddesktop.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.squaddesktop.documents.Author;
import dev.squaddesktop.documents.Document;
import dev.squaddesktop.documents.DocumentKind;
import dev.squaddesktop.planning.PlanningService;
import dev.squaddesktop.planning.ProposalService;
import dev.squaddesktop.plans.PlanService;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.runs.RunOutcome;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.testsupport.Fixture;

/** Fluxo completo no modo demonstração: sala → plano → aprovação → autorização → etapas → revisão. */
@Timeout(60)
class PlanningFlowTest {

    @TempDir
    Path dir;

    private Fixture fx;

    @AfterEach
    void close() {
        if (fx != null) {
            fx.close();
        }
    }

    private Fixture demo() {
        fx = new Fixture(dir, (spec, sink, control) -> RunOutcome.failure(1, "unexpected", "executor real não deveria rodar", List.of()));
        fx.demoMode(true);
        return fx;
    }

    @Test
    void comandanteProporPlanoQueViraFeaturesSoAposAprovacao() throws Exception {
        Fixture f = demo();
        Project project = f.projectWithSquad("Livraria");

        f.planning.post(project.id(), "Quero um catálogo de livros com busca.");
        Fixture.await("plano do comandante", () -> !f.plans.list(project.id()).isEmpty()
                && f.planning.room(project.id(), null, 50).round() == null);

        PlanningService.Room room = f.planning.room(project.id(), null, 50);
        assertThat(room.messages()).extracting(PlanningService.PlanningMessage::authorType).containsExactly("USER", "AGENT");
        PlanningService.PlanningMessage reply = room.messages().get(1);
        assertThat(reply.content()).doesNotContain("```squad-plano");
        assertThat(reply.attachments()).singleElement().satisfies(a -> assertThat(a.get("type")).isEqualTo("plan"));

        PlanService.Plan plan = f.plans.list(project.id()).get(0);
        assertThat(plan.problems()).noneMatch(p -> p.blocking());
        assertThat(f.features.list(project.id())).as("planejar não cria features").isEmpty();

        FeatureService.ApplyResult applied = f.plans.approve(plan.id());
        assertThat(applied.created()).isEqualTo(3);
        List<Feature> features = f.features.list(project.id());
        assertThat(features).allMatch(x -> x.status() == FeatureStatus.PLANNED);
        assertThat(f.sessions.listByProject(project.id(), false)).as("aprovar o plano não inicia execução").isEmpty();
        Feature f2 = features.stream().filter(x -> x.code().equals("F2")).findFirst().orElseThrow();
        Feature f1 = features.stream().filter(x -> x.code().equals("F1")).findFirst().orElseThrow();
        assertThat(f2.dependsOn()).containsExactly(f1.id());
        assertThat(f.features.overview(project.id()).blockedBy().get(f2.id())).containsExactly("F1");
        assertThatThrownBy(() -> f.plans.approve(plan.id())).isInstanceOf(ApiException.class);
    }

    @Test
    void executaNaOrdemDasDependenciasERevisaoHumanaLiberaAsSeguintes() throws Exception {
        Fixture f = demo();
        Project project = f.projectWithSquad("Livraria");
        f.planning.post(project.id(), "Planeje o catálogo.");
        Fixture.await("plano", () -> !f.plans.list(project.id()).isEmpty());
        f.plans.approve(f.plans.list(project.id()).get(0).id());

        assertThat(f.featureRunner.authorizeAll(project.id())).isEqualTo(3);
        Fixture.await("F1 em revisão", () -> status(f, project, "F1") == FeatureStatus.IN_REVIEW);
        assertThat(status(f, project, "F2")).as("F2 espera a aprovação de F1").isEqualTo(FeatureStatus.QUEUED);
        Feature f1 = byCode(f, project, "F1");
        FeatureService.FeatureDetail detail = f.features.detail(f1.id());
        assertThat(detail.steps()).singleElement().satisfies(s -> {
            assertThat(s.status()).isEqualTo("DONE");
            assertThat(s.handoff()).containsKeys("objetivo", "entregas", "testes", "pendencias");
        });

        // pedir ajustes devolve para a fila e o responsável recebe o comentário
        f.featureRunner.requestChanges(f1.id(), "Mostre também o ISBN.");
        Fixture.await("F1 de volta à revisão", () -> status(f, project, "F1") == FeatureStatus.IN_REVIEW
                && f.features.detail(f1.id()).steps().size() == 2);
        FeatureStep second = f.features.detail(f1.id()).steps().get(1);
        assertThat(second.revision()).isEqualTo(1);
        String prompt = f.runs.get(second.runId()).prompt();
        assertThat(prompt).contains("Mostre também o ISBN.");
        assertThat(status(f, project, "F2")).isEqualTo(FeatureStatus.QUEUED);

        f.featureRunner.approve(f1.id(), null);
        assertThat(status(f, project, "F1")).isEqualTo(FeatureStatus.APPROVED);
        Fixture.await("F2 em revisão", () -> status(f, project, "F2") == FeatureStatus.IN_REVIEW);
        assertThat(status(f, project, "F3")).isEqualTo(FeatureStatus.QUEUED);
        f.featureRunner.approve(byCode(f, project, "F2").id(), "ok");
        Fixture.await("F3 em revisão", () -> status(f, project, "F3") == FeatureStatus.IN_REVIEW);
        assertThat(f.features.detail(byCode(f, project, "F2").id()).reviews()).extracting(FeatureReview::decision).containsExactly("APPROVED");
    }

    @Test
    void filaPausadaNaoIniciaNovasFeaturesEReinicioPausa() throws Exception {
        Fixture f = demo();
        Project project = f.projectWithSquad("Livraria");
        f.planning.post(project.id(), "Planeje.");
        Fixture.await("plano", () -> !f.plans.list(project.id()).isEmpty());
        f.plans.approve(f.plans.list(project.id()).get(0).id());

        f.featureRunner.pause(project.id());
        f.featureRunner.authorize(byCode(f, project, "F1").id());
        Thread.sleep(150);
        assertThat(status(f, project, "F1")).isEqualTo(FeatureStatus.QUEUED);
        assertThat(f.features.overview(project.id()).queuePaused()).isTrue();

        // reinício: a fila continua pausada, com a explicação
        f.featureRunner.resume(project.id());
        Fixture.await("F1 em revisão", () -> status(f, project, "F1") == FeatureStatus.IN_REVIEW);
        f.featureRunner.authorize(byCode(f, project, "F2").id());
        f.featureRunner.pause(project.id());
        f.featureRunner.reconcile();
        assertThat(f.features.overview(project.id()).queuePaused()).isTrue();
    }

    @Test
    void mencaoChamaOAgenteEPropostaSoMudaODocumentoQuandoAplicada() throws Exception {
        Fixture f = demo();
        Project project = f.projectWithSquad("Livraria");
        f.planning.post(project.id(), "@AnalistaDeRequisitos revise o briefing, por favor.");
        Fixture.await("proposta", () -> !f.proposals.list(project.id()).isEmpty()
                && f.planning.room(project.id(), null, 50).round() == null);

        ProposalService.Proposal proposal = f.proposals.list(project.id()).get(0);
        Document briefing = f.documents.findSingleton(project.id(), DocumentKind.BRIEFING).orElseThrow();
        assertThat(proposal.documentId()).isEqualTo(briefing.id());
        assertThat(briefing.content()).doesNotContain("Permitir que leitores");

        // a pessoa edita o briefing depois da proposta: aplicar exige confirmação
        Document edited = f.documents.save(briefing.id(), null, "Minha versão", briefing.version(), Author.USER, null);
        assertThatThrownBy(() -> f.proposals.apply(proposal.id(), false))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("proposal_outdated");
                    assertThat(e.details()).containsEntry("currentContent", "Minha versão");
                });
        ProposalService.Proposal applied = f.proposals.apply(proposal.id(), true);
        Document after = f.documents.get(briefing.id());
        assertThat(after.content()).contains("Permitir que leitores");
        assertThat(after.updatedByType()).isEqualTo(Author.Type.AGENT);
        assertThat(applied.appliedVersion()).isEqualTo(edited.version() + 1);

        f.proposals.undo(proposal.id());
        assertThat(f.documents.get(briefing.id()).content()).isEqualTo("Minha versão");
        assertThat(f.proposals.get(proposal.id()).status()).isEqualTo("UNDONE");
    }

    @Test
    void rodadaAtivaRecusaNovaMensagemEPodeSerInterrompida() throws Exception {
        fx = new Fixture(dir, (spec, sink, control) -> {
            while (!control.cancelled()) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new RunOutcome(null, false, null, null, null, List.of());
        });
        Fixture f = fx;
        Project project = f.projectWithSquad("Livraria");
        f.planning.post(project.id(), "Olá");
        assertThatThrownBy(() -> f.planning.post(project.id(), "Outra"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("round_active"));
        f.planning.stop(project.id());
        Fixture.await("sem rodada", () -> f.planning.room(project.id(), null, 50).round() == null
                && f.runs.unfinished().isEmpty());
        assertThat(f.planning.room(project.id(), null, 50).messages()).extracting(PlanningService.PlanningMessage::authorType)
                .contains("SYSTEM");
    }

    @Test
    void falhaNaEtapaNaoERepetidaECancelamentoNaoEFalha() throws Exception {
        Map<String, Integer> calls = new java.util.concurrent.ConcurrentHashMap<>();
        fx = new Fixture(dir, (spec, sink, control) -> {
            calls.merge(spec.kind().name(), 1, Integer::sum);
            if (spec.prompt().contains("Lenta")) {
                while (!control.cancelled()) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new RunOutcome(null, false, null, null, null, List.of());
            }
            return RunOutcome.failure(1, "process_failed", "O Claude Code terminou com erro.", List.of());
        });
        Fixture f = fx;
        Project project = f.projectWithSquad("Livraria");
        String owner = f.members.of(project).get(3).id();
        Feature quebra = f.features.create(project.id(), new FeatureService.FeatureDraft("Q1", "Quebra", null, "alta", null, owner, null,
                List.of(new Feature.Criterion("algo", false)), null, null));
        f.featureRunner.authorize(quebra.id());
        Fixture.await("falha", () -> f.features.get(quebra.id()).status() == FeatureStatus.FAILED);
        Thread.sleep(100);
        assertThat(calls.get("EXECUTION")).as("sem nova tentativa automática").isEqualTo(1);
        assertThat(f.features.get(quebra.id()).statusNote()).contains("Nada é repetido automaticamente");

        Feature lenta = f.features.create(project.id(), new FeatureService.FeatureDraft("L1", "Lenta", null, null, null, owner, null,
                null, null, null));
        f.featureRunner.authorize(lenta.id());
        Fixture.await("em execução", () -> f.features.get(lenta.id()).status() == FeatureStatus.IN_PROGRESS);
        f.featureRunner.cancel(lenta.id());
        Fixture.await("cancelada", () -> f.features.get(lenta.id()).status() == FeatureStatus.CANCELLED);
    }

    private static FeatureStatus status(Fixture f, Project project, String code) {
        return byCode(f, project, code).status();
    }

    private static Feature byCode(Fixture f, Project project, String code) {
        return f.features.list(project.id()).stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow();
    }
}
