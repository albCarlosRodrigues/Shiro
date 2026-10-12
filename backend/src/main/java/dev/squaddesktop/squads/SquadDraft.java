package dev.squaddesktop.squads;

import java.util.List;

public record SquadDraft(String name, String description, String commanderId, String requirementsId, String architectureId,
        List<String> operatorIds) {
}
