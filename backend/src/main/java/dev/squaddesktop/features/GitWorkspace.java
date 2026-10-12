package dev.squaddesktop.features;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Operações git usadas pelas features. Nunca reescreve o histórico nem mexe no índice da pessoa:
 *
 * <ul>
 * <li>a linha de base de uma feature é um retrato da árvore de arquivos (incluindo arquivos novos não ignorados),
 * gravado como objeto git com um índice temporário;</li>
 * <li>as alterações da revisão comparam essa linha de base com um retrato atual;</li>
 * <li>no modo isolado, cada feature trabalha em um {@code git worktree} com branch própria, e a aprovação faz o
 * merge (sem fast-forward) na branch atual do repositório. Conflitos abortam o merge e são devolvidos.</li>
 * </ul>
 */
public class GitWorkspace {

    private static final Logger LOG = Logger.getLogger(GitWorkspace.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_OUTPUT = 2 * 1024 * 1024;
    public static final int MAX_DIFF_CHARS = 200_000;

    public record Change(String path, String status, Integer additions, Integer deletions) {
    }

    public record Worktree(Path path, String branch, String baseTree) {
    }

    public record MergeResult(boolean merged, String commit, List<String> conflicts, String message) {
    }

    record Result(int exitCode, String stdout, String stderr) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    public static class GitException extends RuntimeException {
        public GitException(String message) {
            super(message);
        }
    }

    private final String git;
    private volatile Boolean available;

    public GitWorkspace() {
        this("git");
    }

    public GitWorkspace(String executable) {
        this.git = executable;
    }

    public boolean available() {
        Boolean known = available;
        if (known == null) {
            try {
                known = run(null, List.of("--version"), Map.of()).ok();
            } catch (GitException e) {
                known = false;
            }
            available = known;
        }
        return known;
    }

    public boolean isRepository(Path folder) {
        if (folder == null || !Files.isDirectory(folder) || !available()) {
            return false;
        }
        try {
            Result r = run(folder, List.of("rev-parse", "--is-inside-work-tree"), Map.of());
            return r.ok() && r.stdout().strip().equals("true");
        } catch (GitException e) {
            return false;
        }
    }

    public boolean hasCommits(Path repo) {
        return run(repo, List.of("rev-parse", "--verify", "--quiet", "HEAD"), Map.of()).ok();
    }

    /** Retrato da árvore de trabalho atual (arquivos rastreados e novos não ignorados), sem tocar no índice da pessoa. */
    public String snapshot(Path dir) {
        Path tempIndex = null;
        try {
            tempIndex = Files.createTempFile("squad-index-", ".tmp");
            Files.delete(tempIndex);
            String realIndex = run(dir, List.of("rev-parse", "--git-path", "index"), Map.of()).stdout().strip();
            Path real = dir.resolve(realIndex);
            if (!realIndex.isEmpty() && Files.isRegularFile(real)) {
                Files.copy(real, tempIndex); // acelera: só arquivos alterados são relidos
            }
            Map<String, String> env = Map.of("GIT_INDEX_FILE", tempIndex.toString());
            require(run(dir, List.of("add", "-A", "--", "."), env), "registrar o retrato dos arquivos");
            return require(run(dir, List.of("write-tree"), env), "gravar o retrato dos arquivos").stdout().strip();
        } catch (IOException e) {
            throw new GitException("Não foi possível criar o índice temporário: " + e.getMessage());
        } finally {
            if (tempIndex != null) {
                try {
                    Files.deleteIfExists(tempIndex);
                } catch (IOException ignored) {
                    // arquivo temporário
                }
            }
        }
    }

    /** Alterações entre a linha de base e o estado atual da pasta. */
    public List<Change> changes(Path dir, String baseTree) {
        String current = snapshot(dir);
        Result names = require(run(dir, List.of("diff", "--name-status", "--no-renames", "-z", baseTree, current), Map.of()), "listar as alterações");
        Result stats = require(run(dir, List.of("diff", "--numstat", "--no-renames", "-z", baseTree, current), Map.of()), "contar as alterações");
        Map<String, int[]> counts = new HashMap<>();
        String[] parts = stats.stdout().split("\0");
        for (String part : parts) {
            String[] cols = part.split("\t", 3);
            if (cols.length == 3) {
                counts.put(cols[2], new int[] {parse(cols[0]), parse(cols[1])});
            }
        }
        List<Change> out = new ArrayList<>();
        String[] tokens = names.stdout().split("\0");
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            String status = tokens[i].strip();
            String path = tokens[i + 1];
            int[] c = counts.get(path);
            out.add(new Change(path, statusLabel(status), c == null || c[0] < 0 ? null : c[0], c == null || c[1] < 0 ? null : c[1]));
        }
        return out;
    }

