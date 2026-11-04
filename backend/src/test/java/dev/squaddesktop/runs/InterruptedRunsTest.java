package dev.squaddesktop.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.squaddesktop.features.GitWorkspace;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.testsupport.TestDb;

class InterruptedRunsTest {

    @TempDir
    Path dir;

    Database db;
    InterruptedRuns interrupted;
    final GitWorkspace git = new GitWorkspace();
    final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void setUp() {
        db = TestDb.create(dir);
        interrupted = new InterruptedRuns(db, Clock.systemUTC(), git);
        String t = now.toString();
        db.writeVoid(tx -> {
            tx.update("INSERT INTO agent(id,name,role,created_at,updated_at) VALUES('a','Ana','OPERATOR',?,?)", t, t);
            tx.update("INSERT INTO project(id,name,color,created_at,updated_at) VALUES('p','Loja','#000',?,?)", t, t);
            tx.update("INSERT INTO session(id,project_id,agent_id,title,created_at,updated_at) VALUES('s1','p','a','Checkout',?,?)", t, t);
            tx.update("INSERT INTO session(id,project_id,agent_id,title,created_at,updated_at) VALUES('s2','p','a','Busca',?,?)", t, t);
            tx.update("INSERT INTO session(id,project_id,agent_id,title,created_at,updated_at) VALUES('s3','p','a','Antiga',?,?)", t, t);
        });
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void run(String id, String session, String status, String failure, Instant at, String cwd) {
        db.writeVoid(tx -> tx.update("""
                INSERT INTO run(id,session_id,project_id,agent_id,kind,access,status,prompt,cwd,failure_code,queued_at,finished_at)
                VALUES(?,?,'p','a','EXECUTION','WRITE',?,'Implemente o checkout',?,?,?,?)""",
                id, session, status, cwd, failure, at.toString(), at.toString()));
    }

    @Test
    void listaSoAsInterrompidasPeloFechamentoSemContinuacao() {
        run("r1", "s1", "INTERRUPTED", FailureCodes.APP_CLOSED, now.minusSeconds(60), dir.toString());
        // s2: interrompida, mas a pessoa já mandou outra mensagem depois
        run("r2", "s2", "INTERRUPTED", FailureCodes.APP_CLOSED, now.minusSeconds(120), dir.toString());
        run("r3", "s2", "COMPLETED", null, now.minusSeconds(30), dir.toString());
        // s3: antiga demais
        run("r4", "s3", "INTERRUPTED", FailureCodes.APP_CLOSED, now.minus(30, ChronoUnit.DAYS), dir.toString());

        List<InterruptedRuns.Item> items = interrupted.recent(14);

        assertThat(items).extracting(InterruptedRuns.Item::runId).containsExactly("r1");
        InterruptedRuns.Item item = items.get(0);
        assertThat(item.sessionTitle()).isEqualTo("Checkout");
        assertThat(item.projectName()).isEqualTo("Loja");
        assertThat(item.agentName()).isEqualTo("Ana");
        assertThat(item.prompt()).isEqualTo("Implemente o checkout");
    }

    @Test
    void pastaSemGitPedeConferenciaManual() {
        run("r1", "s1", "INTERRUPTED", FailureCodes.APP_CLOSED, now, dir.toString());
        InterruptedRuns.FolderChanges changes = interrupted.changes("r1");
        assertThat(changes.exists()).isTrue();
        assertThat(changes.repository()).isFalse();
        assertThat(changes.note()).contains("não usa Git");
    }

    @Test
    void pastaQueNaoExisteMais() {
        run("r1", "s1", "INTERRUPTED", FailureCodes.APP_CLOSED, now, dir.resolve("sumiu").toString());
        assertThat(interrupted.changes("r1").exists()).isFalse();
    }

    @Test
    void mostraAlteracoesDesdeOUltimoCommitEADiferenca() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(git.available(), "git não instalado");
        Path repo = Files.createDirectories(dir.resolve("repo"));
        exec(repo, "git", "init", "-q");
        exec(repo, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "base");
        Files.writeString(repo.resolve("checkout.ts"), "export const total = 1;\n");
        run("r1", "s1", "INTERRUPTED", FailureCodes.APP_CLOSED, now, repo.toString());

        InterruptedRuns.FolderChanges changes = interrupted.changes("r1");

        assertThat(changes.repository()).isTrue();
        assertThat(changes.changes()).extracting(GitWorkspace.Change::path).containsExactly("checkout.ts");
        assertThat(interrupted.diff("r1", "checkout.ts")).contains("+export const total = 1;");
    }

    @Test
    void execucaoQueNaoFoiInterrompidaNaoTemRevisao() {
        run("r9", "s1", "COMPLETED", null, now, dir.toString());
        assertThatThrownBy(() -> interrupted.changes("r9")).isInstanceOf(ApiException.class);
    }

    private static void exec(Path dir, String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).as(String.join(" ", command)).isZero();
    }
}
