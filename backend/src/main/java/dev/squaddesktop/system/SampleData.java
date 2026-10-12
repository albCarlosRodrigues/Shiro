package dev.squaddesktop.system;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.agents.AgentRole;
import dev.squaddesktop.agents.AgentService;
import dev.squaddesktop.agents.AgentTemplate;
import dev.squaddesktop.agents.AgentTemplates;
import dev.squaddesktop.documents.Author;
import dev.squaddesktop.documents.Document;
import dev.squaddesktop.documents.DocumentKind;
import dev.squaddesktop.documents.DocumentService;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.projects.ProjectDraft;
import dev.squaddesktop.projects.ProjectService;
import dev.squaddesktop.sessions.SessionService;
import dev.squaddesktop.squads.Squad;
import dev.squaddesktop.squads.SquadDraft;
import dev.squaddesktop.squads.SquadService;

/**
 * Pontos de partida: os agentes dos modelos com uma squad padrão, e um projeto de exemplo identificado
 * como tal (nome com "(exemplo)" e marca {@code sample}).
 */
public class SampleData {

    public static final String SAMPLE_PROJECT_NAME = "Livraria Aurora (exemplo)";

    private final Database database;
    private final AgentService agents;
    private final SquadService squads;
    private final ProjectService projects;
    private final DocumentService documents;
    private final SessionService sessions;

    public SampleData(Database database, AgentService agents, SquadService squads, ProjectService projects,
            DocumentService documents, SessionService sessions) {
        this.database = database;
        this.agents = agents;
        this.squads = squads;
        this.projects = projects;
        this.documents = documents;
        this.sessions = sessions;
    }

    public record Starter(List<Agent> agents, Squad squad, boolean created) {
    }

    /** Cria os agentes dos modelos e a squad padrão, se ainda não houver nenhum agente. */
    public Starter ensureStarterAgents() {
        return database.write(tx -> {
            if (agents.count() > 0) {
                List<Squad> existing = squads.list(false);
                return new Starter(agents.list(false), existing.isEmpty() ? null : existing.get(0), false);
            }
            List<Agent> created = new ArrayList<>();
            for (AgentTemplate template : AgentTemplates.all()) {
                created.add(agents.create(template.draft()));
            }
            Squad squad = squads.create(new SquadDraft("Squad padrão", "Comandante, requisitos, arquitetura e operadores de Angular, Java, banco de dados e QA.",
                    idOf(created, AgentRole.COMMANDER), idOf(created, AgentRole.REQUIREMENTS), idOf(created, AgentRole.ARCHITECTURE),
                    created.stream().filter(a -> a.role() == AgentRole.OPERATOR).map(Agent::id).toList()));
            return new Starter(created, squad, true);
        });
    }

    /** Cria o projeto de exemplo (sem pasta: as execuções reais pedem que a pessoa escolha uma). */
    public Project loadSampleProject() {
        Starter starter = ensureStarterAgents();
        return database.write(tx -> {
            Project project = projects.create(new ProjectDraft(SAMPLE_PROJECT_NAME, "#e9b44c", null, null,
                    starter.squad() == null ? null : starter.squad().id()), true);
            Map<DocumentKind, String> content = Map.of(
                    DocumentKind.BRIEFING, """
                            Loja on-line de uma livraria de bairro. Hoje as vendas são por mensagem e a equipe perde pedidos.
                            Queremos um catálogo com busca, carrinho e retirada na loja, administrado pela própria equipe.
                            """,
                    DocumentKind.VISION, "Clientes encontram e reservam livros em poucos minutos; a equipe atualiza o estoque sem planilhas.",
                    DocumentKind.SCOPE_IN, """
                            - Catálogo com busca por título, autor e categoria
                            - Carrinho e reserva para retirada na loja
                            - Painel da equipe para cadastrar livros e confirmar reservas
                            """,
                    DocumentKind.SCOPE_OUT, """
                            - Pagamento on-line e entrega
                            - Aplicativo móvel nativo
                            """,
                    DocumentKind.GLOSSARY, """
                            - **Reserva**: pedido de retirada na loja, válido por 48 horas.
                            - **Exemplar**: unidade física de um livro no estoque.
                            """,
                    DocumentKind.ARCHITECTURE, "Frontend Angular, API Java com Spring Boot e banco PostgreSQL. Autenticação da equipe por e-mail e senha.");
            for (Document document : documents.list(project.id())) {
                String text = content.get(document.kind());
                if (text != null) {
                    documents.save(document.id(), null, text.strip(), document.version(), Author.USER, "Dados de exemplo");
                }
            }
            documents.create(project.id(), DocumentKind.ADR, "ADR 001: Retirada na loja em vez de entrega",
                    """
                    **Contexto**: a livraria não tem estrutura de entrega.
                    **Decisão**: oferecer apenas reserva com retirada.
                    **Consequências**: sem cálculo de frete; a reserva expira em 48 horas.
                    """.strip(), Author.USER);
            if (starter.squad() != null) {
                sessions.create(project.id(), starter.squad().commanderId(), "Comandante");
            }
            return project;
        });
    }

    private static String idOf(List<Agent> agents, AgentRole role) {
        return agents.stream().filter(a -> a.role() == role).findFirst().map(Agent::id).orElseThrow();
    }
}
