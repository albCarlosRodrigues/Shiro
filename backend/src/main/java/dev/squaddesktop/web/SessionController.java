package dev.squaddesktop.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.squaddesktop.runs.EventStore;
import dev.squaddesktop.runs.Run;
import dev.squaddesktop.runs.RunKind;
import dev.squaddesktop.runs.RunOrchestrator;
import dev.squaddesktop.runs.StoredEvent;
import dev.squaddesktop.sessions.Message;
import dev.squaddesktop.sessions.Session;
import dev.squaddesktop.sessions.SessionService;

@RestController
@RequestMapping("/api")
public class SessionController {

    public record CreateSession(String projectId, String agentId, String title) {
    }

    public record Rename(String title) {
    }

    /** Mensagem da pessoa. {@code mode} EXECUTION autoriza o agente a alterar a pasta do projeto; o padrão é conversa. */
    public record SendMessage(String content, RunKind mode) {
    }

    private final SessionService sessions;
    private final RunOrchestrator runs;
    private final EventStore events;

    public SessionController(SessionService sessions, RunOrchestrator runs, EventStore events) {
        this.sessions = sessions;
        this.runs = runs;
        this.events = events;
    }

    @GetMapping("/projects/{projectId}/sessions")
    public List<Session> byProject(@PathVariable String projectId, @RequestParam(defaultValue = "false") boolean archived) {
        return sessions.listByProject(projectId, archived);
    }

    @GetMapping("/sessions/recent")
    public List<Session> recent(@RequestParam(defaultValue = "12") int limit) {
        return sessions.recent(limit);
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Session create(@RequestBody CreateSession request) {
        return sessions.create(request.projectId(), request.agentId(), request.title());
    }

    @GetMapping("/sessions/{id}")
    public Session get(@PathVariable String id) {
        return sessions.get(id);
    }

    @PutMapping("/sessions/{id}")
    public Session rename(@PathVariable String id, @RequestBody Rename request) {
        return sessions.rename(id, request.title());
    }

    @PostMapping("/sessions/{id}/reset-context")
    public Session resetContext(@PathVariable String id) {
        return sessions.resetContext(id);
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        sessions.delete(id);
    }

    @GetMapping("/sessions/{id}/messages")
    public List<Message> messages(@PathVariable String id) {
        sessions.get(id);
        return sessions.messages(id);
    }

    @PostMapping("/sessions/{id}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunOrchestrator.Submission send(@PathVariable String id, @RequestBody SendMessage request) {
        return runs.submit(id, request.content(), request.mode());
    }

    @GetMapping("/sessions/{id}/runs")
    public List<Run> runs(@PathVariable String id, @RequestParam(defaultValue = "50") int limit) {
        return runs.bySession(id, limit);
    }

    /** Eventos recentes da sessão (abas Atividade e Console), em ordem cronológica. */
    @GetMapping("/sessions/{id}/events")
    public List<StoredEvent> events(@PathVariable String id, @RequestParam(defaultValue = "400") int limit) {
        return events.recentBySession(id, limit);
    }
}
