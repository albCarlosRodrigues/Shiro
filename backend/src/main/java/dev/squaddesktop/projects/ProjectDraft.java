package dev.squaddesktop.projects;

/** Dados editáveis de um projeto. Campos nulos em atualização mantêm o valor atual, exceto onde indicado. */
public record ProjectDraft(String name, String color, String folderPath, String groupId, String squadId) {
}
