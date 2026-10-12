package dev.squaddesktop.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.squaddesktop.features.FeatureService;
import dev.squaddesktop.planning.PlanningService;
import dev.squaddesktop.planning.ProposalService;
import dev.squaddesktop.plans.PlanContent;
import dev.squaddesktop.plans.PlanService;

/** Sala de planejamento, propostas de documentos e planos. */
@RestController
@RequestMapping("/api")
public class PlanningController {

    public record Post(String content) {
    }

    public record Apply(boolean force) {
    }

    private final PlanningService planning;
    private final ProposalService proposals;
    private final PlanService plans;

    public PlanningController(PlanningService planning, ProposalService proposals, PlanService plans) {
        this.planning = planning;
        this.proposals = proposals;
        this.plans = plans;
    }

    @GetMapping("/projects/{projectId}/planning")
    public PlanningService.Room room(@PathVariable String projectId, @RequestParam(required = false) Integer before,
            @RequestParam(defaultValue = "120") int limit) {
        return planning.room(projectId, before, limit);
    }

    @PostMapping("/projects/{projectId}/planning/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanningService.Room post(@PathVariable String projectId, @RequestBody Post request) {
        return planning.post(projectId, request.content());
    }

    @PostMapping("/projects/{projectId}/planning/stop")
    public PlanningService.Room stop(@PathVariable String projectId) {
        return planning.stop(projectId);
    }

    /* propostas */

    @GetMapping("/projects/{projectId}/proposals")
    public List<ProposalService.Proposal> proposals(@PathVariable String projectId) {
        return proposals.list(projectId);
    }

    @PostMapping("/proposals/{id}/apply")
    public ProposalService.Proposal apply(@PathVariable String id, @RequestBody(required = false) Apply request) {
        return proposals.apply(id, request != null && request.force());
    }

    @PostMapping("/proposals/{id}/discard")
    public ProposalService.Proposal discard(@PathVariable String id) {
        return proposals.discard(id);
    }

    @PostMapping("/proposals/{id}/undo")
    public ProposalService.Proposal undo(@PathVariable String id) {
        return proposals.undo(id);
    }

    /* planos */

    @GetMapping("/projects/{projectId}/plans")
    public List<PlanService.Plan> plans(@PathVariable String projectId) {
        return plans.list(projectId);
    }

    @PostMapping("/projects/{projectId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanService.Plan createPlan(@PathVariable String projectId) {
        return plans.createBlank(projectId);
    }

    @GetMapping("/plans/{id}")
    public PlanService.Plan plan(@PathVariable String id) {
        return plans.get(id);
    }

    @PutMapping("/plans/{id}")
    public PlanService.Plan updatePlan(@PathVariable String id, @RequestBody PlanContent content) {
        return plans.update(id, content);
    }

    @PostMapping("/plans/{id}/discard")
    public PlanService.Plan discardPlan(@PathVariable String id) {
        return plans.discard(id);
    }

    /** Aprova o plano: cria sprints e features planejadas. Não autoriza nenhuma execução. */
    @PostMapping("/plans/{id}/approve")
    public FeatureService.ApplyResult approvePlan(@PathVariable String id) {
        return plans.approve(id);
    }
}
