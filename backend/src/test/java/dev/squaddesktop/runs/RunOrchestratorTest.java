package dev.squaddesktop.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.sessions.Message;
import dev.squaddesktop.sessions.Session;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.testsupport.Fixture;

@Timeout(60)
class RunOrchestratorTest {

    @TempDir
    Path dir;

    private Fixture fx;

    @AfterEach
    void tearDown() {
        if (fx != null) {
            fx.close();
        }
    }

    /** Executor controlado: cada execução espera um sinal do teste para terminar. */
    static final class GatedExecutor implements RunExecutor {
        final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        final List<RunSpec> specs = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile CountDownLatch started = new CountDownLatch(1);

        @Override
        public RunOutcome execute(RunSpec spec, Consumer<AgentEvent> sink, RunControl control) {
            specs.add(spec);
            CountDownLatch gate = gates.computeIfAbsent(spec.runId(), id -> new CountDownLatch(1));
            control.onCancel(gate::countDown);
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            sink.accept(new AgentEvent.Started("claude-" + spec.runId(), "modelo", spec.cwd().toString(), spec.permissionMode(), List.of(), "9.9.9", false));
            started.countDown();
            try {
                gate.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
            }
            sink.accept(new AgentEvent.TextDelta("res"));
            sink.accept(new AgentEvent.TextDelta("posta"));
            sink.accept(new AgentEvent.Message("resposta", null, null));
            return RunOutcome.success(0, "resposta", List.of("claude", "-p"));
        }

        void release(String runId) {
            gates.computeIfAbsent(runId, id -> new CountDownLatch(1)).countDown();
        }
    }

