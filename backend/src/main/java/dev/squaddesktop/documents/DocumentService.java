package dev.squaddesktop.documents;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.persistence.Row;
import dev.squaddesktop.persistence.Tx;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Ids;
import dev.squaddesktop.shared.Texts;

/**
 * Documentos versionados do projeto.
 *
 * <p>Toda gravação informa a versão em que o autor se baseou. Se o documento mudou nesse intervalo, a
 * gravação é recusada com {@code document_conflict} e devolve o conteúdo atual, para que edições humanas
 * e propostas de agentes nunca se sobrescrevam em silêncio. Cada versão substituída fica no histórico.
 */
public class DocumentService {

    public static final int MAX_CONTENT = 400_000;

    private static final String COLUMNS = """
            id, project_id, kind, title, content, version, position, updated_by_type, updated_by_id, created_at, updated_at""";

    private final Database database;
    private final Clock clock;

    public DocumentService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public List<Document> list(String projectId) {
        return database.read(tx -> tx.query(
                "SELECT %s FROM document WHERE project_id = ? ORDER BY CASE kind WHEN 'BRIEFING' THEN 0 WHEN 'VISION' THEN 1 WHEN 'SCOPE_IN' THEN 2 WHEN 'SCOPE_OUT' THEN 3 WHEN 'GLOSSARY' THEN 4 WHEN 'ARCHITECTURE' THEN 5 WHEN 'ADR' THEN 6 ELSE 7 END, position, created_at"
                        .formatted(COLUMNS),
                DocumentService::map, projectId));
    }

    public Document get(String id) {
        return database.read(tx -> find(tx, id)).orElseThrow(() -> ApiException.notFound("Documento", id));
    }

    public Optional<Document> findSingleton(String projectId, DocumentKind kind) {
        return database.read(tx -> tx.queryOne("SELECT %s FROM document WHERE project_id = ? AND kind = ?".formatted(COLUMNS),
                DocumentService::map, projectId, kind));
    }

    /** Cria os documentos fixos de um projeto novo (vazios). Chamado dentro da transação de criação do projeto. */
    public void createDefaults(String projectId) {
        String now = now();
        database.writeVoid(tx -> {
            for (DocumentKind kind : DocumentKind.values()) {
                if (kind.singleton()) {
                    insert(tx, new Document(Ids.newId(), projectId, kind, kind.defaultTitle(), "", 1, 0, Author.Type.USER, null, now, now));
                }
            }
        });
    }

    /** Cria um documento que pode se repetir no projeto (ADR ou livre). */
    public Document create(String projectId, DocumentKind kind, String title, String content, Author author) {
        if (kind == null || kind.singleton()) {
            throw ApiException.invalid("invalid_kind", "Só decisões arquiteturais e documentos livres podem ser criados.");
        }
        String cleanTitle = Texts.requireText(title, "título", 200);
        String cleanContent = Texts.optionalText(content, "conteúdo", MAX_CONTENT);
        String now = now();
        return database.write(tx -> {
            if (tx.scalarLong("SELECT COUNT(*) FROM project WHERE id = ?", projectId) == 0) {
                throw ApiException.notFound("Projeto", projectId);
            }
            int position = (int) tx.scalarLong("SELECT COALESCE(MAX(position), -1) + 1 FROM document WHERE project_id = ? AND kind = ?", projectId, kind);
            Document document = new Document(Ids.newId(), projectId, kind, cleanTitle, cleanContent, 1, position,
                    author.type(), author.agentId(), now, now);
            insert(tx, document);
            return document;
        });
    }

