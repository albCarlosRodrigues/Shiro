package dev.squaddesktop.planning;

import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import dev.squaddesktop.documents.Author;
import dev.squaddesktop.documents.Document;
import dev.squaddesktop.documents.DocumentKind;
import dev.squaddesktop.documents.DocumentService;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.persistence.Row;
import dev.squaddesktop.runs.ProjectSignals;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Ids;
import dev.squaddesktop.shared.Texts;

/**
 * Alterações de documento propostas por agentes. Nada muda no documento até a pessoa aplicar; aplicar grava uma
 * versão nova (o histórico guarda a anterior) e desfazer restaura a versão de antes, desde que ninguém tenha
 * editado o documento nesse meio-tempo. Edições humanas concorrentes nunca são sobrescritas em silêncio.
 */
public class ProposalService {

    public record Proposal(String id, String projectId, String documentId, String kind, String title, String content, String summary,
            String agentId, String messageId, Integer baseVersion, String status, Integer appliedVersion, String createdAt,
            String decidedAt) {
    }

    private static final String COLUMNS = """
            id, project_id, document_id, kind, title, content, summary, agent_id, message_id, base_version, status, applied_version,
            created_at, decided_at""";

    private final Database database;
    private final Clock clock;
    private final DocumentService documents;
    private final ProjectSignals signals;

    public ProposalService(Database database, Clock clock, DocumentService documents, ProjectSignals signals) {
        this.database = database;
        this.clock = clock;
        this.documents = documents;
        this.signals = signals;
    }

    public List<Proposal> list(String projectId) {
        return database.read(tx -> tx.query("SELECT %s FROM document_proposal WHERE project_id = ? ORDER BY created_at DESC".formatted(COLUMNS),
                ProposalService::map, projectId));
    }

    public Proposal get(String id) {
        return database.read(tx -> tx.queryOne("SELECT %s FROM document_proposal WHERE id = ?".formatted(COLUMNS), ProposalService::map, id))
                .orElseThrow(() -> ApiException.notFound("Proposta", id));
    }

