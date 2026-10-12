package dev.squaddesktop.projects;

/**
 * Cadastro de um projeto.
 *
 * @param folderPath pasta local em que os agentes leem e escrevem código; o banco do aplicativo nunca fica dentro dela
 * @param sample     criado pelos dados de exemplo; a interface o identifica como tal
 */
public record Project(
        String id,
        String groupId,
        String squadId,
        String name,
        String color,
        String folderPath,
        int position,
        boolean sample,
        String archivedAt,
        String lastOpenedAt,
        String createdAt,
        String updatedAt) {

    public boolean archived() {
        return archivedAt != null;
    }
}