    @Test
    void conversaEmModoDemonstracaoCompletaEGravaHistorico() throws Exception {
        fx = new Fixture(dir, new GatedExecutor());
        fx.demoMode(true);
        Project project = fx.project("loja");
        Agent agent = fx.operator();
        Session session = fx.session(project, agent);

        RunOrchestrator.Submission submission = fx.runs.submit(session.id(), "Explique o projeto", RunKind.CHAT);
        assertThat(submission.message().role()).isEqualTo(Message.Role.USER);
        Run run = awaitTerminal(submission.run().id());

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.demo()).isTrue();
        assertThat(run.costUsd()).as("demonstração não informa custo").isNull();
        assertThat(fx.sessions.messages(session.id())).extracting(Message::role).containsExactly(Message.Role.USER, Message.Role.AGENT);
        assertThat(fx.sessions.get(session.id()).claudeSessionId()).as("sessão de demonstração não vira sessão do Claude").isNull();
        List<String> types = fx.eventTypes(run.id());
        assertThat(types).startsWith("run.queued").contains("run.started", "tool.call", "message").endsWith("run.finished");
        assertThat(types).as("trechos de texto são compactados ao final").doesNotContain("text.delta");
    }

    @Test
    void historicoDaSessaoTrazAsUltimasExecucoesEmOrdemCronologica() throws Exception {
        fx = new Fixture(dir, new GatedExecutor());
        fx.demoMode(true);
        Session session = fx.session(fx.project("loja"), fx.operator());
        List<String> ids = new java.util.ArrayList<>();
        for (String prompt : List.of("primeira", "segunda", "terceira")) {
            ids.add(awaitTerminal(fx.runs.submit(session.id(), prompt, RunKind.CHAT).run().id()).id());
        }

        assertThat(fx.runs.bySession(session.id(), 50)).extracting(Run::id).containsExactlyElementsOf(ids);
        assertThat(fx.runs.bySession(session.id(), 2)).extracting(Run::prompt).containsExactly("segunda", "terceira");
        assertThat(fx.runs.unfinished()).isEmpty();
    }

    @Test
    void conversaUsaSomenteFerramentasDeLeitura() throws Exception {
        GatedExecutor executor = new GatedExecutor();
        fx = new Fixture(dir, executor);
        Session session = fx.session(fx.project("p"), fx.operator());
        Run run = fx.runs.submit(session.id(), "oi", RunKind.CHAT).run();
        executor.release(run.id());
        awaitTerminal(run.id());
        RunSpec spec = executor.specs.get(0);
        assertThat(spec.permissionMode()).isEqualTo("dontAsk");
        assertThat(spec.tools()).containsExactly("Read", "Glob", "Grep");
        assertThat(spec.allowedTools()).isEmpty();
        assertThat(spec.prompt()).startsWith("[Modo desta mensagem: conversa.").endsWith("oi");
        assertThat(spec.systemPrompt()).doesNotContain("Modo desta mensagem");
    }

    @Test
    void execucaoUsaPermissoesDoAgenteERetomaASessao() throws Exception {
        GatedExecutor executor = new GatedExecutor();
        fx = new Fixture(dir, executor);
        Agent agent = fx.operator();
        Session session = fx.session(fx.project("p"), agent);
        Run first = fx.runs.submit(session.id(), "implemente", RunKind.EXECUTION).run();
        executor.release(first.id());
        awaitTerminal(first.id());
        Run second = fx.runs.submit(session.id(), "continue", RunKind.EXECUTION).run();
        executor.release(second.id());
        awaitTerminal(second.id());

        RunSpec a = executor.specs.get(0);
        RunSpec b = executor.specs.get(1);
        assertThat(a.permissionMode()).isEqualTo("acceptEdits");
        assertThat(a.allowedTools()).isEqualTo(agent.allowedTools());
        assertThat(a.resumeSessionId()).isNull();
        assertThat(a.newSessionId()).isNotNull();
        assertThat(b.resumeSessionId()).isEqualTo("claude-" + first.id());
        assertThat(b.prompt()).startsWith("[Modo desta mensagem: execução autorizada");
    }

    @Test
    void umaSessaoNaoAceitaDuasExecucoesAoMesmoTempo() throws Exception {
        GatedExecutor executor = new GatedExecutor();
        fx = new Fixture(dir, executor);
        Session session = fx.session(fx.project("p"), fx.operator());
        Run run = fx.runs.submit(session.id(), "um", RunKind.CHAT).run();
        assertThatThrownBy(() -> fx.runs.submit(session.id(), "dois", RunKind.CHAT))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("session_busy");
        executor.release(run.id());
        awaitTerminal(run.id());
    }

    @Test
    void somenteUmaExecucaoQueEscreveEmCadaPasta() throws Exception {
        GatedExecutor executor = new GatedExecutor();
        fx = new Fixture(dir, executor);
        fx.maxConcurrent(4);
        Project project = fx.project("mesma-pasta");
        Session s1 = fx.session(project, fx.operator());
        Session s2 = fx.session(project, fx.operator());
        Session s3 = fx.session(project, fx.operator());
        Run w1 = fx.runs.submit(s1.id(), "escreve 1", RunKind.EXECUTION).run();
        Run w2 = fx.runs.submit(s2.id(), "escreve 2", RunKind.EXECUTION).run();
        Run read = fx.runs.submit(s3.id(), "só lê", RunKind.CHAT).run();

        awaitStatus(read.id(), RunStatus.RUNNING);
        awaitStatus(w1.id(), RunStatus.RUNNING);
        Thread.sleep(200);
        assertThat(fx.runs.get(w2.id()).status()).as("segunda escrita espera a primeira").isEqualTo(RunStatus.QUEUED);

        executor.release(w1.id());
        awaitTerminal(w1.id());
        awaitStatus(w2.id(), RunStatus.RUNNING);
        executor.release(w2.id());
        executor.release(read.id());
        awaitTerminal(w2.id());
        awaitTerminal(read.id());
    }

    @Test
    void respeitaOLimiteDeExecucoesSimultaneas() throws Exception {
        GatedExecutor executor = new GatedExecutor();
        fx = new Fixture(dir, executor);
        fx.maxConcurrent(2);
        List<Run> runs = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Session session = fx.session(fx.project("p" + i), fx.operator());
            runs.add(fx.runs.submit(session.id(), "tarefa " + i, RunKind.CHAT).run());
        }
        Thread.sleep(300);
        assertThat(fx.runs.queueState().running()).isEqualTo(2);
        runs.forEach(r -> executor.release(r.id()));
        for (Run run : runs) {
            awaitTerminal(run.id());
        }
        assertThat(executor.maxConcurrent.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void cancelarEmAndamentoEncerraEMarcaCancelada() throws Exception {
        GatedExecutor executor = new GatedExecutor();
        fx = new Fixture(dir, executor);
        Session session = fx.session(fx.project("p"), fx.operator());
        Run run = fx.runs.submit(session.id(), "demora", RunKind.EXECUTION).run();
        awaitStatus(run.id(), RunStatus.RUNNING);
        fx.runs.cancel(run.id());
        Run done = awaitTerminal(run.id());
        assertThat(done.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fx.eventTypes(run.id())).contains("run.cancelled");
        assertThat(fx.runs.cancel(run.id()).status()).as("idempotente").isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void pausarAFilaImpedeNovasExecucoesMasNaoCancela() throws Exception {
        GatedExecutor executor = new GatedExecutor();
        fx = new Fixture(dir, executor);
        Session a = fx.session(fx.project("a"), fx.operator());
        Session b = fx.session(fx.project("b"), fx.operator());
        Run first = fx.runs.submit(a.id(), "primeira", RunKind.CHAT).run();
        awaitStatus(first.id(), RunStatus.RUNNING);
        fx.runs.pauseQueue();
        Run second = fx.runs.submit(b.id(), "segunda", RunKind.CHAT).run();
        Thread.sleep(200);
        assertThat(fx.runs.get(second.id()).status()).isEqualTo(RunStatus.QUEUED);
        assertThat(fx.runs.get(first.id()).status()).isEqualTo(RunStatus.RUNNING);
        executor.release(first.id());
        awaitTerminal(first.id());
        assertThat(fx.runs.get(second.id()).status()).isEqualTo(RunStatus.QUEUED);
        fx.runs.resumeQueue();
        executor.release(second.id());
        assertThat(awaitTerminal(second.id()).status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void cancelarNaFilaNaoIniciaProcesso() throws Exception {
        GatedExecutor executor = new GatedExecutor();
        fx = new Fixture(dir, executor);
        fx.runs.pauseQueue();
        Session session = fx.session(fx.project("p"), fx.operator());
        Run run = fx.runs.submit(session.id(), "nunca", RunKind.CHAT).run();
        assertThat(fx.runs.cancel(run.id()).status()).isEqualTo(RunStatus.CANCELLED);
        fx.runs.resumeQueue();
        Thread.sleep(100);
        assertThat(executor.specs).isEmpty();
    }

    @Test
    void falhaNaoERepetidaAutomaticamente() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RunExecutor failing = (spec, sink, control) -> {
            calls.incrementAndGet();
            return RunOutcome.failure(1, FailureCodes.CLI_ERROR, "deu errado", List.of());
        };
        fx = new Fixture(dir, failing);
        Session session = fx.session(fx.project("p"), fx.operator());
        Run run = awaitTerminal(fx.runs.submit(session.id(), "altera arquivos", RunKind.EXECUTION).run().id());
        Thread.sleep(200);
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failureCode()).isEqualTo(FailureCodes.CLI_ERROR);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(fx.sessions.messages(session.id())).last().extracting(Message::role).isEqualTo(Message.Role.SYSTEM);
    }

    @Test
    void execucaoExigePastaExistente() throws Exception {
        fx = new Fixture(dir, new GatedExecutor());
        Project project = fx.projects.create(new dev.squaddesktop.projects.ProjectDraft("sem pasta", null, null, null, null));
        Session session = fx.session(project, fx.operator());
        assertThatThrownBy(() -> fx.runs.submit(session.id(), "implemente", RunKind.EXECUTION))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("folder_required");
    }

    @Test
    void recuperacaoMarcaInterrompidasSemReiniciar() throws Exception {
        GatedExecutor executor = new GatedExecutor();
        fx = new Fixture(dir, executor);
        fx.runs.pauseQueue();
        Session session = fx.session(fx.project("p"), fx.operator());
        Run queued = fx.runs.submit(session.id(), "pendente", RunKind.CHAT).run();
        // simula a queda: a execução ficou sem desfecho no banco
        fx.database.writeVoid(tx -> tx.update("UPDATE run SET status = 'RUNNING' WHERE id = ?", queued.id()));

        assertThat(fx.runs.recoverInterrupted()).isEqualTo(1);
        Run recovered = fx.runs.get(queued.id());
        assertThat(recovered.status()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(recovered.failureCode()).isEqualTo(FailureCodes.APP_CLOSED);
        fx.runs.resumeQueue();
        Thread.sleep(100);
        assertThat(executor.specs).isEmpty();
        assertThat(fx.sessions.messages(session.id())).last().extracting(Message::content).asString().contains("interrompida");
    }

    @Test
    void negacoesDePermissaoFicamRegistradas() throws Exception {
        RunExecutor denying = (spec, sink, control) -> {
            sink.accept(new AgentEvent.PermissionDenied("Bash", "t1", Map.of("command", "npm run build"), "negado", "Bash(npm run *)"));
            return RunOutcome.success(0, "feito em parte", List.of());
        };
        fx = new Fixture(dir, denying);
        Session session = fx.session(fx.project("p"), fx.operator());
        Run run = awaitTerminal(fx.runs.submit(session.id(), "build", RunKind.EXECUTION).run().id());
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.permissionDenials()).singleElement().satisfies(d -> assertThat(d).containsEntry("suggestedRule", "Bash(npm run *)"));
        assertThat(fx.sessions.messages(session.id())).last().extracting(Message::content).asString().contains("permissão negada");
    }

    private Run awaitTerminal(String runId) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            Run run = fx.runs.get(runId);
            if (run.status().terminal()) {
                return run;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("execução não terminou: " + fx.runs.get(runId).status());
    }

    private void awaitStatus(String runId, RunStatus status) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            if (fx.runs.get(runId).status() == status) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("esperado " + status + ", atual " + fx.runs.get(runId).status());
    }

    @SuppressWarnings("unused")
    private static Duration seconds(int s) {
        return Duration.ofSeconds(s);
    }
}
