package dev.squaddesktop.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.agents.AgentDraft;
import dev.squaddesktop.agents.AgentMarkdown;
import dev.squaddesktop.agents.AgentRules;
import dev.squaddesktop.agents.AgentService;
import dev.squaddesktop.agents.AgentTemplate;
import dev.squaddesktop.agents.AgentTemplates;
import dev.squaddesktop.agents.InstructionComposer;
import dev.squaddesktop.claude.ClaudeCapabilities;
import dev.squaddesktop.claude.ClaudeEnvironment;
import dev.squaddesktop.documents.DocumentService;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.projects.ProjectService;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Colors;
import dev.squaddesktop.shared.Texts;

@RestController
@RequestMapping("/api")
public class AgentController {

    /**
     * Opções para os campos do Estúdio do Agente. Modelos e níveis de esforço vêm do Claude Code instalado;
     * quando ele não informa, a lista fica vazia e a interface aceita texto livre.
     */
    public record AgentOptions(List<String> permissionModes, List<String> effortLevels, List<String> modelAliases,
            Set<String> icons, List<String> colors, String cliVersion, boolean effortSupported) {
    }

    public record CreateRequest(String templateKey, AgentDraft draft) {
    }

    public record AllowToolRequest(String rule) {
    }

    /** @param turnPreamble texto que antecede cada mensagem no modo escolhido */
    public record Preview(String instructions, String turnPreamble) {
    }

    public record Removal(String result) {
    }

    private final AgentService agents;
    private final ProjectService projects;
    private final DocumentService documents;
    private final ClaudeEnvironment claude;

    public AgentController(AgentService agents, ProjectService projects, DocumentService documents, ClaudeEnvironment claude) {
        this.agents = agents;
        this.projects = projects;
        this.documents = documents;
        this.claude = claude;
    }

    @GetMapping("/agents")
    public List<Agent> list(@RequestParam(defaultValue = "false") boolean archived) {
        return agents.list(archived);
    }

    @GetMapping("/agents/{id}")
    public Agent get(@PathVariable String id) {
        return agents.get(id);
    }

    @GetMapping("/agent-templates")
    public List<AgentTemplate> templates() {
        return AgentTemplates.all();
    }

    @GetMapping("/agent-options")
    public AgentOptions options() {
        ClaudeCapabilities caps = claude.statusNow().capabilities();
        List<String> efforts = caps.effortLevels().isEmpty() ? AgentRules.EFFORT_LEVELS : caps.effortLevels();
        List<String> modes = caps.permissionModes().isEmpty() ? AgentRules.PERMISSION_MODES
                : AgentRules.PERMISSION_MODES.stream().filter(mode -> caps.permissionModes().contains(mode)
                        || ("default".equals(mode) && caps.permissionModes().contains("manual"))).toList();
        return new AgentOptions(modes, efforts, caps.modelAliases(), AgentRules.ICONS, Colors.PALETTE, caps.version(), caps.effort());
    }

    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    public Agent create(@RequestBody CreateRequest request) {
        if (!Texts.isBlank(request.templateKey()) && request.draft() == null) {
            return agents.createFromTemplate(request.templateKey());
        }
        return agents.create(request.draft());
    }

    @PutMapping("/agents/{id}")
    public Agent update(@PathVariable String id, @RequestBody AgentDraft draft) {
        return agents.update(id, draft);
    }

    @PostMapping("/agents/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public Agent duplicate(@PathVariable String id) {
        return agents.duplicate(id);
    }

    /** Libera uma regra de ferramenta para o agente (por exemplo, depois de uma negação). Vale nas próximas execuções. */
    @PostMapping("/agents/{id}/allowed-tools")
    public Agent allowTool(@PathVariable String id, @RequestBody AllowToolRequest request) {
        return agents.allowTool(id, request.rule() == null ? null : request.rule().strip());
    }

    @DeleteMapping("/agents/{id}")
    public Removal delete(@PathVariable String id) {
        return new Removal(agents.delete(id) == AgentService.Removal.DELETED ? "deleted" : "archived");
    }

    /** Prévia das instruções finais, como o Claude Code as receberá. Com draft, mostra o rascunho ainda não salvo. */
    @PostMapping("/agent-preview")
    public Preview preview(@RequestBody PreviewRequest request) {
        AgentDraft draft = AgentRules.validate(request.draft());
        Agent agent = new Agent("preview", draft.name(), draft.role(), draft.specialty(), draft.icon(), draft.color(),
                draft.responsibility(), draft.personality(), draft.instructions(), draft.guidelines(), draft.model(), draft.effort(),
                draft.permissionMode(), draft.tools(), draft.allowedTools(), draft.disallowedTools(), draft.templateKey(), false,
                null, null, null);
        Project project = Texts.isBlank(request.projectId()) ? null : projects.get(request.projectId());
        InstructionComposer.Mode mode = "EXECUTION".equals(request.mode()) ? InstructionComposer.Mode.EXECUTION : InstructionComposer.Mode.CONVERSATION;
        return new Preview(InstructionComposer.compose(agent, project, project == null ? List.of() : documents.list(project.id())),
                InstructionComposer.turnPreamble(mode));
    }

    public record PreviewRequest(AgentDraft draft, String projectId, String mode) {
    }

    @GetMapping(value = "/agents/{id}/export", produces = "text/markdown")
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        Agent agent = agents.get(id);
        if (agent == null) {
            throw ApiException.notFound("Agente", id);
        }
        byte[] body = AgentMarkdown.export(agent).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(AgentMarkdown.fileName(agent), StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .body(body);
    }
}
