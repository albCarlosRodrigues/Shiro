package dev.squaddesktop.runs;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import dev.squaddesktop.features.GitWorkspace;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.shared.ApiException;

/**
 * O que ficou pela metade quando o app fechou: execuções marcadas como interrompidas na inicialização
 * ({@link FailureCodes#APP_CLOSED}) e ainda sem continuação na mesma conversa. Nada aqui retoma trabalho;
 * a interface mostra a lista, as alterações da pasta e deixa a pessoa decidir.
 */
public class InterruptedRuns {

    /** Árvore vazia do Git: base para repositórios ainda sem commits. */
    static final String EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
    static final int MAX_ITEMS = 20;
    static final int MAX_CHANGES = 200;

    public record Item(String runId, String sessionId, String sessionTitle, String projectId, String projectName, String agentName,
            String kind, String access, String prompt, String cwd, String interruptedAt, boolean demo) {
    }

    /**
     * Alterações da pasta da execução em relação ao último commit. Podem incluir mudanças que já existiam antes da
     * execução; a interface avisa isso.
     */
    public record FolderChanges(String cwd, boolean exists, boolean repository, List<GitWorkspace.Change> changes, boolean truncated,
            String note) {
    }

    private final Database database;
    private final Clock clock;
    private final GitWorkspace git;

    public InterruptedRuns(Database database, Clock clock, GitWorkspace git) {
        this.database = database;
        this.clock = clock;
        this.git = git;
    }

    /** Interrompidas pelo fechamento do app nos últimos {@code days} dias, sem execução posterior na mesma conversa. */
    public List<Item> recent(int days) {
        String since = clock.instant().minus(Duration.ofDays(days)).toString();
        return database.read(tx -> tx.query("""
                SELECT r.id, r.session_id, s.title, r.project_id, p.name AS project_name, a.name AS agent_name, r.kind, r.access,
                       r.prompt, r.cwd, COALESCE(r.finished_at, r.queued_at) AS interrupted_at, r.demo
                FROM run r
                JOIN session s ON s.id = r.session_id
                JOIN project p ON p.id = r.project_id
                LEFT JOIN agent a ON a.id = r.agent_id
                WHERE r.status = 'INTERRUPTED' AND r.failure_code = ?
                  AND COALESCE(r.finished_at, r.queued_at) >= ?
                  AND NOT EXISTS (SELECT 1 FROM run later WHERE later.session_id = r.session_id AND later.queued_at > r.queued_at)
                ORDER BY interrupted_at DESC
                LIMIT %d""".formatted(MAX_ITEMS), row -> new Item(row.str("id"), row.str("session_id"), row.str("title"),
                row.str("project_id"), row.str("project_name"), row.str("agent_name"), row.str("kind"), row.str("access"),
                row.str("prompt"), row.str("cwd"), row.str("interrupted_at"), row.bool("demo")), FailureCodes.APP_CLOSED, since));
    }

    public FolderChanges changes(String runId) {
        Path dir = folder(runId);
        if (dir == null || !Files.isDirectory(dir)) {
            return new FolderChanges(dir == null ? null : dir.toString(), false, false, List.of(), false,
                    "A pasta da execução não existe mais.");
        }
        if (!git.isRepository(dir)) {
            return new FolderChanges(dir.toString(), true, false, List.of(), false,
                    "A pasta não usa Git: confira os arquivos manualmente antes de continuar.");
        }
        List<GitWorkspace.Change> all = git.changes(dir, base(dir));
        boolean truncated = all.size() > MAX_CHANGES;
        return new FolderChanges(dir.toString(), true, true, truncated ? all.subList(0, MAX_CHANGES) : all, truncated,
                all.isEmpty() ? "Nenhuma alteração desde o último commit." : null);
    }

    public String diff(String runId, String file) {
        if (file == null || file.isBlank() || file.contains("\0")) {
            throw ApiException.invalid("path_required", "Informe o arquivo.");
        }
        Path dir = folder(runId);
        if (dir == null || !Files.isDirectory(dir) || !git.isRepository(dir)) {
            throw ApiException.invalid("no_repository", "A pasta da execução não tem Git.");
        }
        return git.diff(dir, base(dir), file);
    }

    private String base(Path dir) {
        return git.hasCommits(dir) ? "HEAD" : EMPTY_TREE;
    }

    private Path folder(String runId) {
        String cwd = database.read(tx -> tx.queryOne("SELECT cwd FROM run WHERE id = ? AND status = 'INTERRUPTED'",
                row -> row.strOrEmpty("cwd"), runId)).orElseThrow(() -> ApiException.notFound("execução interrompida", runId));
        if (cwd.isBlank()) {
            return null;
        }
        try {
            return Path.of(cwd);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