    /**
     * Grava uma nova versão.
     *
     * @param baseVersion versão que o autor tinha aberta; precisa ser a atual
     * @param note        observação opcional guardada com a versão anterior (por exemplo, "proposta de Arquiteto aplicada")
     */
    public Document save(String id, String title, String content, int baseVersion, Author author, String note) {
        String cleanContent = Texts.optionalText(content, "conteúdo", MAX_CONTENT);
        return database.write(tx -> {
            Document current = find(tx, id).orElseThrow(() -> ApiException.notFound("Documento", id));
            String cleanTitle = current.kind().singleton() ? current.title() : Texts.requireText(title == null ? current.title() : title, "título", 200);
            if (baseVersion != current.version()) {
                throw ApiException.conflict("document_conflict",
                        "O documento foi alterado enquanto você editava. Revise a versão atual antes de salvar.",
                        Map.of("currentVersion", current.version(), "currentContent", current.content(), "currentTitle", current.title()));
            }
            if (current.content().equals(cleanContent) && current.title().equals(cleanTitle)) {
                return current;
            }
            String now = now();
            archive(tx, current, note);
            tx.update("""
                    UPDATE document SET title = ?, content = ?, version = ?, updated_by_type = ?, updated_by_id = ?, updated_at = ?
                    WHERE id = ?""",
                    cleanTitle, cleanContent, current.version() + 1, author.type(), author.agentId(), now, id);
            return new Document(current.id(), current.projectId(), current.kind(), cleanTitle, cleanContent, current.version() + 1,
                    current.position(), author.type(), author.agentId(), current.createdAt(), now);
        });
    }

    public List<DocumentVersion> versions(String id) {
        return database.read(tx -> tx.query("""
                SELECT document_id, version, title, content, author_type, author_id, note, created_at
                FROM document_version WHERE document_id = ? ORDER BY version DESC""",
                DocumentService::mapVersion, id));
    }

    /** Restaura o conteúdo de uma versão anterior como uma nova versão (o histórico nunca é reescrito). */
    public Document restore(String id, int version, int baseVersion) {
        DocumentVersion old = database.read(tx -> tx.queryOne("""
                SELECT document_id, version, title, content, author_type, author_id, note, created_at
                FROM document_version WHERE document_id = ? AND version = ?""",
                DocumentService::mapVersion, id, version))
                .orElseThrow(() -> ApiException.notFound("Versão do documento", id + "@" + version));
        return save(id, old.title(), old.content(), baseVersion, Author.USER, "Restaurado da versão " + version);
    }

    public void delete(String id) {
        database.writeVoid(tx -> {
            Document current = find(tx, id).orElseThrow(() -> ApiException.notFound("Documento", id));
            if (current.kind().singleton()) {
                throw ApiException.invalid("fixed_document", "Este documento faz parte de todo projeto e não pode ser excluído; esvazie o conteúdo se preferir.");
            }
            tx.update("DELETE FROM document WHERE id = ?", id);
        });
    }

    private void archive(Tx tx, Document current, String note) {
        tx.update("""
                INSERT INTO document_version (document_id, version, title, content, author_type, author_id, note, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                current.id(), current.version(), current.title(), current.content(), current.updatedByType(),
                current.updatedById(), Texts.trimToNull(note), current.updatedAt());
    }

    private static void insert(Tx tx, Document d) {
        tx.update("""
                INSERT INTO document (id, project_id, kind, title, content, version, position, updated_by_type, updated_by_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                d.id(), d.projectId(), d.kind(), d.title(), d.content(), d.version(), d.position(), d.updatedByType(),
                d.updatedById(), d.createdAt(), d.updatedAt());
    }

    private static Optional<Document> find(Tx tx, String id) {
        return tx.queryOne("SELECT %s FROM document WHERE id = ?".formatted(COLUMNS), DocumentService::map, id);
    }

    private static Document map(Row row) throws SQLException {
        return new Document(row.str("id"), row.str("project_id"), DocumentKind.valueOf(row.str("kind")), row.str("title"),
                row.str("content"), row.integer("version"), row.integer("position"),
                Author.Type.valueOf(row.str("updated_by_type")), row.str("updated_by_id"), row.str("created_at"), row.str("updated_at"));
    }

    private static DocumentVersion mapVersion(Row row) throws SQLException {
        return new DocumentVersion(row.str("document_id"), row.integer("version"), row.str("title"), row.str("content"),
                Author.Type.valueOf(row.str("author_type")), row.str("author_id"), row.str("note"), row.str("created_at"));
    }

    private String now() {
        return clock.instant().toString();
    }
}
