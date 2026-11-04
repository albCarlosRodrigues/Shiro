package dev.squaddesktop.web;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.squaddesktop.features.GitWorkspace;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.runs.InterruptedRuns;

/** Revisão do que ficou pela metade quando o app fechou. Só leitura: continuar é mandar uma nova mensagem. */
@RestController
@RequestMapping("/api/interrupted-runs")
public class InterruptedRunsController {

    private final InterruptedRuns interrupted;

    public InterruptedRunsController(Database database, Clock clock, GitWorkspace git) {
        this.interrupted = new InterruptedRuns(database, clock, git);
    }

    @GetMapping
    public List<InterruptedRuns.Item> list(@RequestParam(value = "days", defaultValue = "14") int days) {
        return interrupted.recent(Math.max(1, Math.min(days, 90)));
    }

    @GetMapping("/{runId}/changes")
    public InterruptedRuns.FolderChanges changes(@PathVariable String runId) {
        return interrupted.changes(runId);
    }

    @GetMapping("/{runId}/diff")
    public Map<String, String> diff(@PathVariable String runId, @RequestParam("path") String path) {
        return Map.of("path", path, "diff", interrupted.diff(runId, path));
    }
}
