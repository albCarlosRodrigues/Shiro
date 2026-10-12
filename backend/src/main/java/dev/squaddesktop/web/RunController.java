package dev.squaddesktop.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.squaddesktop.runs.EventStore;
import dev.squaddesktop.runs.Run;
import dev.squaddesktop.runs.RunOrchestrator;
import dev.squaddesktop.runs.StoredEvent;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunOrchestrator runs;
    private final EventStore events;

    public RunController(RunOrchestrator runs, EventStore events) {
        this.runs = runs;
        this.events = events;
    }

    @GetMapping("/{id}")
    public Run get(@PathVariable String id) {
        return runs.get(id);
    }

    @GetMapping("/{id}/events")
    public List<StoredEvent> events(@PathVariable String id) {
        runs.get(id);
        return events.byRun(id);
    }

    /** Encerra a execução e os processos que ela iniciou. Não afeta a fila. */
    @PostMapping("/{id}/cancel")
    public Run cancel(@PathVariable String id) {
        return runs.cancel(id);
    }

    @GetMapping("/active")
    public List<Run> active() {
        return runs.unfinished();
    }

    @GetMapping("/queue")
    public RunOrchestrator.QueueState queue() {
        return runs.queueState();
    }

    /** Impede que novas execuções comecem; as que já estão rodando continuam. */
    @PostMapping("/queue/pause")
    public RunOrchestrator.QueueState pause() {
        runs.pauseQueue();
        return runs.queueState();
    }

    @PostMapping("/queue/resume")
    public RunOrchestrator.QueueState resume() {
        runs.resumeQueue();
        return runs.queueState();
    }
}
