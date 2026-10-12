package dev.squaddesktop.documents;

public record Document(
        String id,
        String projectId,
        DocumentKind kind,
        String title,
        String content,
        int version,
        int position,
        Author.Type updatedByType,
        String updatedById,
        String createdAt,
        String updatedAt) {
}
