package dev.squaddesktop.features;

import java.util.List;

/**
 * Feature do projeto.
 *
 * @param revision   quantas vezes a revisão pediu ajustes
 * @param workdir    pasta de trabalho isolada (git worktree) quando o projeto executa em paralelo; null usa a pasta do projeto
 * @param baseCommit commit de partida, para listar as alterações na revisão quando a pasta é um repositório git
 * @param statusNote explicação curta do estado (por exemplo, a falha ou o que está aguardando)
 */
public record Feature(
        String id,
        String projectId,
        String sprintId,
        String planId,
        String code,
        String title,
        String description,
        String priority,
        String specialty,
        String ownerAgentId,
        List<Criterion> acceptanceCriteria,
        List<Task> tasks,
        FeatureStatus status,
        int revision,
        int position,
        String workdir,
        String branch,
        String baseCommit,
        String statusNote,
        List<String> dependsOn,
        String createdAt,
        String updatedAt) {

    public record Criterion(String text, boolean met) {
    }

    public Feature withStatus(FeatureStatus next, String note, String now) {
        return new Feature(id, projectId, sprintId, planId, code, title, description, priority, specialty, ownerAgentId,
                acceptanceCriteria, tasks, next, revision, position, workdir, branch, baseCommit, note, dependsOn, createdAt, now);
    }

    public Feature withWork(String newWorkdir, String newBranch, String newBaseCommit, String now) {
        return new Feature(id, projectId, sprintId, planId, code, title, description, priority, specialty, ownerAgentId,
                acceptanceCriteria, tasks, status, revision, position, newWorkdir, newBranch, newBaseCommit, statusNote, dependsOn,
                createdAt, now);
    }

    public Feature withRevision(int newRevision, String now) {
        return new Feature(id, projectId, sprintId, planId, code, title, description, priority, specialty, ownerAgentId,
                acceptanceCriteria, tasks, status, newRevision, position, workdir, branch, baseCommit, statusNote, dependsOn,
                createdAt, now);
    }

    public Feature withChecklists(List<Criterion> criteria, List<Task> newTasks, String now) {
        return new Feature(id, projectId, sprintId, planId, code, title, description, priority, specialty, ownerAgentId,
                criteria, newTasks, status, revision, position, workdir, branch, baseCommit, statusNote, dependsOn, createdAt, now);
    }

    /** Ordem de prioridade para a fila: alta antes de média antes de baixa. */
    public int priorityRank() {
        return switch (priority == null ? "MEDIUM" : priority) {
            case "HIGH" -> 0;
            case "LOW" -> 2;
            default -> 1;
        };
    }

    public record Task(String text, boolean done) {
    }
}
