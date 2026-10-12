package dev.squaddesktop.projects;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import dev.squaddesktop.persistence.Row;
import dev.squaddesktop.persistence.Tx;

class ProjectRepository {

    private static final String COLUMNS = """
            id, group_id, squad_id, name, color, folder_path, position, sample,
            archived_at, last_opened_at, created_at, updated_at""";

    List<Project> list(Tx tx, boolean includeArchived, String query) {
        String like = query == null || query.isBlank() ? null : "%" + escapeLike(query.strip().toLowerCase()) + "%";
        return tx.query("""
                SELECT %s FROM project
                WHERE (? = 1 OR archived_at IS NULL)
                  AND (? IS NULL OR lower(name) LIKE ? ESCAPE '\\' OR lower(COALESCE(folder_path, '')) LIKE ? ESCAPE '\\')
                ORDER BY position, lower(name)""".formatted(COLUMNS),
                ProjectRepository::map, includeArchived ? 1 : 0, like, like, like);
    }

    Optional<Project> find(Tx tx, String id) {
        return tx.queryOne("SELECT %s FROM project WHERE id = ?".formatted(COLUMNS), ProjectRepository::map, id);
    }

    void insert(Tx tx, Project p) {
        tx.update("""
                INSERT INTO project (id, group_id, squad_id, name, color, folder_path, position, sample,
                                     archived_at, last_opened_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                p.id(), p.groupId(), p.squadId(), p.name(), p.color(), p.folderPath(), p.position(), p.sample(),
                p.archivedAt(), p.lastOpenedAt(), p.createdAt(), p.updatedAt());
    }

    void update(Tx tx, Project p) {
        tx.update("""
                UPDATE project
                SET group_id = ?, squad_id = ?, name = ?, color = ?, folder_path = ?, position = ?,
                    archived_at = ?, last_opened_at = ?, updated_at = ?
                WHERE id = ?""",
                p.groupId(), p.squadId(), p.name(), p.color(), p.folderPath(), p.position(),
                p.archivedAt(), p.lastOpenedAt(), p.updatedAt(), p.id());
    }

    void delete(Tx tx, String id) {
        tx.update("DELETE FROM project WHERE id = ?", id);
    }

    int nextPosition(Tx tx) {
        return (int) tx.scalarLong("SELECT COALESCE(MAX(position), -1) + 1 FROM project");
    }

    long count(Tx tx) {
        return tx.scalarLong("SELECT COUNT(*) FROM project");
    }

    List<ProjectGroup> groups(Tx tx) {
        return tx.query("SELECT id, name, color, position, created_at, updated_at FROM project_group ORDER BY position, lower(name)",
                ProjectRepository::mapGroup);
    }

    Optional<ProjectGroup> findGroup(Tx tx, String id) {
        return tx.queryOne("SELECT id, name, color, position, created_at, updated_at FROM project_group WHERE id = ?",
                ProjectRepository::mapGroup, id);
    }

    void insertGroup(Tx tx, ProjectGroup g) {
        tx.update("INSERT INTO project_group (id, name, color, position, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                g.id(), g.name(), g.color(), g.position(), g.createdAt(), g.updatedAt());
    }

    void updateGroup(Tx tx, ProjectGroup g) {
        tx.update("UPDATE project_group SET name = ?, color = ?, position = ?, updated_at = ? WHERE id = ?",
                g.name(), g.color(), g.position(), g.updatedAt(), g.id());
    }

    void deleteGroup(Tx tx, String id) {
        tx.update("DELETE FROM project_group WHERE id = ?", id);
    }

    int nextGroupPosition(Tx tx) {
        return (int) tx.scalarLong("SELECT COALESCE(MAX(position), -1) + 1 FROM project_group");
    }

    private static Project map(Row row) throws SQLException {
        return new Project(row.str("id"), row.str("group_id"), row.str("squad_id"), row.str("name"), row.str("color"),
                row.str("folder_path"), row.integer("position"), row.bool("sample"), row.str("archived_at"),
                row.str("last_opened_at"), row.str("created_at"), row.str("updated_at"));
    }

    private static ProjectGroup mapGroup(Row row) throws SQLException {
        return new ProjectGroup(row.str("id"), row.str("name"), row.str("color"), row.integer("position"),
                row.str("created_at"), row.str("updated_at"));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
