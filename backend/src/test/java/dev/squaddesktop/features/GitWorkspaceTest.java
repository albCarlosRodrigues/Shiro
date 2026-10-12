package dev.squaddesktop.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Operações git reais em repositórios temporários (pulado quando o git não está instalado). */
class GitWorkspaceTest {

    @TempDir
    Path dir;

    private final GitWorkspace git = new GitWorkspace();
    private Path repo;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(git.available(), "git não instalado");
        repo = Files.createDirectories(dir.resolve("repositório com espaço"));
        run("init", "-q", "-b", "main");
        run("config", "user.email", "teste@example.com");
        run("config", "user.name", "Teste");
        write("README.md", "olá\n");
        run("add", "-A");
        run("commit", "-q", "-m", "inicial");
    }

    @Test
    void linhaDeBaseEnxergaArquivosNovosSemMexerNoIndice() throws Exception {
        String base = git.snapshot(repo);
        write("README.md", "olá\nmundo\n");
        write("novo/arquivo ç.txt", "x\n");
        List<GitWorkspace.Change> changes = git.changes(repo, base);
        assertThat(changes).extracting(GitWorkspace.Change::path).containsExactlyInAnyOrder("README.md", "novo/arquivo ç.txt");
        assertThat(changes).filteredOn(c -> c.path().equals("README.md")).singleElement()
                .satisfies(c -> assertThat(c.additions()).isEqualTo(1));
        assertThat(git.diff(repo, base, "README.md")).contains("+mundo");
        assertThat(git.run(repo, List.of("status", "--porcelain"), Map.of()).stdout()).as("índice intocado").contains("?? novo/");
    }

    @Test
    void worktreeIsoladoIntegraComMergeEConflitoEAbortado() throws Exception {
        GitWorkspace.Worktree wt = git.worktree(repo, dir.resolve("wt").resolve("f1"), "squad/f1");
        Files.writeString(wt.path().resolve("feature.txt"), "feito\n");
        assertThat(git.changes(wt.path(), wt.baseTree())).extracting(GitWorkspace.Change::path).containsExactly("feature.txt");
        assertThat(git.commitAll(wt.path(), "feat: f1")).isNotBlank();
        GitWorkspace.MergeResult ok = git.merge(repo, "squad/f1", "Integra F1");
        assertThat(ok.merged()).isTrue();
        assertThat(repo.resolve("feature.txt")).exists();
        git.cleanup(repo, wt.path(), "squad/f1", true);
        assertThat(wt.path()).doesNotExist();

        GitWorkspace.Worktree wt2 = git.worktree(repo, dir.resolve("wt").resolve("f2"), "squad/f2");
        Files.writeString(wt2.path().resolve("README.md"), "versão da feature\n");
        git.commitAll(wt2.path(), "feat: f2");
        write("README.md", "versão humana\n");
        run("commit", "-q", "-am", "mudança humana");
        GitWorkspace.MergeResult conflict = git.merge(repo, "squad/f2", "Integra F2");
        assertThat(conflict.merged()).isFalse();
        assertThat(conflict.conflicts()).containsExactly("README.md");
        assertThat(Files.readString(repo.resolve("README.md"))).as("merge desfeito").isEqualTo("versão humana\n");
        assertThat(git.run(repo, List.of("status", "--porcelain"), Map.of()).stdout()).isBlank();
    }

    private void write(String path, String content) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void run(String... args) {
        GitWorkspace.Result r = git.run(repo, List.of(args), Map.of());
        assertThat(r.exitCode()).as(String.join(" ", args) + ": " + r.stderr()).isZero();
    }
}
