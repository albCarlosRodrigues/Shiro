package dev.squaddesktop.projects;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import dev.squaddesktop.documents.DocumentService;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Colors;
import dev.squaddesktop.shared.Ids;
import dev.squaddesktop.shared.Texts;

public class ProjectService {

    /** Consulta feita antes de excluir ou arquivar: há execução em andamento neste projeto? */
    public interface ActiveWorkProbe {
        boolean hasActiveRuns(String projectId);
    }

    private final Database database;
    private final Clock clock;
    private final DocumentService documents;
    private final Path dataDir;
    private final ProjectRepository repository = new ProjectRepository();
    private volatile ActiveWorkProbe activeWork = projectId -> false;

    public ProjectService(Database database, Clock clock, DocumentService documents, Path dataDir) {
        this.database = database;
        this.clock = clock;
        this.documents = documents;
        this.dataDir = dataDir.toAbsolutePath().normalize();
    }

    public void setActiveWorkProbe(ActiveWorkProbe probe) {
        this.activeWork = probe;
    }

    public List<Project> list(boolean includeArchived, String query) {
        return database.read(tx -> repository.list(tx, includeArchived, query));
    }

    public Project get(String id) {
        return database.read(tx -> repository.find(tx, id)).orElseThrow(() -> ApiException.notFound("Projeto", id));
    }

    public Project create(ProjectDraft draft) {
        return create(draft, false);
    }

    public Project create(ProjectDraft draft, boolean sample) {
        String name = Texts.requireText(draft.name(), "nome", 120);
        String folder = normalizeFolder(draft.folderPath());
        String now = now();
        return database.write(tx -> {
            String groupId = requireGroup(tx, draft.groupId());
            int position = repository.nextPosition(tx);
            Project project = new Project(Ids.newId(), groupId, Texts.trimToNull(draft.squadId()), name,
                    Colors.require(draft.color(), Colors.pick(position)), folder, position, sample, null, null, now, now);
            repository.insert(tx, project);
            documents.createDefaults(project.id());
            return project;
        });
    }

    public Project update(String id, ProjectDraft draft) {
        String name = Texts.requireText(draft.name(), "nome", 120);
        String folder = normalizeFolder(draft.folderPath());
        return database.write(tx -> {
            Project current = repository.find(tx, id).orElseThrow(() -> ApiException.notFound("Projeto", id));
            Project updated = new Project(current.id(), requireGroup(tx, draft.groupId()), Texts.trimToNull(draft.squadId()),
                    name, Colors.require(draft.color(), current.color()), folder, current.position(), current.sample(),
                    current.archivedAt(), current.lastOpenedAt(), current.createdAt(), now());
            repository.update(tx, updated);
            return updated;
        });
    }

    public Project setArchived(String id, boolean archived) {
        if (archived && activeWork.hasActiveRuns(id)) {
            throw ApiException.conflict("project_busy", "Há execuções em andamento neste projeto. Cancele-as antes de arquivar.");
        }
        return database.write(tx -> {
            Project c = repository.find(tx, id).orElseThrow(() -> ApiException.notFound("Projeto", id));
            Project updated = new Project(c.id(), c.groupId(), c.squadId(), c.name(), c.color(), c.folderPath(), c.position(),
                    c.sample(), archived ? now() : null, c.lastOpenedAt(), c.createdAt(), now());
            repository.update(tx, updated);
            return updated;
        });
    }

    public Project markOpened(String id) {
        return database.write(tx -> {
            Project c = repository.find(tx, id).orElseThrow(() -> ApiException.notFound("Projeto", id));
            Project updated = new Project(c.id(), c.groupId(), c.squadId(), c.name(), c.color(), c.folderPath(), c.position(),
                    c.sample(), c.archivedAt(), now(), c.createdAt(), c.updatedAt());
            repository.update(tx, updated);
            return updated;
        });
    }

