package dev.squaddesktop.documents;

public record DocumentVersion(
        String documentId,
        int version,
        String title,
        String content,
        Author.Type authorType,
        String authorId,
        String note,
        String createdAt) {
}
