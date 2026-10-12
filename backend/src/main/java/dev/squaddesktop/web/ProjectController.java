package dev.squaddesktop.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.squaddesktop.documents.Author;
import dev.squaddesktop.documents.Document;
import dev.squaddesktop.documents.DocumentKind;
import dev.squaddesktop.documents.DocumentService;
import dev.squaddesktop.documents.DocumentVersion;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.projects.ProjectDraft;
import dev.squaddesktop.projects.ProjectGroup;
import dev.squaddesktop.projects.ProjectService;

@RestController
@RequestMapping("/api")
public class ProjectController {

    /** Projeto com dados calculados para a interface. */
    public record ProjectView(Project project, boolean folderExists) {
        static ProjectView of(Project project) {
            return new ProjectView(project, ProjectService.folderExists(project));
        }
    }

    public record GroupRequest(String name, String color) {
    }

    public record DocumentSave(String title, String content, Integer baseVersion) {
    }

    public record DocumentCreate(DocumentKind kind, String title, String content) {
    }

    public record Restore(Integer version, Integer baseVersion) {
    }

    private final ProjectService projects;
    private final DocumentService documents;

    public ProjectController(ProjectService projects, DocumentService documents) {
        this.projects = projects;
        this.documents = documents;
    }

    @GetMapping("/projects")
    public List<ProjectView> list(@RequestParam(defaultValue = "false") boolean archived, @RequestParam(required = false) String query) {
        return projects.list(archived, query).stream().map(ProjectView::of).toList();
    }

    @GetMapping("/projects/{id}")
    public ProjectView get(@PathVariable String id) {
        return ProjectView.of(projects.get(id));
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView create(@RequestBody ProjectDraft draft) {
        return ProjectView.of(projects.create(draft));
    }

    @PutMapping("/projects/{id}")
    public ProjectView update(@PathVariable String id, @RequestBody ProjectDraft draft) {
        return ProjectView.of(projects.update(id, draft));
    }

    @PostMapping("/projects/{id}/archive")
    public ProjectView archive(@PathVariable String id) {
        return ProjectView.of(projects.setArchived(id, true));
    }

    @PostMapping("/projects/{id}/unarchive")
    public ProjectView unarchive(@PathVariable String id) {
        return ProjectView.of(projects.setArchived(id, false));
    }

    @PostMapping("/projects/{id}/opened")
    public ProjectView opened(@PathVariable String id) {
        return ProjectView.of(projects.markOpened(id));
    }

    /** Exclui o cadastro do projeto. A pasta de código no disco não é tocada. */
    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        projects.delete(id);
    }

    @GetMapping("/groups")
    public List<ProjectGroup> groups() {
        return projects.groups();
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectGroup createGroup(@RequestBody GroupRequest request) {
        return projects.createGroup(request.name(), request.color());
    }

    @PutMapping("/groups/{id}")
    public ProjectGroup updateGroup(@PathVariable String id, @RequestBody GroupRequest request) {
        return projects.updateGroup(id, request.name(), request.color());
    }

    @DeleteMapping("/groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable String id) {
        projects.deleteGroup(id);
    }

    /* ---------- documentos ---------- */

    @GetMapping("/projects/{id}/documents")
    public List<Document> documents(@PathVariable String id) {
        projects.get(id);
        return documents.list(id);
    }

    @PostMapping("/projects/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public Document createDocument(@PathVariable String id, @RequestBody DocumentCreate request) {
        return documents.create(id, request.kind(), request.title(), request.content(), Author.USER);
    }

    @GetMapping("/documents/{id}")
    public Document document(@PathVariable String id) {
        return documents.get(id);
    }

    @PutMapping("/documents/{id}")
    public Document saveDocument(@PathVariable String id, @RequestBody DocumentSave request) {
        if (request.baseVersion() == null) {
            throw dev.squaddesktop.shared.ApiException.invalid("base_version_required", "Informe a versão em que a edição se baseou.");
        }
        return documents.save(id, request.title(), request.content(), request.baseVersion(), Author.USER, null);
    }

    @GetMapping("/documents/{id}/versions")
    public List<DocumentVersion> versions(@PathVariable String id) {
        return documents.versions(id);
    }

    @PostMapping("/documents/{id}/restore")
    public Document restore(@PathVariable String id, @RequestBody Restore request) {
        if (request.version() == null || request.baseVersion() == null) {
            throw dev.squaddesktop.shared.ApiException.invalid("version_required", "Informe a versão a restaurar e a versão atual.");
        }
        return documents.restore(id, request.version(), request.baseVersion());
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable String id) {
        documents.delete(id);
    }
}
