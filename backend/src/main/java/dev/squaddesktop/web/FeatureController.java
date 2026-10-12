package dev.squaddesktop.web;

import java.util.Map;

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

import dev.squaddesktop.features.Feature;
import dev.squaddesktop.features.FeatureRunner;
import dev.squaddesktop.features.FeatureService;
import dev.squaddesktop.features.Sprint;

/** Sprints, features, execução autorizada e revisão das entregas. */
@RestController
@RequestMapping("/api")
public class FeatureController {

    public record SprintDraft(String name, String goal) {
    }

    public record Check(boolean value) {
    }

    public record Review(String comment) {
    }

    private final FeatureService features;
    private final FeatureRunner runner;

    public FeatureController(FeatureService features, FeatureRunner runner) {
        this.features = features;
        this.runner = runner;
    }

    @GetMapping("/projects/{projectId}/features")
    public FeatureService.Overview overview(@PathVariable String projectId) {
        return features.overview(projectId);
    }

    @PostMapping("/projects/{projectId}/features")
    @ResponseStatus(HttpStatus.CREATED)
    public Feature create(@PathVariable String projectId, @RequestBody FeatureService.FeatureDraft draft) {
        return features.create(projectId, draft);
    }

    @GetMapping("/features/{id}")
    public FeatureService.FeatureDetail detail(@PathVariable String id) {
        return features.detail(id);
    }

    @PutMapping("/features/{id}")
    public Feature update(@PathVariable String id, @RequestBody FeatureService.FeatureDraft draft) {
        return features.update(id, draft);
    }

    @DeleteMapping("/features/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        features.delete(id);
    }

    @PutMapping("/features/{id}/criteria/{index}")
    public Feature criterion(@PathVariable String id, @PathVariable int index, @RequestBody Check check) {
        return features.setCriterion(id, index, check.value());
    }

    @PutMapping("/features/{id}/tasks/{index}")
    public Feature task(@PathVariable String id, @PathVariable int index, @RequestBody Check check) {
        return features.setTask(id, index, check.value());
    }

    /* sprints */

    @PostMapping("/projects/{projectId}/sprints")
    @ResponseStatus(HttpStatus.CREATED)
    public Sprint createSprint(@PathVariable String projectId, @RequestBody SprintDraft draft) {
        return features.createSprint(projectId, draft.name(), draft.goal());
    }

    @PutMapping("/sprints/{id}")
    public Sprint updateSprint(@PathVariable String id, @RequestBody SprintDraft draft) {
        return features.updateSprint(id, draft.name(), draft.goal());
    }

    @DeleteMapping("/sprints/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSprint(@PathVariable String id) {
        features.deleteSprint(id);
    }

    /* execução e revisão */

    @PostMapping("/features/{id}/authorize")
    public Feature authorize(@PathVariable String id) {
        return runner.authorize(id);
    }

    @PostMapping("/projects/{projectId}/features/authorize-all")
    public Map<String, Object> authorizeAll(@PathVariable String projectId) {
        return Map.of("authorized", runner.authorizeAll(projectId));
    }

    @PostMapping("/projects/{projectId}/features/pause")
    public FeatureService.Overview pause(@PathVariable String projectId) {
        runner.pause(projectId);
        return features.overview(projectId);
    }

    @PostMapping("/projects/{projectId}/features/resume")
    public FeatureService.Overview resume(@PathVariable String projectId) {
        runner.resume(projectId);
        return features.overview(projectId);
    }

    @PostMapping("/features/{id}/cancel")
    public Feature cancel(@PathVariable String id) {
        return runner.cancel(id);
    }

    @PostMapping("/features/{id}/approve")
    public Feature approve(@PathVariable String id, @RequestBody(required = false) Review review) {
        return runner.approve(id, review == null ? null : review.comment());
    }

    @PostMapping("/features/{id}/request-changes")
    public Feature requestChanges(@PathVariable String id, @RequestBody Review review) {
        return runner.requestChanges(id, review.comment());
    }

    @GetMapping("/features/{id}/changes")
    public FeatureRunner.ChangesView changes(@PathVariable String id) {
        return runner.changes(id);
    }

    @GetMapping("/features/{id}/diff")
    public Map<String, Object> diff(@PathVariable String id, @RequestParam String path) {
        return Map.of("path", path, "diff", runner.diff(id, path));
    }
}