    /** Remove apenas o cadastro (sessões, documentos e histórico incluídos). A pasta de código nunca é tocada. */
    public void delete(String id) {
        if (activeWork.hasActiveRuns(id)) {
            throw ApiException.conflict("project_busy", "Há execuções em andamento neste projeto. Cancele-as antes de excluir.");
        }
        database.writeVoid(tx -> {
            repository.find(tx, id).orElseThrow(() -> ApiException.notFound("Projeto", id));
            repository.delete(tx, id);
        });
    }

    public long count() {
        return database.read(repository::count);
    }

    /* ---------- grupos ---------- */

    public List<ProjectGroup> groups() {
        return database.read(repository::groups);
    }

    public ProjectGroup createGroup(String name, String color) {
        String clean = Texts.requireText(name, "nome do grupo", 80);
        String now = now();
        return database.write(tx -> {
            int position = repository.nextGroupPosition(tx);
            ProjectGroup group = new ProjectGroup(Ids.newId(), clean, Colors.require(color, Colors.pick(position + 3)), position, now, now);
            repository.insertGroup(tx, group);
            return group;
        });
    }

    public ProjectGroup updateGroup(String id, String name, String color) {
        String clean = Texts.requireText(name, "nome do grupo", 80);
        return database.write(tx -> {
            ProjectGroup current = repository.findGroup(tx, id).orElseThrow(() -> ApiException.notFound("Grupo", id));
            ProjectGroup updated = new ProjectGroup(id, clean, Colors.require(color, current.color()), current.position(),
                    current.createdAt(), now());
            repository.updateGroup(tx, updated);
            return updated;
        });
    }

    /** Exclui o grupo; os projetos dele permanecem, sem grupo. */
    public void deleteGroup(String id) {
        database.writeVoid(tx -> {
            repository.findGroup(tx, id).orElseThrow(() -> ApiException.notFound("Grupo", id));
            repository.deleteGroup(tx, id);
        });
    }

    /* ---------- regras ---------- */

    private String requireGroup(dev.squaddesktop.persistence.Tx tx, String groupId) {
        String id = Texts.trimToNull(groupId);
        if (id != null && repository.findGroup(tx, id).isEmpty()) {
            throw ApiException.invalid("unknown_group", "O grupo informado não existe mais.");
        }
        return id;
    }

    /**
     * Valida a pasta de trabalho: caminho absoluto, que não seja a pasta de dados do aplicativo nem esteja
     * dentro dela. A pasta pode ainda não existir (unidade removível, clone futuro); a interface sinaliza.
     */
    String normalizeFolder(String raw) {
        String text = Texts.trimToNull(raw);
        if (text == null) {
            return null;
        }
        Path path;
        try {
            path = Path.of(text);
        } catch (InvalidPathException e) {
            String encoding = System.getProperty("sun.jnu.encoding", "");
            if (!text.chars().allMatch(c -> c < 128) && !encoding.toUpperCase(java.util.Locale.ROOT).contains("UTF")) {
                throw ApiException.invalid("folder_encoding", "O serviço local foi iniciado com uma localidade (" + encoding
                        + ") que não aceita acentos em caminhos. Inicie-o pelo aplicativo ou com LC_ALL=C.UTF-8.");
            }
            throw ApiException.invalid("invalid_folder", "Caminho de pasta inválido.");
        }
        if (!path.isAbsolute()) {
            throw ApiException.invalid("invalid_folder", "Informe o caminho completo da pasta do projeto.");
        }
        Path normalized = path.normalize();
        if (normalized.startsWith(dataDir)) {
            throw ApiException.invalid("folder_is_app_data",
                    "Esta é a pasta de dados do Squad Desktop. Escolha a pasta em que o código do projeto fica.");
        }
        if (Files.exists(normalized) && !Files.isDirectory(normalized)) {
            throw ApiException.invalid("invalid_folder", "O caminho informado é um arquivo, não uma pasta.");
        }
        return normalized.toString();
    }

    public static boolean folderExists(Project project) {
        if (project.folderPath() == null) {
            return false;
        }
        try {
            return Files.isDirectory(Path.of(project.folderPath()));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private String now() {
        return clock.instant().toString();
    }
}