    /**
     * Registra a proposta de um bloco {@code squad-proposta}: {"documento": "briefing|visao|escopo|fora_do_escopo|
     * glossario|arquitetura|adr|documento", "titulo": "...", "conteudo": "markdown completo", "resumo": "..."}.
     *
     * @return a proposta, ou vazio quando o bloco não tem conteúdo utilizável (o motivo vai em {@code problem})
     */
    public Optional<Proposal> createFromAgent(String projectId, String agentId, String messageId, Map<String, Object> data,
            StringBuilder problem) {
        String content = str(data, "conteudo", "conteúdo", "content");
        if (Texts.isBlank(content)) {
            problem.append("proposta sem conteúdo");
            return Optional.empty();
        }
        if (content.length() > DocumentService.MAX_CONTENT) {
            problem.append("proposta grande demais");
            return Optional.empty();
        }
        String target = Texts.orEmpty(str(data, "documento", "document", "tipo", "kind"));
        DocumentKind kind = kindOf(target);
        String title = Texts.trimToNull(str(data, "titulo", "título", "title"));
        List<Document> existing = documents.list(projectId);
        Document doc = null;
        for (Document d : existing) {
            if (d.id().equals(target)) {
                doc = d;
            }
        }
        if (doc == null && kind != null && kind.singleton()) {
            doc = existing.stream().filter(d -> d.kind() == kind).findFirst().orElse(null);
        }
        if (doc == null && title != null) {
            for (Document d : existing) {
                if (key(d.title()).equals(key(title)) && (kind == null || d.kind() == kind)) {
                    doc = d;
                }
            }
        }
        DocumentKind effectiveKind = doc != null ? doc.kind() : kind == null ? DocumentKind.FREE : kind;
        String effectiveTitle = doc != null ? doc.title() : title != null ? Texts.truncate(title, 200) : effectiveKind.defaultTitle();
        Document finalDoc = doc;
        String now = now();
        Proposal proposal = new Proposal(Ids.newId(), projectId, doc == null ? null : doc.id(), effectiveKind.name(), effectiveTitle, content,
                Texts.truncate(Texts.orEmpty(str(data, "resumo", "summary")).strip(), 1_000), agentId, messageId,
                finalDoc == null ? null : finalDoc.version(), "PENDING", null, now, null);
        database.writeVoid(tx -> tx.update("""
                INSERT INTO document_proposal (id, project_id, document_id, kind, title, content, summary, agent_id, message_id,
                                               base_version, status, applied_version, created_at, decided_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                proposal.id(), projectId, proposal.documentId(), proposal.kind(), proposal.title(), proposal.content(), proposal.summary(),
                agentId, messageId, proposal.baseVersion(), "PENDING", null, now, null));
        signals.changed(projectId, "proposals", proposal.id());
        return Optional.of(proposal);
    }

    /**
     * Aplica a proposta. Se o documento mudou desde que o agente o leu, recusa com {@code proposal_outdated} e
     * devolve as duas versões; com {@code force} aplica por cima (a versão humana continua no histórico).
     */
    public Proposal apply(String id, boolean force) {
        Proposal p = get(id);
        if (!"PENDING".equals(p.status())) {
            throw ApiException.conflict("proposal_decided", "Esta proposta já foi " + statusLabel(p.status()) + ".");
        }
        Author author = p.agentId() == null ? Author.USER : Author.agent(p.agentId());
        Document saved;
        if (p.documentId() != null) {
            Document current = documents.get(p.documentId());
            if (p.baseVersion() != null && current.version() != p.baseVersion() && !force) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("currentVersion", current.version());
                details.put("baseVersion", p.baseVersion());
                details.put("currentContent", current.content());
                details.put("proposedContent", p.content());
                throw ApiException.conflict("proposal_outdated",
                        "O documento foi alterado depois que o agente fez a proposta. Compare as versões antes de aplicar.", details);
            }
            saved = documents.save(current.id(), current.title(), p.content(), current.version(), author,
                    "Antes da proposta aplicada" + (p.agentId() == null ? "" : " (agente)"));
        } else {
            DocumentKind kind = DocumentKind.valueOf(p.kind());
            if (kind.singleton()) {
                Document current = documents.findSingleton(p.projectId(), kind).orElseThrow(() -> ApiException.notFound("Documento", kind.name()));
                saved = documents.save(current.id(), current.title(), p.content(), current.version(), author, "Antes da proposta aplicada");
            } else {
                saved = documents.create(p.projectId(), kind, p.title(), p.content(), author);
            }
        }
        String now = now();
        Document finalSaved = saved;
        database.writeVoid(tx -> tx.update("""
                UPDATE document_proposal SET status = 'APPLIED', document_id = ?, applied_version = ?, decided_at = ? WHERE id = ?""",
                finalSaved.id(), finalSaved.version(), now, id));
        signals.changed(p.projectId(), "proposals", id);
        return get(id);
    }

    public Proposal discard(String id) {
        Proposal p = get(id);
        if (!"PENDING".equals(p.status())) {
            throw ApiException.conflict("proposal_decided", "Esta proposta já foi " + statusLabel(p.status()) + ".");
        }
        database.writeVoid(tx -> tx.update("UPDATE document_proposal SET status = 'DISCARDED', decided_at = ? WHERE id = ?", now(), id));
        signals.changed(p.projectId(), "proposals", id);
        return get(id);
    }

    /** Desfaz uma proposta aplicada, se o documento não mudou depois disso. Documento criado pela proposta é removido. */
    public Proposal undo(String id) {
        Proposal p = get(id);
        if (!"APPLIED".equals(p.status()) || p.documentId() == null || p.appliedVersion() == null) {
            throw ApiException.conflict("proposal_not_applied", "Só é possível desfazer propostas aplicadas.");
        }
        Document current = documents.get(p.documentId());
        if (current.version() != p.appliedVersion()) {
            throw ApiException.conflict("proposal_undo_conflict",
                    "O documento mudou depois que a proposta foi aplicada. Use o histórico de versões para restaurar o que quiser.");
        }
        if (p.baseVersion() == null && p.appliedVersion() == 1 && !DocumentKind.valueOf(p.kind()).singleton()) {
            documents.delete(current.id());
        } else {
            // a versão imediatamente anterior à aplicação (que pode conter edições humanas feitas depois da proposta)
            documents.restore(current.id(), p.appliedVersion() - 1, current.version());
        }
        database.writeVoid(tx -> tx.update("UPDATE document_proposal SET status = 'UNDONE', decided_at = ? WHERE id = ?", now(), id));
        signals.changed(p.projectId(), "proposals", id);
        return get(id);
    }

    static DocumentKind kindOf(String raw) {
        String k = key(raw);
        return switch (k) {
            case "briefing" -> DocumentKind.BRIEFING;
            case "visao", "visaodoproduto", "vision" -> DocumentKind.VISION;
            case "escopo", "escopoincluido", "scope", "scopein" -> DocumentKind.SCOPE_IN;
            case "foradoescopo", "escopoexcluido", "scopeout" -> DocumentKind.SCOPE_OUT;
            case "glossario", "glossary" -> DocumentKind.GLOSSARY;
            case "arquitetura", "architecture" -> DocumentKind.ARCHITECTURE;
            case "adr", "decisao", "decisaoarquitetural" -> DocumentKind.ADR;
            case "documento", "livre", "free", "doc" -> DocumentKind.FREE;
            default -> {
                try {
                    yield raw == null ? null : DocumentKind.valueOf(raw.strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "APPLIED" -> "aplicada";
            case "DISCARDED" -> "descartada";
            case "UNDONE" -> "desfeita";
            default -> "decidida";
        };
    }

    private static String key(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String str(Map<String, Object> data, String... keys) {
        for (String k : keys) {
            Object v = data.get(k);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private static Proposal map(Row row) throws SQLException {
        return new Proposal(row.str("id"), row.str("project_id"), row.str("document_id"), row.str("kind"), row.str("title"), row.str("content"),
                row.strOrEmpty("summary"), row.str("agent_id"), row.str("message_id"), row.integerOrNull("base_version"), row.str("status"),
                row.integerOrNull("applied_version"), row.str("created_at"), row.str("decided_at"));
    }

    private String now() {
        return clock.instant().toString();
    }
}
