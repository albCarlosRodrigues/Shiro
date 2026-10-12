package dev.squaddesktop.squads;

import java.util.List;

/** Uma squad: um comandante, um especialista em requisitos, um em arquitetura e ao menos um operador. */
public record Squad(
        String id,
        String name,
        String description,
        String commanderId,
        String requirementsId,
        String architectureId,
        List<String> operatorIds,
        boolean sample,
        String archivedAt,
        String createdAt,
        String updatedAt) {

    public List<String> memberIds() {
        List<String> all = new java.util.ArrayList<>(List.of(commanderId, requirementsId, architectureId));
        all.addAll(operatorIds);
        return all;
    }
}