    /** Diferença unificada de um arquivo entre a linha de base e o estado atual, truncada. */
    public String diff(Path dir, String baseTree, String path) {
        String current = snapshot(dir);
        Result r = require(run(dir, List.of("diff", "--no-renames", "--no-color", baseTree, current, "--", ":(top)" + path), Map.of()), "gerar a diferença");
        String text = r.stdout();
        return text.length() > MAX_DIFF_CHARS ? text.substring(0, MAX_DIFF_CHARS) + "\n… (diferença truncada)\n" : text;
    }

    /** Cria (ou reaproveita) o worktree da feature, a partir do commit atual do repositório. */
    public Worktree worktree(Path repo, Path target, String branch) {
        if (!hasCommits(repo)) {
            throw new GitException("O repositório ainda não tem nenhum commit; faça o primeiro commit para executar em paralelo.");
        }
        if (Files.isDirectory(target) && isRepository(target)) {
            String tree = require(run(target, List.of("rev-parse", "HEAD^{tree}"), Map.of()), "ler o worktree").stdout().strip();
            return new Worktree(target, branch, tree);
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new GitException("Não foi possível criar a pasta do worktree: " + e.getMessage());
        }
        boolean branchExists = run(repo, List.of("rev-parse", "--verify", "--quiet", "refs/heads/" + branch), Map.of()).ok();
        List<String> args = branchExists
                ? List.of("worktree", "add", target.toString(), branch)
                : List.of("worktree", "add", "-b", branch, target.toString(), "HEAD");
        require(run(repo, args, Map.of()), "criar o worktree");
        String tree = require(run(target, List.of("rev-parse", "HEAD^{tree}"), Map.of()), "ler o worktree").stdout().strip();
        return new Worktree(target, branch, tree);
    }

    /** Registra tudo o que mudou no worktree em um commit na branch da feature. Devolve null se não havia mudanças. */
    public String commitAll(Path worktree, String message) {
        require(run(worktree, List.of("add", "-A", "--", "."), Map.of()), "preparar o commit");
        if (run(worktree, List.of("diff", "--cached", "--quiet"), Map.of()).ok()) {
            return null;
        }
        require(run(worktree, withIdentity(worktree, List.of("commit", "--no-verify", "-m", message)), Map.of()), "fazer o commit da feature");
        return require(run(worktree, List.of("rev-parse", "HEAD"), Map.of()), "ler o commit").stdout().strip();
    }

    /** Faz o merge da branch da feature na branch atual do repositório. Em conflito, aborta e devolve os arquivos. */
    public MergeResult merge(Path repo, String branch, String message) {
        Result r = run(repo, withIdentity(repo, List.of("merge", "--no-ff", "--no-edit", "-m", message, branch)), Map.of());
        if (r.ok()) {
            String commit = require(run(repo, List.of("rev-parse", "HEAD"), Map.of()), "ler o commit").stdout().strip();
            return new MergeResult(true, commit, List.of(), "Merge concluído.");
        }
        List<String> conflicts = new ArrayList<>();
        Result u = run(repo, List.of("diff", "--name-only", "--diff-filter=U", "-z"), Map.of());
        for (String p : u.stdout().split("\0")) {
            if (!p.isBlank()) {
                conflicts.add(p);
            }
        }
        if (Files.exists(gitPath(repo, "MERGE_HEAD"))) {
            run(repo, List.of("merge", "--abort"), Map.of());
        }
        String detail = (r.stderr() + "\n" + r.stdout()).strip();
        return new MergeResult(false, null, conflicts, conflicts.isEmpty()
                ? "O git recusou o merge: " + firstLines(detail, 4)
                : "O merge tem conflitos em " + conflicts.size() + (conflicts.size() == 1 ? " arquivo." : " arquivos."));
    }

    /** Remove o worktree e a branch da feature depois do merge. Falhas aqui só geram aviso no log. */
    public void cleanup(Path repo, Path worktree, String branch, boolean deleteBranch) {
        try {
            run(repo, List.of("worktree", "remove", "--force", worktree.toString()), Map.of());
            if (deleteBranch) {
                run(repo, List.of("branch", "-D", branch), Map.of());
            }
        } catch (GitException e) {
            LOG.log(Level.WARNING, "Falha ao limpar o worktree " + worktree, e);
        }
    }

    private Path gitPath(Path repo, String name) {
        String p = run(repo, List.of("rev-parse", "--git-path", name), Map.of()).stdout().strip();
        return repo.resolve(p);
    }

    /** Usa a identidade configurada pela pessoa; só na falta dela assina como Squad Desktop. */
    private List<String> withIdentity(Path dir, List<String> args) {
        boolean hasEmail = run(dir, List.of("config", "user.email"), Map.of()).ok();
        boolean hasName = run(dir, List.of("config", "user.name"), Map.of()).ok();
        List<String> out = new ArrayList<>();
        if (!hasName) {
            out.addAll(List.of("-c", "user.name=Squad Desktop"));
        }
        if (!hasEmail) {
            out.addAll(List.of("-c", "user.email=squad-desktop@localhost"));
        }
        out.addAll(args);
        return out;
    }

    private static String statusLabel(String code) {
        return switch (code.isEmpty() ? '?' : code.charAt(0)) {
            case 'A' -> "added";
            case 'D' -> "deleted";
            case 'M' -> "modified";
            case 'T' -> "type";
            default -> "modified";
        };
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return -1; // arquivo binário
        }
    }

    private static String firstLines(String text, int n) {
        String[] lines = text.split("\\R");
        return String.join(" ", java.util.Arrays.copyOf(lines, Math.min(n, lines.length))).strip();
    }

    private Result require(Result r, String action) {
        if (!r.ok()) {
            throw new GitException("Falha ao " + action + " com o git: " + firstLines((r.stderr() + " " + r.stdout()).strip(), 3));
        }
        return r;
    }

    Result run(Path dir, List<String> args, Map<String, String> env) {
        List<String> command = new ArrayList<>();
        command.add(git);
        command.addAll(List.of("-c", "core.quotepath=off", "-c", "core.autocrlf=false", "-c", "color.ui=never"));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (dir != null) {
            builder.directory(dir.toFile());
        }
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("LC_ALL", builder.environment().getOrDefault("LC_ALL", "C.UTF-8"));
        builder.environment().putAll(env);
        builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null")));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GitException("Não foi possível executar o git (" + git + "): " + e.getMessage());
        }
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread a = Thread.ofVirtual().start(() -> drain(process.getInputStream(), out));
        Thread b = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), err));
        try {
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GitException("O git não respondeu a tempo (" + String.join(" ", args) + ").");
            }
            a.join(2_000);
            b.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GitException("Operação git interrompida.");
        }
        return new Result(process.exitValue(), out.toString(), err.toString());
    }

    private static void drain(InputStream in, StringBuilder target) {
        try (in) {
            byte[] bytes = in.readNBytes(MAX_OUTPUT);
            synchronized (target) {
                target.append(new String(bytes, StandardCharsets.UTF_8));
            }
            in.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // processo encerrado
        }
    }
}
